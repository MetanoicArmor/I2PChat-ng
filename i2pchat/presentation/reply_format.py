"""
Build a quoted reply block for the compose field (issue: message context actions).

Outgoing quotes are always a single ``>`` line (preview). History may still
contain older multi-line quotes; parsers keep accepting those.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional

_HEADER_RE = re.compile(r"^@(.+) wrote:$")

# Stable wire/history markers (not localized) so scrubbing works across locales.
DELETED_REPLY_AUTHOR = "[deleted]"
DELETED_REPLY_MARKER = "Original message deleted"
REPLY_SUMMARY_MAX = 120


def summarize_for_reply(
    text: str,
    *,
    attachment_name: Optional[str] = None,
) -> str:
    """One-line preview for a reply quote (attachment name wins when given)."""
    if attachment_name is not None and str(attachment_name).strip():
        raw = str(attachment_name).strip()
    else:
        raw = (text or "").replace("\r\n", "\n").replace("\r", "\n")
        raw = " ".join(raw.split())
    if len(raw) > REPLY_SUMMARY_MAX:
        return raw[: REPLY_SUMMARY_MAX - 1].rstrip() + "…"
    return raw


def format_reply_quote(
    sender: str,
    text: str,
    *,
    attachment_name: Optional[str] = None,
) -> str:
    """Return a one-line markdown-style quote; ends with a blank line for the answer."""
    who = (sender or "").strip() or "message"
    summary = summarize_for_reply(text, attachment_name=attachment_name)
    quoted = f"> {summary}" if summary else ">"
    return f"@{who} wrote:\n{quoted}\n\n"


def format_deleted_reply_quote(answer: str = "") -> str:
    """Rewrite a reply body so the quote no longer retains author/content."""
    body = format_reply_quote(DELETED_REPLY_AUTHOR, DELETED_REPLY_MARKER)
    ans = (answer or "").lstrip("\n")
    return body + ans if ans else body


@dataclass(frozen=True)
class ReplyQuoteLayout:
    """Display form of a message that starts with a reply quote.

    ``display_text`` lines are: optional ``@author`` line, quoted lines (marker stripped),
    then the answer. Line indexes map 1:1 to text blocks in a rendered document.
    """

    display_text: str
    author: str
    header_lines: int  # 0 or 1
    quote_lines: int
    quoted_text: str  # original text of the quoted message (for lookup)
    answer: str = ""


def split_reply_quote(text: str) -> Optional[ReplyQuoteLayout]:
    """Parse leading ``[@who wrote:]`` + ``> ...`` lines; None if the message has no quote."""
    lines = (text or "").split("\n")
    header = _HEADER_RE.match(lines[0]) if lines else None
    start = 1 if header else 0
    end = start
    while end < len(lines) and lines[end].startswith(">"):
        end += 1
    if end == start:
        return None
    quoted = [ln[2:] if ln.startswith("> ") else ln[1:] for ln in lines[start:end]]
    answer_lines = lines[end:]
    while answer_lines and not answer_lines[0].strip():
        answer_lines.pop(0)
    author = header.group(1).strip() if header else ""
    answer = "\n".join(answer_lines)
    display = ([f"@{author}"] if header else []) + quoted + answer_lines
    return ReplyQuoteLayout(
        display_text="\n".join(display),
        author=author,
        header_lines=start,
        quote_lines=len(quoted),
        quoted_text="\n".join(quoted),
        answer=answer,
    )


def is_deleted_reply_quote(text: str) -> bool:
    layout = split_reply_quote(text)
    if layout is None:
        return False
    if layout.author == DELETED_REPLY_AUTHOR:
        return True
    return layout.quoted_text.strip() == DELETED_REPLY_MARKER


def replace_quote_with_deleted(full_text: str) -> str:
    """Keep the answer; replace the quote block with the deleted placeholder."""
    layout = split_reply_quote(full_text)
    if layout is None:
        return full_text
    if is_deleted_reply_quote(full_text):
        return full_text
    return format_deleted_reply_quote(layout.answer)


def quote_matches_source(
    quoted_text: str,
    source_text: str,
    *,
    attachment_name: Optional[str] = None,
) -> bool:
    """True if ``quoted_text`` refers to ``source_text`` (full or one-line summary)."""
    q = (quoted_text or "").rstrip("\n")
    src = (source_text or "").rstrip("\n")
    if not q:
        return False
    if q == src:
        return True
    summary = summarize_for_reply(src, attachment_name=attachment_name)
    return bool(summary) and q == summary


def scrub_reply_text_if_matches(
    message_text: str,
    deleted_source_text: str,
    *,
    attachment_name: Optional[str] = None,
) -> Optional[str]:
    """If ``message_text`` is a reply quoting the deleted source, return scrubbed body."""
    layout = split_reply_quote(message_text)
    if layout is None or is_deleted_reply_quote(message_text):
        return None
    if not quote_matches_source(
        layout.quoted_text,
        deleted_source_text,
        attachment_name=attachment_name,
    ):
        return None
    return format_deleted_reply_quote(layout.answer)


def scrub_history_texts_to_deleted(
    texts: list[str],
    deleted_source_text: str,
    *,
    attachment_name: Optional[str] = None,
) -> list[str]:
    """Return a copy of ``texts`` with matching reply quotes scrubbed."""
    out: list[str] = []
    for text in texts:
        scrubbed = scrub_reply_text_if_matches(
            text,
            deleted_source_text,
            attachment_name=attachment_name,
        )
        out.append(scrubbed if scrubbed is not None else text)
    return out
