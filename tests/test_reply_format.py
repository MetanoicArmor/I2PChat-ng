from i2pchat.presentation.reply_format import format_reply_quote, split_reply_quote


def test_format_reply_quote_multiline() -> None:
    s = format_reply_quote("Peer", "line1\nline2")
    assert "@Peer wrote:" in s
    assert "> line1" in s
    assert "> line2" in s
    assert s.endswith("\n\n")


def test_format_reply_empty_sender_uses_fallback() -> None:
    s = format_reply_quote("", "hi")
    assert "@message wrote:" in s
    assert "> hi" in s


def test_format_reply_strips_trailing_newlines_from_body() -> None:
    s = format_reply_quote("Me", "a\n\n")
    assert s.endswith("\n\n")
    assert "> a" in s
    assert "> " not in s.split("> a")[1]  # no extra blank quoted lines from trailing \n


def test_split_reply_quote_roundtrip_with_answer() -> None:
    msg = format_reply_quote("me", "line1\nline2") + "my answer"
    layout = split_reply_quote(msg)
    assert layout is not None
    assert layout.author == "me"
    assert layout.header_lines == 1
    assert layout.quote_lines == 2
    assert layout.quoted_text == "line1\nline2"
    assert layout.display_text == "@me\nline1\nline2\nmy answer"


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
