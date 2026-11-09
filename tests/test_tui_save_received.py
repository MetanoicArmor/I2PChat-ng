"""Saving a received file or picture by hand in the TUI (/savefile and F3)."""

from __future__ import annotations

import asyncio
from collections import deque
from pathlib import Path
from unittest.mock import PropertyMock, patch

import pytest

pytest.importorskip("textual.app", exc_type=ImportError)

from textual.app import App  # noqa: E402
from textual.widgets import Button, DataTable, Input  # noqa: E402

from i2pchat.core.i2p_chat_core import FileTransferInfo  # noqa: E402
from i2pchat.gui.chat_python import (  # noqa: E402
    I2PChat,
    TransferEventRef,
    TuiMediaScreen,
    TuiSavePathScreen,
)


def _app() -> tuple[I2PChat, list[tuple[str, str]]]:
    app = object.__new__(I2PChat)
    posted: list[tuple[str, str]] = []
    app._recent_transfers = deque(maxlen=80)
    app._transfer_progress_buckets = {}
    app.post = lambda kind, text, **_kw: posted.append((kind, text))  # type: ignore[method-assign]
    return app, posted


def _received(tmp_path: Path, name: str = "photo.png", data: bytes = b"picture") -> str:
    cache = tmp_path / "incoming-images"
    cache.mkdir(exist_ok=True)
    path = cache / name
    path.write_bytes(data)
    return str(path)


def test_save_copy_to_a_file_path_and_to_a_folder(tmp_path: Path) -> None:
    app, posted = _app()
    source = _received(tmp_path)
    keep = tmp_path / "keep"
    keep.mkdir()

    assert asyncio.run(I2PChat._save_received_copy(app, source, str(tmp_path / "new" / "mine.png")))
    assert (tmp_path / "new" / "mine.png").read_bytes() == b"picture"

    assert asyncio.run(I2PChat._save_received_copy(app, source, str(keep)))
    assert (keep / "photo.png").read_bytes() == b"picture"

    assert [kind for kind, _ in posted] == ["success", "success"]
    assert Path(source).exists()


def test_save_copy_never_overwrites_an_existing_file(tmp_path: Path) -> None:
    app, posted = _app()
    source = _received(tmp_path)
    keep = tmp_path / "keep"
    keep.mkdir()
    (keep / "photo.png").write_bytes(b"older file")

    assert asyncio.run(I2PChat._save_received_copy(app, source, str(keep)))

    assert (keep / "photo.png").read_bytes() == b"older file"
    assert (keep / "photo (1).png").read_bytes() == b"picture"
    assert posted[-1][0] == "success" and "photo (1).png" in posted[-1][1]


def test_save_copy_reports_a_source_that_is_gone(tmp_path: Path) -> None:
    app, posted = _app()
    assert not asyncio.run(
        I2PChat._save_received_copy(app, str(tmp_path / "gone.png"), str(tmp_path / "out.png"))
    )
    assert [kind for kind, _ in posted] == ["error"]
    assert not (tmp_path / "out.png").exists()


def test_last_received_path_skips_sent_items_and_deleted_files(tmp_path: Path) -> None:
    app, _posted = _app()
    older = _received(tmp_path, "older.png")
    deleted = _received(tmp_path, "deleted.png")
    Path(deleted).unlink()

    I2PChat._record_transfer_event(app, "image", older, "received inline preview", path=older)
    I2PChat._record_transfer_event(app, "image", deleted, "received inline preview", path=deleted)
    I2PChat._record_transfer_event(app, "file", "/home/me/report.pdf", "queued for sending")

    assert I2PChat._last_received_path(app) == older
    assert app._recent_transfers[0].filename == "report.pdf"


def test_received_picture_and_finished_file_record_their_paths(tmp_path: Path) -> None:
    app, _posted = _app()
    app._peer_display_name = lambda _peer: "Peer"  # type: ignore[method-assign]
    app._current_target_peer = lambda: None  # type: ignore[method-assign]
    app._render_inline_image = lambda *_a, **_k: None  # type: ignore[method-assign]
    picture = _received(tmp_path)
    document = _received(tmp_path, "notes.txt", b"12345")

    I2PChat.handle_inline_image_received(app, "/home/me/mine.png", True, "mine.png")
    assert I2PChat._last_received_path(app) is None

    I2PChat.handle_inline_image_received(app, picture, False)
    assert I2PChat._last_received_path(app) == picture

    I2PChat.handle_file_event(app, FileTransferInfo(filename=document, size=5, received=2))
    assert I2PChat._last_received_path(app) == picture
    I2PChat.handle_file_event(app, FileTransferInfo(filename=document, size=5, received=5))
    assert I2PChat._last_received_path(app) == document

    # An outgoing file is not something to "save".
    I2PChat.handle_file_event(
        app, FileTransferInfo(filename=picture, size=7, received=7, is_sending=True)
    )
    assert app._recent_transfers[0].path == ""


def _command_app(tmp_path: Path) -> tuple[I2PChat, list[tuple[str, str]]]:
    app, posted = _app()
    app._compose_draft_active_key = None
    app._set_compose_text = lambda *_a, **_k: None  # type: ignore[method-assign]
    app._flush_compose_drafts_to_disk = lambda: None  # type: ignore[method-assign]
    app._refresh_status_bar = lambda: None  # type: ignore[method-assign]
    return app, posted


def test_savefile_command_copies_the_last_received_item(tmp_path: Path) -> None:
    app, posted = _command_app(tmp_path)
    source = _received(tmp_path)
    I2PChat._record_transfer_event(app, "image", source, "received inline preview", path=source)
    target = tmp_path / "saved.png"

    asyncio.run(I2PChat._execute_command(app, f'/savefile "{target}"'))
    assert target.read_bytes() == b"picture"
    assert posted[-1][0] == "success"

    asyncio.run(I2PChat._execute_command(app, f'/save-file "{tmp_path}"'))
    assert (tmp_path / "photo.png").read_bytes() == b"picture"


def test_savefile_command_without_anything_received(tmp_path: Path) -> None:
    app, posted = _command_app(tmp_path)
    asyncio.run(I2PChat._execute_command(app, f'/savefile "{tmp_path / "x.png"}"'))
    asyncio.run(I2PChat._execute_command(app, "/savefile"))
    assert [kind for kind, _ in posted] == ["error", "error"]
    assert not (tmp_path / "x.png").exists()


class _Shell(App[None]):
    """Bare app: the media screen talks to a stand-in host, not to a real core."""


def test_media_screen_saves_the_selected_received_item(tmp_path: Path) -> None:
    picture = _received(tmp_path)
    older = _received(tmp_path, "older.png", b"older")
    target_dir = tmp_path / "keep"
    target_dir.mkdir()

    async def _run() -> None:
        shell = _Shell()
        host, posted = _app()
        workers: list[object] = []
        host._media_summary_text = lambda: "summary"  # type: ignore[method-assign]
        host._current_target_peer = lambda: None  # type: ignore[method-assign]
        host.run_worker = lambda work, **_kw: workers.append(work)  # type: ignore[method-assign]
        host.push_screen = shell.push_screen  # type: ignore[method-assign]
        I2PChat._record_transfer_event(host, "image", older, "received inline preview", path=older)
        I2PChat._record_transfer_event(host, "file", "/home/me/report.pdf", "queued for sending")
        I2PChat._record_transfer_event(host, "image", picture, "received inline preview", path=picture)

        with patch.object(TuiMediaScreen, "host", new_callable=PropertyMock, return_value=host):
            async with shell.run_test(size=(120, 50)) as pilot:
                screen = TuiMediaScreen()
                await shell.push_screen(screen)
                await pilot.pause()

                save_button = screen.query_one("#media_save_as", Button)
                assert save_button.disabled is False
                table = screen.query_one("#media_table", DataTable)
                assert table.row_count == 3

                # The periodic rebuild keeps the cursor on the row the user chose.
                table.move_cursor(row=2, animate=False)
                screen._refresh()
                assert table.cursor_row == 2
                assert screen._save_source() == older

                # A row that is not a received item falls back to the newest one.
                table.move_cursor(row=1, animate=False)
                assert screen._save_source() == picture
                table.move_cursor(row=2, animate=False)

                await pilot.click("#media_save_as")
                await pilot.pause()
                assert isinstance(shell.screen, TuiSavePathScreen)
                shell.screen.query_one("#save_path_input", Input).value = str(target_dir)
                await pilot.click("#save_path_ok")
                await pilot.pause()

                assert len(workers) == 1
                assert await workers[0] is True  # type: ignore[misc]

        assert (target_dir / "older.png").read_bytes() == b"older"
        assert posted[-1][0] == "success"

    asyncio.run(_run())


def test_media_screen_disables_save_when_nothing_was_received() -> None:
    async def _run() -> None:
        shell = _Shell()
        host, _posted = _app()
        host._media_summary_text = lambda: "summary"  # type: ignore[method-assign]
        host._current_target_peer = lambda: None  # type: ignore[method-assign]
        I2PChat._record_transfer_event(host, "file", "/home/me/report.pdf", "queued for sending")

        with patch.object(TuiMediaScreen, "host", new_callable=PropertyMock, return_value=host):
            async with shell.run_test(size=(120, 50)) as pilot:
                screen = TuiMediaScreen()
                await shell.push_screen(screen)
                await pilot.pause()
                assert screen.query_one("#media_save_as", Button).disabled is True

    asyncio.run(_run())


def test_transfer_event_path_defaults_to_empty() -> None:
    assert TransferEventRef(kind="file", filename="a", detail="d", timestamp="t").path == ""
