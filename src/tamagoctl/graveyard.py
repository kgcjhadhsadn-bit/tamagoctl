"""Tombstones. When a pet dies it leaves a file behind with the numbers in it.

One JSON file per pet under ~/.tamagoctl/graveyard/, named by time of death so
a directory listing is already in chronological order.
"""

from __future__ import annotations

import json
import os
import random
import re
from dataclasses import asdict, dataclass, field
from pathlib import Path

from tamagoctl import fmt
from tamagoctl.config import graveyard_dir
from tamagoctl.health import Tick
from tamagoctl.lines import EPITAPH_FALLBACK, EPITAPHS
from tamagoctl.metrics import Metrics
from tamagoctl.state import PetState

SCHEMA_VERSION = 1
_UNSAFE = re.compile(r"[^A-Za-z0-9_.-]")


@dataclass(frozen=True)
class Tombstone:
    name: str
    generation: int
    born_at: float
    died_at: float
    lifespan_s: float
    cause: str
    killer: str | None
    epitaph: str
    final_metrics: dict = field(default_factory=dict)
    schema_version: int = SCHEMA_VERSION

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> "Tombstone":
        known = set(cls.__dataclass_fields__)
        clean = {k: v for k, v in data.items() if k in known}
        clean.setdefault("name", "unknown")
        clean.setdefault("generation", 1)
        clean.setdefault("born_at", 0.0)
        clean.setdefault("died_at", 0.0)
        clean.setdefault("lifespan_s", 0.0)
        clean.setdefault("cause", "unknown")
        clean.setdefault("killer", None)
        clean.setdefault("epitaph", "")
        return cls(**clean)

    @property
    def lifespan(self) -> str:
        return fmt.duration(self.lifespan_s)

    def as_predecessor(self) -> dict:
        """The subset a new pet carries around and mentions occasionally."""
        return {
            "name": self.name,
            "generation": self.generation,
            "died_at": self.died_at,
            "lifespan_s": self.lifespan_s,
            "cause": self.cause,
            "killer": self.killer,
        }


def epitaph_for(killer: str | None, rng: random.Random | None = None) -> str:
    rng = rng or random
    return rng.choice(EPITAPHS.get(killer or "", EPITAPH_FALLBACK))


def snapshot(metrics: Metrics, tick: Tick) -> dict:
    """The state of the machine at the moment of death, for the record."""
    return {
        "cpu_pct": metrics.cpu_pct,
        "cpu_hot_for_s": round(metrics.cpu_hot_for, 1),
        "ram_pct": metrics.ram_pct,
        "disk_free_pct": metrics.disk_free_pct,
        "disk_path": metrics.disk_path,
        "latency_ms": metrics.latency_ms,
        "packet_loss": metrics.packet_loss,
        "battery_pct": metrics.battery_pct,
        "power_plugged": metrics.power_plugged,
        "uptime_s": round(metrics.uptime_s, 1) if metrics.uptime_s else None,
        "red": list(tick.verdict.red),
        "checks": {c.key: c.detail for c in tick.verdict.checks},
    }


def make(
    pet: PetState, tick: Tick, metrics: Metrics, rng: random.Random | None = None
) -> Tombstone:
    return Tombstone(
        name=pet.name,
        generation=pet.generation,
        born_at=pet.born_at,
        died_at=metrics.now,
        lifespan_s=max(0.0, metrics.now - pet.born_at),
        cause=tick.verdict.cause or "Health reached zero with no metric to blame.",
        killer=tick.verdict.killer,
        epitaph=epitaph_for(tick.verdict.killer, rng),
        final_metrics=snapshot(metrics, tick),
    )


def _filename(stone: Tombstone) -> str:
    # Generation is in the name because two pets can share a name across
    # generations, and a same-second death would otherwise overwrite a grave.
    safe = _UNSAFE.sub("_", stone.name) or "pet"
    return f"{int(stone.died_at)}-g{stone.generation}-{safe}.json"


def bury(stone: Tombstone, directory: Path | None = None) -> Path:
    """Write the tombstone. Atomic, like state - a half-written grave is worse."""
    directory = directory or graveyard_dir()
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / _filename(stone)
    tmp = path.with_name(path.name + f".{os.getpid()}.tmp")
    try:
        tmp.write_text(json.dumps(stone.to_dict(), indent=2), encoding="utf-8")
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)
    return path


def graves(directory: Path | None = None) -> list[Tombstone]:
    """Every tombstone, oldest first. Unreadable files are skipped, not fatal."""
    directory = directory or graveyard_dir()
    if not directory.is_dir():
        return []
    out: list[Tombstone] = []
    for path in sorted(directory.glob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if isinstance(data, dict):
            try:
                out.append(Tombstone.from_dict(data))
            except (TypeError, ValueError):
                continue
    # Tie-break on birth so the order is stable when two pets die in the same
    # second - otherwise `latest()` would depend on directory listing order.
    return sorted(out, key=lambda stone: (stone.died_at, stone.born_at))


def latest(directory: Path | None = None) -> Tombstone | None:
    found = graves(directory)
    return found[-1] if found else None


def find_by_birth(born_at: float, directory: Path | None = None) -> Tombstone | None:
    """The grave belonging to one specific pet.

    Birth time identifies a pet exactly; names repeat across generations and
    two pets can die in the same second, so neither is safe to match on.
    """
    for stone in graves(directory):
        if stone.born_at == born_at:
            return stone
    return None
