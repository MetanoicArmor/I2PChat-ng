package org.i2pchat.android

data class ParsedReply(
    val author: String,
    val quote: String,
    val body: String,
)

/** Mirror of C++/Python `format_reply_quote` / `parse_reply_message`. */
object ReplyFormat {
    fun formatQuote(sender: String, text: String): String {
        val who = sender.trim().ifBlank { "message" }
        val body = text.trimEnd('\n')
        val quoted = body.split("\n").joinToString("\n") { line ->
            if (line.isEmpty()) ">" else "> $line"
        }
        return "@$who wrote:\n$quoted\n\n"
    }

    fun formatMessage(sender: String, quotedText: String, replyBody: String): String {
        return formatQuote(sender, quotedText) + replyBody
    }

    fun parse(text: String): ParsedReply? {
        val lines = text.split("\n")
        if (lines.isEmpty()) return null
        val header = Regex("^@(.+) wrote:\\s*$").matchEntire(lines[0]) ?: return null
        val quoted = ArrayList<String>()
        var index = 1
        while (index < lines.size) {
            val line = lines[index]
            if (!line.startsWith(">")) break
            quoted += when {
                line == ">" -> ""
                line.startsWith("> ") -> line.removePrefix("> ")
                else -> line.removePrefix(">")
            }
            index += 1
        }
        while (index < lines.size && lines[index].isBlank()) {
            index += 1
        }
        val body = if (index >= lines.size) "" else lines.subList(index, lines.size).joinToString("\n")
        return ParsedReply(
            author = header.groupValues[1],
            quote = quoted.joinToString("\n"),
            body = body,
        )
    }
}
