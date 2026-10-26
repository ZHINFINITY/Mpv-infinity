#!/usr/bin/env python3
"""Regression tests for the structural FFmpeg audio decoder inventory."""
from __future__ import annotations

from pathlib import Path
import struct
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from ffmpeg_decoder_inventory import (  # noqa: E402
    SHF_ALLOC,
    SHF_WRITE,
    Image,
    InventoryError,
    Section,
    audio_decoders_present,
    enabled_codec_names,
    FFCODEC_FLAGS_OFFSET,
    FFCODEC_IS_DECODER_MASK,
)

RODATA_ADDR, RODATA_OFF = 0x1000, 0x0
DATA_ADDR, DATA_OFF = 0x2000, 0x400
AUDIO, VIDEO = 1, 0


class FakeLibrary:
    """Builds an AArch64-style image: strings in .rodata, records in .data.rel.ro."""

    def __init__(self) -> None:
        self.rodata = bytearray()
        self.data = bytearray()
        self.strings: dict[str, int] = {}

    def string(self, text: str) -> int:
        if text not in self.strings:
            self.strings[text] = RODATA_ADDR + len(self.rodata)
            self.rodata += text.encode("ascii") + b"\0"
        return self.strings[text]

    def _here(self) -> int:
        while len(self.data) % 8:
            self.data += b"\0"
        return DATA_ADDR + len(self.data)

    def _codec_record(self, name: str, long_name: str, media_type: int, codec_id: int, is_decoder: bool) -> int:
        at = self._here()
        self.data += struct.pack(
            "<QQIIQ", self.string(name), self.string(long_name), media_type, codec_id, 0
        )
        flags_at = at - DATA_ADDR + FFCODEC_FLAGS_OFFSET
        if len(self.data) < flags_at + 4:
            self.data.extend(b"\0" * (flags_at + 4 - len(self.data)))
        struct.pack_into("<I", self.data, flags_at, FFCODEC_IS_DECODER_MASK if is_decoder else 0)
        return at

    def decoder(self, name: str, long_name: str, media_type: int, codec_id: int) -> int:
        return self._codec_record(name, long_name, media_type, codec_id, is_decoder=True)

    def encoder(self, name: str, long_name: str, media_type: int, codec_id: int) -> int:
        return self._codec_record(name, long_name, media_type, codec_id, is_decoder=False)

    def descriptor(self, name: str, long_name: str, media_type: int, codec_id: int) -> int:
        at = self._here()
        self.data += struct.pack(
            "<IIQQII", codec_id, media_type, self.string(name), self.string(long_name), 1, 0
        )
        return at

    def codec_list(self, *records: int) -> None:
        self._here()
        for record in records:
            self.data += struct.pack("<Q", record)
        self.data += struct.pack("<Q", 0)

    def image(self) -> Image:
        blob = bytearray(DATA_OFF + len(self.data))
        blob[RODATA_OFF:RODATA_OFF + len(self.rodata)] = self.rodata
        blob[DATA_OFF:] = self.data
        sections = [
            Section(".rodata", 1, SHF_ALLOC, RODATA_ADDR, RODATA_OFF, len(self.rodata), 0),
            Section(".data.rel.ro", 1, SHF_ALLOC | SHF_WRITE, DATA_ADDR, DATA_OFF, len(self.data), 0),
        ]
        return Image(bytes(blob), sections)


class DecoderInventoryTest(unittest.TestCase):
    def test_registered_audio_decoder_is_present(self) -> None:
        lib = FakeLibrary()
        lib.descriptor("truehd", "TrueHD", AUDIO, 0x1502C)
        truehd = lib.decoder("truehd", "TrueHD", AUDIO, 0x1502C)
        lib.codec_list(truehd)
        self.assertEqual({"truehd": True}, audio_decoders_present(lib.image(), ["truehd"]))

    def test_registered_encoder_only_record_is_not_an_audio_decoder(self) -> None:
        lib = FakeLibrary()
        aac_encoder = lib.encoder("aac", "AAC encoder", AUDIO, 0x15002)
        lib.codec_list(aac_encoder)
        self.assertEqual({"aac": False}, audio_decoders_present(lib.image(), ["aac"]))

    def test_descriptor_name_alone_is_not_a_decoder(self) -> None:
        lib = FakeLibrary()
        lib.descriptor("dts", "DCA (DTS Coherent Acoustics)", AUDIO, 0x15002)
        lib.descriptor("ac4", "AC-4", AUDIO, 0x15060)
        dca = lib.decoder("dca", "DCA (DTS Coherent Acoustics)", AUDIO, 0x15002)
        lib.codec_list(dca)
        self.assertEqual(
            {"dts": False, "ac4": False, "dca": True},
            audio_decoders_present(lib.image(), ["dts", "ac4", "dca"]),
        )

    def test_unregistered_record_is_not_present(self) -> None:
        lib = FakeLibrary()
        lib.decoder("eac3", "ATSC A/52B (AC-3, E-AC-3)", AUDIO, 0x15028)
        lib.codec_list()
        self.assertEqual({"eac3": False}, audio_decoders_present(lib.image(), ["eac3"]))

    def test_video_decoder_does_not_count_as_audio(self) -> None:
        lib = FakeLibrary()
        h264 = lib.decoder("h264", "H.264", VIDEO, 27)
        lib.codec_list(h264)
        self.assertEqual({"h264": False}, audio_decoders_present(lib.image(), ["h264"]))

    def test_explicit_rela_addend_is_used_for_zeroed_pointer(self) -> None:
        lib = FakeLibrary()
        opus = lib.decoder("opus", "Opus", AUDIO, 0x1503C)
        list_at = lib._here()
        lib.codec_list(0)  # file holds 0; the RELA addend supplies the real pointer
        image = lib.image()
        image.overrides[list_at] = opus
        self.assertEqual({"opus": True}, audio_decoders_present(image, ["opus"]))

    def test_names_come_from_the_java_mapping(self) -> None:
        names = enabled_codec_names()
        for required in ("dca", "truehd", "ac3", "eac3", "aac", "mp3", "opus", "flac", "vorbis"):
            self.assertIn(required, names)
        self.assertNotIn("h264", names)
        self.assertEqual(len(names), len(set(names)))

    def test_empty_java_mapping_fails_closed(self) -> None:
        source = (
            "static String getCodecName(String mimeType) {\n"
            "    switch (mimeType) {\n      default:\n        return null;\n    }\n  }\n"
        )
        with self.assertRaises(InventoryError):
            enabled_codec_names(source)

    def test_non_elf_input_is_rejected(self) -> None:
        with self.assertRaises(InventoryError):
            Image.from_elf(b"not an elf file at all" * 4)


if __name__ == "__main__":
    unittest.main()
