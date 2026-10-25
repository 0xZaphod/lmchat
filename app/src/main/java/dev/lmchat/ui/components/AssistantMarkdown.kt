package dev.lmchat.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/** Selectable native Markdown text, with conflated background rendering during SSE updates. */
@Composable
fun AssistantMarkdown(text: String, modifier: Modifier = Modifier, isScrolling: Boolean = false) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val contentColor = colors.onSurface.toArgb()
    val linkColor = colors.primary.toArgb()
    val codeColor = colors.surface.toArgb()
    val outlineColor = colors.outlineVariant.toArgb()
    // Each bubble owns a renderer: table visitors have mutable state during rendering.
    val renderer = remember(context, contentColor, linkColor, codeColor, outlineColor) {
        createMarkdownRenderer(context, contentColor, linkColor, codeColor, outlineColor)
    }
    val latestText by rememberUpdatedState(text)
    val latestIsScrolling by rememberUpdatedState(isScrolling)
    val rendered by produceState<Spanned>(SpannableString(text), renderer) {
        snapshotFlow { latestText to latestIsScrolling }.conflate().collect { (source, scrolling) ->
            // Replacing selectable TextView text reflows the entire response and
            // disturbs native touch/selection handling. Catch up after the gesture
            // and fling instead of relaying every token into an active scroll.
            if (scrolling) return@collect
            val parsed = withContext(Dispatchers.Default) { renderer.toMarkdown(source) }
            if (!latestIsScrolling) value = parsed
            // Conflate rapid tokens without delaying the final update indefinitely.
            delay(50)
        }
    }
    val typography = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val fontSize = with(density) { typography.fontSize.toPx() }
    val lineSpacing = with(density) { typography.lineHeight.toPx() } - fontSize

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TextView(viewContext).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
                setTextIsSelectable(true)
            }
        },
        update = { view ->
            view.setTextColor(contentColor)
            view.setLinkTextColor(linkColor)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
            val spacing = lineSpacing.coerceAtLeast(0f)
            // setLineSpacing requests layout even when its arguments are unchanged.
            if (view.lineSpacingExtra != spacing || view.lineSpacingMultiplier != 1f) {
                view.setLineSpacing(spacing, 1f)
            }
            if (view.tag !== rendered) {
                renderer.setParsedMarkdown(view, rendered)
                view.tag = rendered
            }
        },
    )
}

internal fun createMarkdownRenderer(
    context: Context,
    contentColor: Int,
    linkColor: Int,
    codeColor: Int,
    outlineColor: Int,
): Markwon = Markwon.builder(context)
    .usePlugin(StrikethroughPlugin.create())
    .usePlugin(TablePlugin.create { theme ->
        theme.tableBorderColor(outlineColor)
            .tableHeaderRowBackgroundColor(codeColor)
            .tableOddRowBackgroundColor(Color.TRANSPARENT)
            .tableEvenRowBackgroundColor(Color.TRANSPARENT)
            .tableCellPadding((8 * context.resources.displayMetrics.density).toInt())
    })
    .usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureTheme(builder: MarkwonTheme.Builder) {
            builder.linkColor(linkColor)
                .codeTextColor(contentColor)
                .codeBlockTextColor(contentColor)
                .codeBackgroundColor(codeColor)
                .codeBlockBackgroundColor(codeColor)
                .blockQuoteColor(linkColor)
                .headingBreakColor(outlineColor)
                .thematicBreakColor(outlineColor)
                .headingTextSizeMultipliers(floatArrayOf(1.6f, 1.4f, 1.2f, 1.1f, 1f, 1f))
        }

        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
            builder.linkResolver { view, link ->
                val uri = Uri.parse(link)
                if (uri.scheme?.lowercase() in setOf("http", "https", "mailto")) {
                    try {
                        view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: ActivityNotFoundException) {
                        // A device may have no browser or mail app installed.
                    }
                }
            }
        }
    })
    .build()
