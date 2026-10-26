#!/usr/bin/env python3
"""Prove which FFmpeg audio decoders a stripped arm64 libavcodec.so really contains.

Name strings are not evidence: FFmpeg's codec descriptor table names every codec whether or not
its decoder was compiled. This reader looks for the decoder records themselves instead. A
compiled decoder is an ``AVCodec`` (the first member of ``FFCodec``) laid out as

    const char *name; const char *long_name; enum AVMediaType type; enum AVCodecID id; ...

and is reachable only through the NULL-terminated ``codec_list`` pointer array. A descriptor
(``AVCodecDescriptor``) puts ``id``/``type`` *before* ``name`` and has zero padding after
``props``, so it can never match the decoder layout below. A decoder counts as present only when
its record exists *and* some pointer in the image refers to that record (its codec_list entry).

Pointers are read from the file image (RELR/implicit-addend relocations, as the Android NDK
emits) and overlaid with any explicit ``R_AARCH64_RELATIVE`` RELA addends. Standard library only.
"""
from __future__ import annotations

import argparse
import re
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

AVMEDIA_TYPE_AUDIO = 1
SHF_WRITE = 0x1
SHF_ALLOC = 0x2
SHT_NOBITS = 8
SHT_RELA = 4
R_AARCH64_RELATIVE = 1027
EM_AARCH64 = 183

JAVA_MAPPING = Path(__file__).resolve().parents[1] / (
    "media3-decoder-ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java"
)


class InventoryError(ValueError):
    """Raised when the library cannot be inspected reliably."""


@dataclass(frozen=True)
class Section:
    name: str
    kind: int
    flags: int
    addr: int
    offset: int
    size: int
    entsize: int


class Image:
    """Virtual-address view of an ELF64 little-endian shared object."""

    def __init__(self, data: bytes, sections: list[Section]):
        self.data = data
        self.sections = sections
        self._mapped = [s for s in sections if s.flags & SHF_ALLOC and s.kind != SHT_NOBITS and s.addr]
        self.overrides: dict[int, int] = {}

    @classmethod
    def from_elf(cls, data: bytes) -> "Image":
        if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
            raise InventoryError("expected an ELF64 little-endian shared object")
        machine = struct.unpack_from("<H", data, 0x12)[0]
        if machine != EM_AARCH64:
            raise InventoryError(f"expected an arm64 (AArch64) library, got e_machine={machine}")
        shoff = struct.unpack_from("<Q", data, 0x28)[0]
        shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)
        if not shoff or shentsize != 64 or not shnum:
            raise InventoryError("library has no usable section headers")
        raw = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize) for i in range(shnum)]
        names_offset = raw[shstrndx][4]

        def section_name(offset: int) -> str:
            start = names_offset + offset
            return data[start:data.index(b"\0", start)].decode("ascii", "replace")

        sections = [
            Section(section_name(r[0]), r[1], r[2], r[3], r[4], r[5], r[9]) for r in raw
        ]
        image = cls(data, sections)
        for section in sections:
            if section.kind == SHT_RELA and section.entsize == 24:
                for i in range(section.size // 24):
                    offset, info, addend = struct.unpack_from("<QQq", data, section.offset + i * 24)
                    if info & 0xFFFFFFFF == R_AARCH64_RELATIVE:
                        image.overrides[offset] = addend
        return image

    def _locate(self, vaddr: int, size: int) -> int | None:
        for section in self._mapped:
            if section.addr <= vaddr and vaddr + size <= section.addr + section.size:
                return section.offset + (vaddr - section.addr)
        return None

    def u32(self, vaddr: int) -> int | None:
        at = self._locate(vaddr, 4)
        return None if at is None else struct.unpack_from("<I", self.data, at)[0]

    def u64(self, vaddr: int) -> int | None:
        if vaddr in self.overrides:
            return self.overrides[vaddr]
        at = self._locate(vaddr, 8)
        return None if at is None else struct.unpack_from("<Q", self.data, at)[0]

    def c_string(self, vaddr: int, limit: int = 256) -> str | None:
        at = self._locate(vaddr, 1)
        if at is None:
            return None
        end = self.data.find(b"\0", at, at + limit)
        if end < 0:
            return None
        raw = self.data[at:end]
        if not raw or any(b < 0x20 or b > 0x7E for b in raw):
            return None
        return raw.decode("ascii")

    def pointer_words(self) -> dict[int, list[int]]:
        """Map pointed-to address -> locations of 8-byte-aligned pointer words in data sections."""
        words: dict[int, list[int]] = {}
        for section in self._mapped:
            if not section.flags & SHF_WRITE:
                continue  # .data.rel.ro and .data are writable before RELRO; .rodata is not.
            start = (section.addr + 7) & ~7
            for vaddr in range(start, section.addr + section.size - 7, 8):
                value = self.u64(vaddr)
                if value:
                    words.setdefault(value, []).append(vaddr)
        for location, addend in self.overrides.items():
            words.setdefault(addend, []).append(location)
        return words

    def string_addresses(self, name: str) -> set[int]:
        """Every address holding ``name\\0`` (the linker may tail-merge strings)."""
        needle = name.encode("ascii") + b"\0"
        found: set[int] = set()
        for section in self._mapped:
            if section.flags & SHF_WRITE:
                continue
            blob = self.data[section.offset:section.offset + section.size]
            for match in re.finditer(re.escape(needle), blob):
                found.add(section.addr + match.start())
        return found


def audio_decoders_present(image: Image, names: Iterable[str]) -> dict[str, bool]:
    words = image.pointer_words()
    result: dict[str, bool] = {}
    for name in names:
        present = False
        for string_addr in image.string_addresses(name):
            for record in words.get(string_addr, ()):  # candidate AVCodec.name fields
                long_name_ptr = image.u64(record + 8)
                media_type = image.u32(record + 16)
                codec_id = image.u32(record + 20)
                if media_type != AVMEDIA_TYPE_AUDIO or not codec_id:
                    continue
                if long_name_ptr and image.c_string(long_name_ptr) is None:
                    continue
                if any(location != record for location in words.get(record, ())):
                    present = True  # registered: codec_list holds a pointer to this record
                    break
            if present:
                break
        result[name] = present
    return result


def enabled_codec_names(java_source: str | None = None) -> list[str]:
    """FFmpeg decoder names returned by FfmpegLibrary.getCodecName (the single source of truth)."""
    text = java_source if java_source is not None else JAVA_MAPPING.read_text(encoding="utf-8")
    start = text.index("static String getCodecName(String mimeType)")
    end = text.index("\n  }\n", start)
    names = sorted(set(re.findall(r'return "([a-z0-9_]+)";', text[start:end])))
    if not names:
        raise InventoryError("FfmpegLibrary.getCodecName maps no FFmpeg decoders")
    return names


def verify_library(path: Path, names: Iterable[str] | None = None) -> list[str]:
    wanted = list(names) if names is not None else enabled_codec_names()
    found = audio_decoders_present(Image.from_elf(path.read_bytes()), wanted)
    missing = [name for name, ok in found.items() if not ok]
    if missing:
        raise InventoryError(
            "libavcodec.so lacks FFmpeg audio decoder(s) that FfmpegLibrary maps: " + ", ".join(missing)
        )
    return wanted


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("libavcodec", type=Path)
    parser.add_argument("names", nargs="*", help="decoder names (default: FfmpegLibrary mapping)")
    args = parser.parse_args()
    if args.names:
        found = audio_decoders_present(Image.from_elf(args.libavcodec.read_bytes()), args.names)
        for name, ok in found.items():
            print(f"{'present' if ok else 'MISSING'} {name}")
        sys.exit(0 if all(found.values()) else 1)
    try:
        names = verify_library(args.libavcodec)
    except InventoryError as error:
        raise SystemExit(str(error)) from error
    print(f"OK {args.libavcodec}: {len(names)} mapped audio decoders present: {', '.join(names)}")


if __name__ == "__main__":
    main()
