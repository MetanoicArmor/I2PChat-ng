#include "i2pchat/presentation/reply_format.hpp"

#include <cctype>

namespace i2pchat::presentation {
namespace {

std::string_view strip_trailing_newlines(std::string_view text) {
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) {
        text.remove_suffix(1);
    }
    return text;
}

std::string_view strip_whitespace(std::string_view text) {
    while (!text.empty() &&
           std::isspace(static_cast<unsigned char>(text.front()))) {
        text.remove_prefix(1);
    }
    while (!text.empty() &&
           std::isspace(static_cast<unsigned char>(text.back()))) {
        text.remove_suffix(1);
    }
    return text;
}

/// Split on `\n`; keep empty segments. Trailing `\r` on each segment is dropped.
std::vector<std::string_view> split_lines(std::string_view text) {
    std::vector<std::string_view> lines;
    std::size_t start = 0;
    while (start <= text.size()) {
        const std::size_t nl = text.find('\n', start);
        if (nl == std::string_view::npos) {
            std::string_view line = text.substr(start);
            if (!line.empty() && line.back() == '\r') {
                line.remove_suffix(1);
            }
            lines.push_back(line);
            break;
        }
        std::string_view line = text.substr(start, nl - start);
        if (!line.empty() && line.back() == '\r') {
            line.remove_suffix(1);
        }
        lines.push_back(line);
        start = nl + 1;
        if (start == text.size()) {
            // Trailing newline yields a final empty segment, matching Python
            // `split("\n")` only when content remains; after rstrip we rarely
            // hit this, but keep behaviour predictable for parsers.
            lines.emplace_back();
            break;
        }
    }
    return lines;
}

std::string quote_line(std::string_view line) {
    if (line.empty()) {
        return ">";
    }
    std::string out = "> ";
    out.append(line);
    return out;
}

bool starts_with(std::string_view text, std::string_view prefix) {
    return text.size() >= prefix.size() &&
           text.compare(0, prefix.size(), prefix) == 0;
}

/// Header `@X wrote:` — X is everything between `@` and ` wrote:`.
std::optional<std::string> parse_wrote_header(std::string_view line) {
    constexpr std::string_view kSuffix = " wrote:";
    if (!starts_with(line, "@") || line.size() < 1 + kSuffix.size()) {
        return std::nullopt;
    }
    if (!starts_with(line.substr(line.size() - kSuffix.size()), kSuffix)) {
        return std::nullopt;
    }
    const std::string_view who = line.substr(1, line.size() - 1 - kSuffix.size());
    if (who.empty()) {
        return std::nullopt;
    }
    return std::string(who);
}

std::string strip_quote_prefix(std::string_view line) {
    if (!starts_with(line, ">")) {
        return std::string(line);
    }
    line.remove_prefix(1);
    if (starts_with(line, " ")) {
        line.remove_prefix(1);
    }
    return std::string(line);
}

}  // namespace

std::string format_reply_quote(std::string_view sender, std::string_view text) {
    const std::string_view who_raw = strip_whitespace(sender);
    const std::string who = who_raw.empty() ? std::string("message") : std::string(who_raw);
    const std::string_view body = strip_trailing_newlines(text);
    const auto lines = split_lines(body);

    std::string out;
    out.reserve(who.size() + body.size() + lines.size() * 3 + 16);
    out.push_back('@');
    out += who;
    out += " wrote:\n";
    for (std::size_t i = 0; i < lines.size(); ++i) {
        if (i != 0) {
            out.push_back('\n');
        }
        out += quote_line(lines[i]);
    }
    out += "\n\n";
    return out;
}

std::string format_reply_message(std::string_view sender, std::string_view quoted_text,
                                 std::string_view reply_body) {
    return format_reply_quote(sender, quoted_text) + std::string(reply_body);
}

std::optional<ParsedReply> parse_reply_message(std::string_view text) {
    if (text.empty()) {
        return std::nullopt;
    }

    const auto lines = split_lines(text);
    if (lines.empty()) {
        return std::nullopt;
    }

    const auto author = parse_wrote_header(lines[0]);
    if (!author) {
        return std::nullopt;
    }

    ParsedReply parsed;
    parsed.author = *author;

    std::size_t i = 1;
    for (; i < lines.size(); ++i) {
        if (!starts_with(lines[i], ">")) {
            break;
        }
        parsed.quoted_lines.push_back(strip_quote_prefix(lines[i]));
    }

    // A blank line may separate the quote block from the body; skip one empty
    // line so the body does not start with a spurious leading newline.
    if (i < lines.size() && lines[i].empty()) {
        ++i;
    }

    if (i < lines.size()) {
        std::string body;
        for (std::size_t j = i; j < lines.size(); ++j) {
            if (j != i) {
                body.push_back('\n');
            }
            body.append(lines[j]);
        }
        parsed.body = std::move(body);
    }

    for (std::size_t q = 0; q < parsed.quoted_lines.size(); ++q) {
        if (q != 0) {
            parsed.quoted_plain.push_back('\n');
        }
        parsed.quoted_plain += parsed.quoted_lines[q];
    }

    return parsed;
}

}  // namespace i2pchat::presentation
