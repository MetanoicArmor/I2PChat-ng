#include <catch2/catch_test_macros.hpp>

#include <string>

#include "i2pchat/presentation/reply_format.hpp"

using namespace i2pchat::presentation;

TEST_CASE("format_reply_quote multiline") {
    const std::string s = format_reply_quote("Peer", "line1\nline2");
    CHECK(s.find("@Peer wrote:") != std::string::npos);
    CHECK(s.find("> line1") != std::string::npos);
    CHECK(s.find("> line2") != std::string::npos);
    REQUIRE(s.size() >= 2);
    CHECK(s.substr(s.size() - 2) == "\n\n");
    CHECK(s == "@Peer wrote:\n> line1\n> line2\n\n");
}

TEST_CASE("format_reply_quote empty sender uses fallback") {
    const std::string s = format_reply_quote("", "hi");
    CHECK(s.find("@message wrote:") != std::string::npos);
    CHECK(s.find("> hi") != std::string::npos);
    CHECK(s == "@message wrote:\n> hi\n\n");
}

TEST_CASE("format_reply_quote strips trailing newlines from body") {
    const std::string s = format_reply_quote("Me", "a\n\n");
    REQUIRE(s.size() >= 2);
    CHECK(s.substr(s.size() - 2) == "\n\n");
    CHECK(s.find("> a") != std::string::npos);
    // No extra blank quoted lines from trailing \n (Python: "> " not in tail).
    const auto pos = s.find("> a");
    REQUIRE(pos != std::string::npos);
    const std::string after = s.substr(pos + 3);
    CHECK(after.find("> ") == std::string::npos);
    CHECK(s == "@Me wrote:\n> a\n\n");
}

TEST_CASE("format_reply_quote whitespace sender and blank lines") {
    CHECK(format_reply_quote("  \t ", "x") == "@message wrote:\n> x\n\n");
    CHECK(format_reply_quote("Alice", "a\n\nb") == "@Alice wrote:\n> a\n>\n> b\n\n");
    CHECK(format_reply_quote("Bob", "") == "@Bob wrote:\n>\n\n");
}

TEST_CASE("format_reply_message appends body after quote blank line") {
    const std::string msg = format_reply_message("Peer", "quoted", "thanks");
    CHECK(msg == "@Peer wrote:\n> quoted\n\nthanks");
    CHECK(msg.find("\n\nthanks") != std::string::npos);
}

TEST_CASE("parse_reply_message round-trip") {
    const std::string msg = format_reply_message("Alice", "hello\nworld", "ok");
    const auto parsed = parse_reply_message(msg);
    REQUIRE(parsed.has_value());
    CHECK(parsed->author == "Alice");
    REQUIRE(parsed->quoted_lines.size() == 2);
    CHECK(parsed->quoted_lines[0] == "hello");
    CHECK(parsed->quoted_lines[1] == "world");
    CHECK(parsed->quoted_plain == "hello\nworld");
    CHECK(parsed->body == "ok");
}

TEST_CASE("parse_reply_message accepts me and empty quote lines") {
    const auto parsed = parse_reply_message("@me wrote:\n>\n\nreply body\nmore");
    REQUIRE(parsed.has_value());
    CHECK(parsed->author == "me");
    REQUIRE(parsed->quoted_lines.size() == 1);
    CHECK(parsed->quoted_lines[0].empty());
    CHECK(parsed->quoted_plain.empty());
    CHECK(parsed->body == "reply body\nmore");
}

TEST_CASE("parse_reply_message rejects non-reply text") {
    CHECK_FALSE(parse_reply_message("hello").has_value());
    CHECK_FALSE(parse_reply_message("@nobody").has_value());
    CHECK_FALSE(parse_reply_message("@ wrote:\n> x").has_value());
    CHECK_FALSE(parse_reply_message("").has_value());
}

TEST_CASE("parse_reply_message stops quote at first non-prefix line") {
    const auto parsed = parse_reply_message("@Peer wrote:\n> one\n> two\nnot quoted\nstill body");
    REQUIRE(parsed.has_value());
    CHECK(parsed->author == "Peer");
    REQUIRE(parsed->quoted_lines.size() == 2);
    CHECK(parsed->quoted_lines[0] == "one");
    CHECK(parsed->quoted_lines[1] == "two");
    CHECK(parsed->quoted_plain == "one\ntwo");
    CHECK(parsed->body == "not quoted\nstill body");
}
