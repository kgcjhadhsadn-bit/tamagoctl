#!/usr/bin/env python3
"""Generuje tekstury paczki (piksel-art 16x16 Kłaka Czarodzieja i ikonę 64x64) bez zewnętrznych bibliotek.
Uruchamiane ręcznie po zmianie wzoru; wynikowe PNG są w repozytorium."""
import struct
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Kłak: kędzierzawy kosmyk w fioletach z iskrą magii. '.' = przezroczysty.
PALETTE = {
    '.': (0, 0, 0, 0),
    'd': (60, 9, 108, 255),     # ciemny fiolet (obrys)
    'm': (123, 44, 191, 255),   # fiolet
    'l': (199, 125, 255, 255),  # jasny fiolet
    'w': (224, 170, 255, 255),  # połysk
    's': (255, 240, 170, 255),  # iskra
}
ART = [
    "................",
    "..........s.....",
    ".........sws....",
    "....ddd...s.....",
    "...dlllddd......",
    "..dlwwlmmmd.....",
    "..dlwlmmlmmd....",
    "...dmmlmwlmd....",
    "....dmmlwlmmd...",
    "....dmlmmlmmd...",
    ".....dmmlmmmd...",
    "......dmmlmd....",
    ".......dmmmd....",
    "........dmd.....",
    ".........d......",
    "................",
]


def png(path: Path, pixels: list[list[tuple[int, int, int, int]]]) -> None:
    h, w = len(pixels), len(pixels[0])
    raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *px) for px in row) for row in pixels)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.write_bytes(data)


def main() -> None:
    item = [[PALETTE[c] for c in row] for row in ART]
    png(HERE / "assets/kudlacze/textures/item/klak_czarodzieja.png", item)
    # ikona paczki 64x64: kłak powiększony 4x na ciemnym tle
    bg = (36, 0, 70, 255)
    icon = [[item[y // 4][x // 4] if item[y // 4][x // 4][3] else bg for x in range(64)] for y in range(64)]
    png(HERE / "pack.png", icon)


if __name__ == "__main__":
    main()
