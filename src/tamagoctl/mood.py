"""The mood engine. Pure: no clock, no psutil, no randomness, no I/O.

Everything it needs arrives as arguments, which is what makes it testable with
fabricated metrics and what keeps the rest of the program honest.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from tamagoctl.config import Config
from tamagoctl.fmt import duration, pct, size
from tamagoctl.metrics import Metrics


class Mood(StrEnum):
    HANGRY = "hangry"
    CONSTIPATED = "constipated"
    BLOATED = "bloated"
    SWEATING = "sweating"
    DIZZY = "dizzy"
    SMUG = "smug"
    CONTENT = "content"
    DEAD = "dead"


LABELS: dict[Mood, str] = {
    Mood.HANGRY: "hangry",
    Mood.CONSTIPATED: "constipated",
    Mood.BLOATED: "bloated",
    Mood.SWEATING: "sweating",
    Mood.DIZZY: "dizzy",
    Mood.SMUG: "smug",
    Mood.CONTENT: "insufferably content",
    Mood.DEAD: "dead",
}

# Worst first. Several metrics go red at once all the time, so the order is the
# whole design: hangry means the machine is about to stop existing, a full disk
# breaks writes, RAM and CPU are recoverable, and latency is someone else's fault.
PRIORITY: tuple[str, ...] = ("battery", "disk", "ram", "cpu", "net", "uptime")

CHECK_MOODS: dict[str, Mood] = {
    "battery": Mood.HANGRY,
    "disk": Mood.CONSTIPATED,
    "ram": Mood.BLOATED,
    "cpu": Mood.SWEATING,
    "net": Mood.DIZZY,
    "uptime": Mood.SMUG,
}

# Uptime is an attitude, not an injury. Everything else drains health.
DAMAGING: frozenset[str] = frozenset({"battery", "disk", "ram", "cpu", "net"})


@dataclass(frozen=True)
class Check:
    key: str
    triggered: bool
    available: bool
    detail: str
    value: float | None = None

    @property
    def damaging(self) -> bool:
        return self.key in DAMAGING

    @property
    def red(self) -> bool:
        return self.triggered and self.damaging


@dataclass(frozen=True)
class Verdict:
    """What the metrics say, before health arithmetic."""

    mood: Mood
    checks: tuple[Check, ...]
    red: tuple[str, ...]
    triggered: tuple[str, ...]
    killer: str | None
    cause: str | None

    def check(self, key: str) -> Check | None:
        for item in self.checks:
            if item.key == key:
                return item
        return None

    @property
    def all_green(self) -> bool:
        return not self.red

    @property
    def label(self) -> str:
        return LABELS[self.mood]

    @property
    def unavailable(self) -> tuple[str, ...]:
        return tuple(c.key for c in self.checks if not c.available)


def _cpu_check(m: Metrics, cfg: Config) -> Check:
    th = cfg.thresholds
    if m.cpu_pct is None:
        return Check("cpu", False, False, "CPU unreadable")
    hot_for = m.cpu_hot_for
    triggered = m.cpu_pct >= th.cpu_pct and hot_for >= th.cpu_sustain_s
    if triggered:
        detail = f"CPU {pct(m.cpu_pct)} sustained for {duration(hot_for)}"
    elif m.cpu_pct >= th.cpu_pct:
        detail = f"CPU {pct(m.cpu_pct)} for {duration(hot_for)} (not yet sustained)"
    else:
        detail = f"CPU {pct(m.cpu_pct)}"
    return Check("cpu", triggered, True, detail, m.cpu_pct)


def _ram_check(m: Metrics, cfg: Config) -> Check:
    if m.ram_pct is None:
        return Check("ram", False, False, "RAM unreadable")
    triggered = m.ram_pct >= cfg.thresholds.ram_pct
    detail = f"RAM {pct(m.ram_pct)}"
    if m.ram_used_bytes and m.ram_total_bytes:
        detail += f" ({size(m.ram_used_bytes)} of {size(m.ram_total_bytes)})"
    return Check("ram", triggered, True, detail, m.ram_pct)


def _disk_check(m: Metrics, cfg: Config) -> Check:
    if m.disk_free_pct is None:
        return Check("disk", False, False, "Disk unreadable")
    triggered = m.disk_free_pct < cfg.thresholds.disk_free_pct
    where = m.disk_path or "/"
    detail = f"Disk {pct(m.disk_free_pct)} free on {where}"
    if m.disk_free_bytes is not None:
        detail += f" ({size(m.disk_free_bytes)})"
    return Check("disk", triggered, True, detail, m.disk_free_pct)


def _net_check(m: Metrics, cfg: Config) -> Check:
    host = cfg.network.host
    if not cfg.network.enabled:
        return Check("net", False, False, "Network check disabled")
    if m.packet_loss is None and m.latency_ms is None:
        return Check("net", False, False, f"No reading from {host} yet")
    if m.packet_loss:
        if m.latency_ms is None:
            return Check("net", True, True, f"No answer from {host}", None)
        return Check(
            "net", True, True, f"Packet loss to {host} ({m.latency_ms:.0f}ms when it answers)",
            m.latency_ms,
        )
    if m.latency_ms is None:
        return Check("net", False, False, f"No reading from {host} yet")
    triggered = m.latency_ms > cfg.thresholds.latency_ms
    return Check("net", triggered, True, f"{host} at {m.latency_ms:.0f}ms", m.latency_ms)


def _battery_check(m: Metrics, cfg: Config) -> Check:
    if m.battery_pct is None:
        # Desktops and servers land here. No battery means hangry is unreachable,
        # which is correct, not a gap.
        return Check("battery", False, False, "No battery on this machine")
    plugged = m.power_plugged
    triggered = m.battery_pct < cfg.thresholds.battery_pct and plugged is False
    state = "unplugged" if plugged is False else "plugged in" if plugged else "unknown power"
    detail = f"Battery {pct(m.battery_pct, 0)} and {state}"
    if triggered and m.battery_secs_left:
        detail += f", {duration(m.battery_secs_left)} left"
    return Check("battery", triggered, True, detail, m.battery_pct)


def _uptime_check(m: Metrics, cfg: Config) -> Check:
    if m.uptime_s is None:
        return Check("uptime", False, False, "Uptime unreadable")
    triggered = m.uptime_s >= cfg.thresholds.uptime_days * 86400
    return Check("uptime", triggered, True, f"Up {duration(m.uptime_s)}", m.uptime_s)


CHECKERS = {
    "cpu": _cpu_check,
    "ram": _ram_check,
    "disk": _disk_check,
    "net": _net_check,
    "battery": _battery_check,
    "uptime": _uptime_check,
}


def evaluate(metrics: Metrics, config: Config) -> Verdict:
    """Metrics in, mood out. The single source of truth for how the pet feels."""
    checks = tuple(CHECKERS[key](metrics, config) for key in PRIORITY)
    by_key = {c.key: c for c in checks}

    triggered = tuple(k for k in PRIORITY if by_key[k].triggered)
    red = tuple(k for k in triggered if by_key[k].damaging)

    mood = CHECK_MOODS[triggered[0]] if triggered else Mood.CONTENT
    killer = red[0] if red else None
    cause = by_key[killer].detail if killer else None

    return Verdict(
        mood=mood, checks=checks, red=red, triggered=triggered, killer=killer, cause=cause
    )
