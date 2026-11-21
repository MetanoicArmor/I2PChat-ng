from i2pchat.presentation.reply_format import (
    DELETED_REPLY_AUTHOR,
    DELETED_REPLY_MARKER,
    format_deleted_reply_quote,
    format_reply_quote,
    is_deleted_reply_quote,
    quote_matches_source,
    replace_quote_with_deleted,
    scrub_reply_text_if_matches,
    split_reply_quote,
    summarize_for_reply,
)


def test_summarize_collapses_whitespace_and_truncates() -> None:
    assert summarize_for_reply("a\n\nb  c") == "a b c"
    assert summarize_for_reply("x" * 200).endswith("…")
    assert len(summarize_for_reply("x" * 200)) == 120
    assert summarize_for_reply("ignored", attachment_name=" photo.png ") == "photo.png"


def test_format_reply_quote_is_one_line() -> None:
    s = format_reply_quote("Peer", "line1\nline2")
    assert "@Peer wrote:" in s
    assert "> line1 line2" in s
    assert s.count("\n> ") == 1 or s.startswith("@Peer wrote:\n> ")
    assert s.endswith("\n\n")


def test_format_reply_empty_sender_uses_fallback() -> None:
    s = format_reply_quote("", "hi")
    assert "@message wrote:" in s
    assert "> hi" in s


def test_format_reply_strips_trailing_newlines_from_body() -> None:
    s = format_reply_quote("Me", "a\n\n")
    assert s.endswith("\n\n")
    assert "> a" in s


def test_split_reply_quote_roundtrip_with_answer() -> None:
    msg = format_reply_quote("me", "line1\nline2") + "my answer"
    layout = split_reply_quote(msg)
    assert layout is not None
    assert layout.author == "me"
    assert layout.header_lines == 1
    assert layout.quote_lines == 1
    assert layout.quoted_text == "line1 line2"
    assert layout.display_text == "@me\nline1 line2\nmy answer"


def test_split_legacy_multiline_quote_still_parses() -> None:
    legacy = "@Peer wrote:\n> line1\n> line2\n\nanswer"
    layout = split_reply_quote(legacy)
    assert layout is not None
    assert layout.quote_lines == 2
    assert layout.quoted_text == "line1\nline2"
    assert layout.display_text.endswith("answer")


def test_split_reply_quote_without_header() -> None:
    layout = split_reply_quote("> a\n>\n> b\nanswer")
    assert layout is not None
    assert layout.header_lines == 0
    assert layout.author == ""
    assert layout.quoted_text == "a\n\nb"
    assert layout.display_text == "a\n\nb\nanswer"


def test_split_reply_quote_only_leading_lines_are_quote() -> None:
    layout = split_reply_quote("> q\nanswer\n> not a quote")
    assert layout is not None
    assert layout.quote_lines == 1
    assert layout.display_text == "q\nanswer\n> not a quote"


def test_split_reply_quote_plain_message_is_none() -> None:
    assert split_reply_quote("hello\n> later") is None
    assert split_reply_quote("@Peer wrote:\nno quote") is None
    assert split_reply_quote("") is None


def test_deleted_placeholder_roundtrip() -> None:
    msg = format_reply_quote("me", "secret\nlines") + "ok"
    scrubbed = replace_quote_with_deleted(msg)
    assert is_deleted_reply_quote(scrubbed)
    layout = split_reply_quote(scrubbed)
    assert layout is not None
    assert layout.author == DELETED_REPLY_AUTHOR
    assert layout.quoted_text == DELETED_REPLY_MARKER
    assert layout.answer == "ok"
    assert format_deleted_reply_quote("ok") == scrubbed


def test_quote_matches_and_scrub() -> None:
    source = "hello\nworld"
    reply = format_reply_quote("peer", source) + "thanks"
    assert quote_matches_source(split_reply_quote(reply).quoted_text, source)
    assert scrub_reply_text_if_matches(reply, source) == format_deleted_reply_quote("thanks")
    assert scrub_reply_text_if_matches(reply, "other") is None
    assert scrub_reply_text_if_matches(
        format_reply_quote("peer", "x", attachment_name="a.bin") + "y",
        "x",
        attachment_name="a.bin",
    ) == format_deleted_reply_quote("y")


def test_scrub_history_texts_to_deleted() -> None:
    from i2pchat.presentation.reply_format import scrub_history_texts_to_deleted

    source = "orig"
    texts = [
        "plain",
        format_reply_quote("peer", source) + "a",
        format_reply_quote("peer", "other") + "b",
    ]
    out = scrub_history_texts_to_deleted(texts, source)
    assert out[0] == "plain"
    assert out[1] == format_deleted_reply_quote("a")
    assert out[2] == texts[2]
