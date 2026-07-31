from __future__ import annotations

import os
import shutil
import struct
import subprocess
from pathlib import Path
from typing import Callable

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
TS_PACKET_BYTES = 188
MAX_PNG_PREFIX_BYTES = 1024 * 1024
MAX_TS_PADDING_BYTES = TS_PACKET_BYTES * 4
MIN_FREE_MARGIN_BYTES = 64 * 1024 * 1024
REMUX_TIMEOUT_SECONDS = 30 * 60


def png_transport_stream_prefix(path: str | Path) -> int | None:
    source = Path(path)
    try:
        with source.open("rb") as handle:
            if handle.read(len(PNG_SIGNATURE)) != PNG_SIGNATURE:
                return None
            position = len(PNG_SIGNATURE)
            while position < MAX_PNG_PREFIX_BYTES:
                raw_length = handle.read(4)
                chunk_type = handle.read(4)
                if len(raw_length) != 4 or len(chunk_type) != 4:
                    return None
                length = struct.unpack(">I", raw_length)[0]
                position += 8
                if length > MAX_PNG_PREFIX_BYTES or position + length + 4 > MAX_PNG_PREFIX_BYTES:
                    return None
                handle.seek(length + 4, os.SEEK_CUR)
                position += length + 4
                if chunk_type == b"IEND":
                    break
            else:
                return None

            # Some streaming servers align MPEG-TS to a 188-byte boundary and
            # pad the gap after the decoy PNG with 0xff bytes. Locate the first
            # candidate containing three consecutive TS sync bytes instead of
            # requiring the payload to start immediately after IEND.
            scan_end = min(position + MAX_TS_PADDING_BYTES, source.stat().st_size)
            for candidate in range(position, scan_end):
                valid = True
                for packet in range(3):
                    handle.seek(candidate + packet * TS_PACKET_BYTES)
                    if handle.read(1) != b"\x47":
                        valid = False
                        break
                if valid:
                    return candidate
            return None
    except OSError:
        return None


def _remux_command(
    source: Path,
    temporary: Path,
    prefix: int,
    ffmpeg_bin: str,
) -> list[str]:
    command: list[str] = []
    if Path("/usr/bin/ionice").exists():
        command.extend(("/usr/bin/ionice", "-c", "3"))
    if Path("/usr/bin/nice").exists():
        command.extend(("/usr/bin/nice", "-n", "15"))
    command.extend(
        (
            ffmpeg_bin,
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-threads",
            "1",
            "-skip_initial_bytes",
            str(prefix),
            "-f",
            "mpegts",
            "-i",
            str(source),
            "-map",
            "0:v:0",
            "-map",
            "0:a:0?",
            "-c",
            "copy",
            "-movflags",
            "+faststart",
            "-f",
            "mp4",
            "-y",
            str(temporary),
        )
    )
    return command


def normalize_downloaded_media(
    path: str | Path,
    *,
    ffmpeg_bin: str = "/usr/bin/ffmpeg",
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> str:
    source = Path(path)
    prefix = png_transport_stream_prefix(source)
    if prefix is None:
        return str(source)

    payload_bytes = source.stat().st_size - prefix
    free_bytes = shutil.disk_usage(source.parent).free
    if free_bytes < payload_bytes + MIN_FREE_MARGIN_BYTES:
        raise RuntimeError(
            "Khong du dung luong de remux MPEG-TS sang MP4: "
            f"can them {payload_bytes + MIN_FREE_MARGIN_BYTES} byte"
        )

    destination = source.with_suffix(".mp4")
    if destination != source and destination.exists():
        destination = source.with_name(source.stem + ".normalized.mp4")
    temporary = source.with_name(f".{destination.stem}.{os.getpid()}.remux.tmp.mp4")

    try:
        completed = runner(
            _remux_command(source, temporary, prefix, ffmpeg_bin),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
            timeout=REMUX_TIMEOUT_SECONDS,
            check=False,
        )
        if completed.returncode != 0:
            detail = (completed.stderr or "").strip()[-1000:]
            raise RuntimeError(f"ffmpeg remux failed ({completed.returncode}): {detail}")
        if not temporary.is_file() or temporary.stat().st_size < 12:
            raise RuntimeError("ffmpeg did not create a valid MP4 file")
        with temporary.open("rb") as handle:
            if handle.read(12)[4:8] != b"ftyp":
                raise RuntimeError("remux output does not contain an MP4 ftyp header")
        os.chmod(temporary, source.stat().st_mode & 0o777)
        os.replace(temporary, destination)
        if destination != source:
            source.unlink()
        return str(destination)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise
