# Bundled call codecs (libopus / libvpx)

Native libraries for desktop call audio (Opus) and optional VP8 encode via system FFmpeg/PyAV.

Populate a platform folder with:

```bash
./scripts/stage_call_codecs.sh
```

Expected layout:

- `darwin-arm64/`, `darwin-x64/` — `libopus*.dylib`, optional `libvpx*.dylib`
- `linux-x86_64/`, `linux-aarch64/` — `libopus.so*`, optional `libvpx.so*`
- `win-x64/` — `opus.dll` / `libopus-0.dll`

Python packages (soft deps, not required for install):

- `opuslib` — wraps libopus
- `av` (PyAV) — VP8 encode/decode when FFmpeg was built with libvpx

Without these libraries the client falls back to PCM16 audio and JPEG video.
