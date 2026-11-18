package org.i2pchat.android

/** Author used in the quote header when replying to one's own message. */
const val REPLY_AUTHOR_ME = "me"

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
)

private val replyHeader = Regex("^@(.+) wrote:$")

/** Parses a leading `[@who wrote:]` line plus `> ...` lines; null when the text has no quote. */
fun splitReplyQuote(text: String): ReplyQuoteParts? {
    val lines = text.split("\n")
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

private fun MessageUi.isMine(): Boolean = kind == "out" || kind == "me"

/**
 * Index of the message a reply quotes: the closest one before [from] whose text
 * (or, for a reply itself, whose own answer) equals the quoted text. `@me`
 * restricts the search to own messages and a peer name to the other side; if
 * that finds nothing the author is ignored, since desktop writes other names.
 * Returns -1 when the quoted message is not in the list.
 */
fun findQuotedMessage(messages: List<MessageUi>, from: Int, quote: ReplyQuoteParts): Int {
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
            val body = candidate.text.trimEnd('\n')
            if (body == quote.quoted || splitReplyQuote(body)?.answer == quote.quoted) return i
        }
        return -1
    }
    val exact = search(byAuthor = true)
    return if (exact >= 0) exact else search(byAuthor = false)
}
