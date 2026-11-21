package org.i2pchat.android

/** Author used in the quote header when replying to one's own message. */
const val REPLY_AUTHOR_ME = "me"

/** Stable wire markers (same as `i2pchat.presentation.reply_format`). */
const val DELETED_REPLY_AUTHOR = "[deleted]"
const val DELETED_REPLY_MARKER = "Original message deleted"
const val REPLY_SUMMARY_MAX = 120

/**
 * A message that starts with a reply quote, split the way
 * `i2pchat.presentation.reply_format.split_reply_quote` does on desktop.
 */
data class ReplyQuoteParts(
    /** Name from the `@who wrote:` header; empty when the header is missing. */
    val author: String,
    /** Quoted lines with the `>` marker removed, joined by newlines. */
    val quoted: String,
    /** The reply itself, without leading blank lines. */
    val answer: String,
) {
    val isDeleted: Boolean
        get() = author == DELETED_REPLY_AUTHOR || quoted.trim() == DELETED_REPLY_MARKER
}

/** Pending compose reply (not part of the editable draft text). */
data class PendingReply(
    val author: String,
    val summary: String,
    /** Full source body for jump/scrub matching of older multi-line quotes. */
    val sourceText: String = "",
    val messageId: String = "",
)

private val replyHeader = Regex("^@(.+) wrote:$")

/** One-line preview; attachment name wins when non-blank. */
fun summarizeForReply(text: String, attachmentName: String? = null): String {
    val raw = if (!attachmentName.isNullOrBlank()) {
        attachmentName.trim()
    } else {
        text.replace("\r\n", "\n").replace("\r", "\n").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
    return if (raw.length > REPLY_SUMMARY_MAX) {
        raw.take(REPLY_SUMMARY_MAX - 1).trimEnd() + "…"
    } else {
        raw
    }
}

/** Same quote block the desktop client builds (always one `>` line). */
fun formatReplyQuote(sender: String, text: String, attachmentName: String? = null): String {
    val who = sender.trim().ifBlank { "message" }
    val summary = summarizeForReply(text, attachmentName)
    val quoted = if (summary.isEmpty()) ">" else "> $summary"
    return "@$who wrote:\n$quoted\n\n"
}

fun formatDeletedReplyQuote(answer: String = ""): String {
    val body = formatReplyQuote(DELETED_REPLY_AUTHOR, DELETED_REPLY_MARKER)
    val ans = answer.trimStart('\n')
    return if (ans.isEmpty()) body else body + ans
}

/** Parses a leading `[@who wrote:]` line plus `> ...` lines; null when the text has no quote. */
fun splitReplyQuote(text: String): ReplyQuoteParts? {
    val lines = text.split("\n")
    if (lines.isEmpty()) return null
    val header = replyHeader.matchEntire(lines.first())
    val start = if (header != null) 1 else 0
    var end = start
    while (end < lines.size && lines[end].startsWith(">")) end++
    if (end == start) return null
    val quoted = lines.subList(start, end).map { line ->
        if (line.startsWith("> ")) line.substring(2) else line.substring(1)
    }
    val answer = lines.drop(end).dropWhile { it.isBlank() }
    return ReplyQuoteParts(
        author = header?.groupValues?.get(1)?.trim().orEmpty(),
        quoted = quoted.joinToString("\n"),
        answer = answer.joinToString("\n"),
    )
}

fun isDeletedReplyQuote(text: String): Boolean = splitReplyQuote(text)?.isDeleted == true

fun replaceQuoteWithDeleted(fullText: String): String {
    val layout = splitReplyQuote(fullText) ?: return fullText
    if (layout.isDeleted) return fullText
    return formatDeletedReplyQuote(layout.answer)
}

fun quoteMatchesSource(
    quotedText: String,
    sourceText: String,
    attachmentName: String? = null,
): Boolean {
    val q = quotedText.trimEnd('\n')
    val src = sourceText.trimEnd('\n')
    if (q.isEmpty()) return false
    if (q == src) return true
    val summary = summarizeForReply(src, attachmentName)
    return summary.isNotEmpty() && q == summary
}

fun scrubReplyTextIfMatches(
    messageText: String,
    deletedSourceText: String,
    attachmentName: String? = null,
): String? {
    val layout = splitReplyQuote(messageText) ?: return null
    if (layout.isDeleted) return null
    if (!quoteMatchesSource(layout.quoted, deletedSourceText, attachmentName)) return null
    return formatDeletedReplyQuote(layout.answer)
}

private fun MessageUi.isMine(): Boolean = kind == "out" || kind == "me"

/**
 * Index of the message a reply quotes: the closest one before [from] whose text
 * (or one-line summary / reply answer) equals the quoted text. `@me`
 * restricts the search to own messages and a peer name to the other side; if
 * that finds nothing the author is ignored, since desktop writes other names.
 * Returns -1 when the quoted message is not in the list.
 */
fun findQuotedMessage(messages: List<MessageUi>, from: Int, quote: ReplyQuoteParts): Int {
    if (quote.isDeleted) return -1
    fun matches(candidate: MessageUi): Boolean {
        val body = candidate.text.trimEnd('\n')
        if (body == quote.quoted || splitReplyQuote(body)?.answer == quote.quoted) return true
        val attName = candidate.attachment?.displayName?.ifBlank { null }
            ?: candidate.fileCaption.ifBlank { null }
        return quoteMatchesSource(quote.quoted, body, attName)
    }
    fun search(byAuthor: Boolean): Int {
        for (i in minOf(from, messages.size) - 1 downTo 0) {
            val candidate = messages[i]
            if (candidate.kind == "system" || candidate.kind == "info" ||
                candidate.kind == "success" || candidate.kind == "error"
            ) {
                continue
            }
            if (byAuthor && quote.author.isNotBlank()) {
                if (quote.author == REPLY_AUTHOR_ME) {
                    if (!candidate.isMine()) continue
                } else {
                    if (candidate.isMine()) continue
                    if (candidate.sender.isNotBlank() && candidate.sender.trim() != quote.author) continue
                }
            }
            if (matches(candidate)) return i
        }
        return -1
    }
    val exact = search(byAuthor = true)
    return if (exact >= 0) exact else search(byAuthor = false)
}
