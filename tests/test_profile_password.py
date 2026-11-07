"""I2PK v2 seals a profile with a password and still reads a v1 file."""

from __future__ import annotations

import pytest

from i2pchat.storage.profile_dat import (
    ProfilePasswordError,
    profile_dat_needs_password,
    read_profile_dat_file,
    write_encrypted_profile_dat,
)


def test_password_seals_the_profile_and_a_wrong_one_does_not_open(tmp_path):
    path = tmp_path / "ada.dat"
    write_encrypted_profile_dat(
        str(path),
        "private-key-line",
        profile="ada",
        profile_data_dir=str(tmp_path),
        password="correct horse",
    )
    assert profile_dat_needs_password(str(path))
    key, peer, was_plain = read_profile_dat_file(
        str(path),
        profile="ada",
        profile_data_dir=str(tmp_path),
        password="correct horse",
        create_wrap_key=False,
    )
    assert key == "private-key-line"
    assert peer is None
    assert was_plain is False
    with pytest.raises(ProfilePasswordError):
        read_profile_dat_file(
            str(path),
            profile="ada",
            profile_data_dir=str(tmp_path),
            password="nope",
            create_wrap_key=False,
        )
    with pytest.raises(ProfilePasswordError):
        read_profile_dat_file(
            str(path),
            profile="ada",
            profile_data_dir=str(tmp_path),
            create_wrap_key=False,
        )


def test_removing_the_password_writes_a_file_that_opens_without_one(tmp_path):
    path = tmp_path / "ada.dat"
    write_encrypted_profile_dat(
        str(path),
        "private-key-line",
        profile="ada",
        profile_data_dir=str(tmp_path),
        password="secret",
    )
    write_encrypted_profile_dat(
        str(path),
        "private-key-line",
        profile="ada",
        profile_data_dir=str(tmp_path),
        password="",
    )
    assert not profile_dat_needs_password(str(path))
    key, _peer, _plain = read_profile_dat_file(
        str(path),
        profile="ada",
        profile_data_dir=str(tmp_path),
        create_wrap_key=False,
    )
    assert key == "private-key-line"
