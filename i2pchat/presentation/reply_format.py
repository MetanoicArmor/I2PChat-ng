"""
Build a quoted reply block for the compose field (issue: message context actions).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional

_HEADER_RE = re.compile(r"^@(.+) wrote:$")


def format_reply_quote(sender: str, text: str) -> str:
    """Return markdown-style quoted lines with a short header; ends with blank line for typing."""
    who = (sender or "").strip() or "message"
    body = (text or "").rstrip("\n")
    lines = body.split("\n")
    quoted = "\n".join(f"> {line}" if line else ">" for line in lines)
    return f"@{who} wrote:\n{quoted}\n\n"


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
    answer = lines[end:]
    while answer and not answer[0].strip():
        answer.pop(0)
    author = header.group(1).strip() if header else ""
    display = ([f"@{author}"] if header else []) + quoted + answer
    return ReplyQuoteLayout(
        display_text="\n".join(display),
        author=author,
        header_lines=start,
        quote_lines=len(quoted),
        quoted_text="\n".join(quoted),
    )
