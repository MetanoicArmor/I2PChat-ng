package org.i2pchat.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyQuoteTest {
    @Test
    fun summarizeCollapsesWhitespaceAndTruncates() {
        assertEquals("a b c", summarizeForReply("a\n\nb  c"))
        assertTrue(summarizeForReply("x".repeat(200)).endsWith("…"))
        assertEquals(120, summarizeForReply("x".repeat(200)).length)
        assertEquals("photo.png", summarizeForReply("ignored", " photo.png "))
    }

    @Test
    fun formatReplyQuoteIsOneLine() {
        val s = formatReplyQuote("Peer", "line1\nline2")
        assertTrue(s.contains("@Peer wrote:"))
        assertTrue(s.contains("> line1 line2"))
        assertEquals(1, s.lines().count { it.startsWith(">") })
        assertTrue(s.endsWith("\n\n"))
    }

    @Test
    fun deletedPlaceholderRoundtrip() {
        val msg = formatReplyQuote("me", "secret\nlines") + "ok"
        val scrubbed = replaceQuoteWithDeleted(msg)
        assertTrue(isDeletedReplyQuote(scrubbed))
        val layout = splitReplyQuote(scrubbed)!!
        assertEquals(DELETED_REPLY_AUTHOR, layout.author)
        assertEquals(DELETED_REPLY_MARKER, layout.quoted)
        assertEquals("ok", layout.answer)
        assertEquals(formatDeletedReplyQuote("ok"), scrubbed)
    }

    @Test
    fun quoteMatchesAndScrub() {
        val source = "hello\nworld"
        val reply = formatReplyQuote("peer", source) + "thanks"
        assertTrue(quoteMatchesSource(splitReplyQuote(reply)!!.quoted, source))
        assertEquals(
            formatDeletedReplyQuote("thanks"),
            scrubReplyTextIfMatches(reply, source),
        )
        assertNull(scrubReplyTextIfMatches(reply, "other"))
        assertEquals(
            formatDeletedReplyQuote("y"),
            scrubReplyTextIfMatches(
                formatReplyQuote("peer", "x", "a.bin") + "y",
                "x",
                "a.bin",
            ),
        )
    }

    @Test
    fun findQuotedMessageSkipsDeleted() {
        val messages = listOf(
            MessageUi(kind = "in", text = "hello", ts = "1"),
            MessageUi(
                kind = "out",
                text = formatDeletedReplyQuote("thanks"),
                ts = "2",
            ),
        )
        val quote = splitReplyQuote(messages[1].text)!!
        assertTrue(quote.isDeleted)
        assertEquals(-1, findQuotedMessage(messages, 1, quote))
    }

    @Test
    fun findQuotedMessageMatchesSummary() {
        val source = "hello\nworld"
        val messages = listOf(
            MessageUi(kind = "in", text = source, ts = "1"),
            MessageUi(
                kind = "out",
                text = formatReplyQuote("peer", source) + "ok",
                ts = "2",
            ),
        )
        val quote = splitReplyQuote(messages[1].text)!!
        assertFalse(quote.isDeleted)
        assertEquals(0, findQuotedMessage(messages, 1, quote))
    }
}
