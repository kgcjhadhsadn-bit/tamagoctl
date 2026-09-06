"""Small pure formatting helpers. Shared by the mood engine, the TUI and the
graveyard so a duration reads the same everywhere."""

from __future__ import annotations


def duration(seconds: float | None, precision: int = 2) -> str:
    """'3m12s', '9d 4h', '412ms'. Coarse on purpose - nobody needs 3 decimals."""
    if seconds is None:
        return "unknown"
    seconds = float(seconds)
    if seconds < 0:
        seconds = 0.0
    if seconds < 1:
        return f"{seconds * 1000:.0f}ms"

    units = (("d", 86400), ("h", 3600), ("m", 60), ("s", 1))
    parts: list[str] = []
    remaining = int(seconds)
    for label, span in units:
        value, remaining = divmod(remaining, span)
        if value:
            parts.append(f"{value}{label}")
    return " ".join(parts[:precision]) if parts else "0s"


def days(seconds: float | None) -> float:
    return 0.0 if seconds is None else seconds / 86400.0


def size(num_bytes: float | None) -> str:
    if num_bytes is None:
        return "unknown"
    value = float(num_bytes)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(value) < 1024.0 or unit == "TB":
            return f"{value:.0f}{unit}" if unit == "B" else f"{value:.1f}{unit}"
        value /= 1024.0
    return f"{value:.1f}TB"


def pct(value: float | None, decimals: int = 1) -> str:
    return "n/a" if value is None else f"{value:.{decimals}f}%"
