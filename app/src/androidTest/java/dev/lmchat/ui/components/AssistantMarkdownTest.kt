package dev.lmchat.ui.components

import android.text.Spanned
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lmchat.ui.theme.LMChatTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantMarkdownTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun renderer() = createMarkdownRenderer(context, -1, -3355444, -15658735, -7829368)
    private fun Spanned.hasSpan(name: String) =
        getSpans(0, length, Any::class.java).any { it.javaClass.simpleName == name }

    @Test fun commonMarkdownIsStyledRatherThanShownAsMarkup() {
        val rendered = renderer().toMarkdown("""
            # Heading

            **bold**, *italic*, ~~removed~~, `inline`

            > quotation

            - first
            - second

            1. ordered

            [Example](https://example.com)

            ```kotlin
            val greeting = "hello"
            ```
        """.trimIndent())
        assertTrue(rendered.toString().contains("Heading"))
        assertFalse(rendered.toString().contains("**bold**"))
        for (span in listOf("HeadingSpan", "StrongEmphasisSpan", "EmphasisSpan", "StrikethroughSpan",
            "CodeSpan", "BlockQuoteSpan", "BulletListItemSpan", "OrderedListItemSpan", "LinkSpan", "CodeBlockSpan")) {
            assertTrue("Missing $span", rendered.hasSpan(span))
        }
    }

    @Test fun tablesAndUnfinishedStreamingBlocksRenderWithoutCrashing() {
        val renderer = renderer()
        val markdown = """
            | Name | Value |
            | --- | --- |
            | answer | **42** |

            ```kotlin
            val answer = 42
            ```
        """.trimIndent()
        // Model tokens may stop anywhere, including inside a table or fence.
        for (end in 0..markdown.length) renderer.toMarkdown(markdown.take(end))
        val rendered = renderer.toMarkdown(markdown)
        assertTrue(rendered.hasSpan("TableRowSpan"))
        assertTrue(rendered.hasSpan("CodeBlockSpan"))
    }

    @Test fun scrollingHoldsNativeTextSteadyAndCatchesUpWhenItStops() {
        val text = mutableStateOf("Initial response")
        val scrolling = mutableStateOf(false)
        var view: TextView? = null
        compose.setContent {
            LMChatTheme { AssistantMarkdown(text.value, isScrolling = scrolling.value) }
        }
        fun findTextView(node: View): TextView? {
            if (node is TextView) return node
            if (node is ViewGroup) for (index in 0 until node.childCount) {
                findTextView(node.getChildAt(index))?.let { return it }
            }
            return null
        }
        compose.runOnIdle {
            view = findTextView(compose.activity.findViewById(android.R.id.content))
            scrolling.value = true
        }
        compose.runOnIdle { text.value = "Newest **streamed** response" }
        Thread.sleep(250)
        compose.runOnIdle {
            assertEquals("Initial response", view!!.text.toString())
            assertTrue(view!!.isTextSelectable)
            scrolling.value = false
        }
        compose.waitUntil(5000) {
            var caughtUp = false
            compose.runOnIdle { caughtUp = view?.text?.toString() == "Newest streamed response" }
            caughtUp
        }
    }

    @Test fun streamingUpdatesReachSelectableNativeTextInsideAChatList() {
        val text = mutableStateOf("**Hel")
        var view: TextView? = null
        compose.setContent {
            LMChatTheme {
                LazyColumn(Modifier.fillMaxWidth()) {
                    item { AssistantMarkdown(text.value, Modifier.fillMaxWidth()) }
                }
            }
        }
        fun findTextView(node: View): TextView? {
            if (node is TextView) return node
            if (node is ViewGroup) for (index in 0 until node.childCount) {
                findTextView(node.getChildAt(index))?.let { return it }
            }
            return null
        }
        compose.waitForIdle()
        compose.runOnIdle {
            view = findTextView(compose.activity.findViewById(android.R.id.content))
        }
        assertNotNull(view)
        compose.runOnIdle { text.value = "# Result\n\n**Hello**, `world`\n\n```kotlin\nval done = true" }
        compose.waitUntil(5000) {
            var complete = false
            compose.runOnIdle { complete = view?.text?.contains("val done = true") == true }
            complete
        }
        compose.runOnIdle {
            assertTrue(view!!.isTextSelectable)
            assertTrue(view!!.measuredWidth > 0)
            assertTrue(view!!.measuredHeight > 0)
            val rendered = view!!.text as Spanned
            assertTrue(rendered.hasSpan("HeadingSpan"))
            assertTrue(rendered.hasSpan("StrongEmphasisSpan"))
            assertTrue(rendered.hasSpan("CodeBlockSpan"))
            assertFalse(rendered.toString().contains("**Hello**"))
        }
    }
}
