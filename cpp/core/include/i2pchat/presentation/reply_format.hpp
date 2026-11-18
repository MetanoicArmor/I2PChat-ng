#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

/// Shared reply-quote formatting for compose insert and outbound messages.
///
/// Mirrors Python `i2pchat.presentation.reply_format` and adds full-message
/// compose plus parse for UI rendering of reply-shaped history text.
namespace i2pchat::presentation {

/// Markdown-style quote block ending with blank line (compose insert).
/// Format:
///   @Who wrote:\n
///   > line1\n
///   > line2\n\n
[[nodiscard]] std::string format_reply_quote(std::string_view sender, std::string_view text);

/// Full outbound reply message: quote + body (no trailing blank required after body).
[[nodiscard]] std::string format_reply_message(std::string_view sender,
                                               std::string_view quoted_text,
                                               std::string_view reply_body);

struct ParsedReply {
    /// Without leading @.
    std::string author;
    /// Without leading "> " / ">".
    std::vector<std::string> quoted_lines;
    /// Text after the quote block.
    std::string body;
    /// Quoted lines joined with `\n`.
    std::string quoted_plain;
};

/// Detect and parse a message that starts with "@… wrote:" and quoted lines.
/// Returns nullopt if not a reply-shaped message.
[[nodiscard]] std::optional<ParsedReply> parse_reply_message(std::string_view text);

}  // namespace i2pchat::presentation
