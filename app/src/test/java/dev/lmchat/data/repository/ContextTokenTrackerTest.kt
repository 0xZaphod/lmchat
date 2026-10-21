package dev.lmchat.data.repository

import dev.lmchat.data.remote.ChatBodyBuilder
import dev.lmchat.domain.Attachment
import dev.lmchat.domain.AttachmentKind
import org.junit.Assert.*
import org.junit.Test

class ContextTokenTrackerTest {
    @Test fun streamedOutputIsAddedToTheExistingPromptOnlyOnce() {
        val tracker = ContextTokenTracker(120)
        tracker.output("12345678")
        assertEquals(ContextTokenCount(122, true), tracker.snapshot())
        tracker.output("123456789012")
        assertEquals(ContextTokenCount(123, true), tracker.snapshot())
        tracker.output("123456789012")
        assertEquals(ContextTokenCount(123, true), tracker.snapshot())
    }

    @Test fun reportedTotalsReplaceEstimatesRatherThanBeingAddedToThem() {
        val tracker = ContextTokenTracker(120)
        tracker.output("12345678")
        tracker.reported(130)
        assertEquals(ContextTokenCount(130, false), tracker.snapshot())
        tracker.output("123456789012")
        assertEquals(ContextTokenCount(131, true), tracker.snapshot())
        tracker.reported(134)
        assertEquals(ContextTokenCount(134, false), tracker.snapshot())
    }

    @Test fun nextTurnAddsOnlyTheNewUserMessageToThePreviousTotal() {
        val userMessage = ChatBodyBuilder.textMessage("user", "12345678")
        val tracker = ContextTokenTracker(130 + estimateMessageTokens(userMessage))
        tracker.output("12345678")
        assertEquals(ContextTokenCount(138, true), tracker.snapshot())
    }

    @Test fun imageBase64IsNotCountedAsPromptText() {
        val image = Attachment(AttachmentKind.IMAGE, "photo.jpg", "image/jpeg", "a".repeat(10000))
        val textOnly = ChatBodyBuilder.userMessage("Describe", emptyList())
        val withImage = ChatBodyBuilder.userMessage("Describe", listOf(image))
        assertEquals(estimateMessageTokens(textOnly), estimateMessageTokens(withImage))
    }
}
