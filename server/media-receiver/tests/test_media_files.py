from pathlib import Path

from media_receiver.media_files import TS_PACKET_BYTES, png_transport_stream_prefix


PNG_1X1 = bytes.fromhex(
    "89504e470d0a1a0a"
    "0000000d49484452000000010000000108060000001f15c489"
    "0000000d4944415478da63fccfc0500f000485018084a98c21"
    "0000000049454e44ae426082"
)


def test_detects_transport_stream_after_alignment_padding(tmp_path: Path) -> None:
    padding = b"\xff" * (TS_PACKET_BYTES - len(PNG_1X1))
    packet = b"\x47" + b"\x00" * (TS_PACKET_BYTES - 1)
    media = tmp_path / "wrapped.mp4"
    media.write_bytes(PNG_1X1 + padding + packet * 3)

    assert png_transport_stream_prefix(media) == TS_PACKET_BYTES


def test_plain_png_is_not_treated_as_transport_stream(tmp_path: Path) -> None:
    image = tmp_path / "pixel.png"
    image.write_bytes(PNG_1X1)

    assert png_transport_stream_prefix(image) is None
