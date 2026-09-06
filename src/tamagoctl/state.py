"""Pet state and its on-disk form.

Written atomically (temp file + os.replace) so a crash mid-write leaves the
previous state intact rather than a truncated JSON file the pet cannot read.
"""

from __future__ import annotations

import json
import os
import random
import time
from dataclasses import asdict, dataclass, field, replace
from pathlib import Path

from tamagoctl.config import state_path

SCHEMA_VERSION = 1

# Named after the things a sysadmin shouts at.
NAMES = (
    "Sudo", "Grep", "Cron", "Tmux", "Nohup", "Awk", "Sed", "Fsck", "Htop",
    "Dmesg", "Sysctl", "Swapfile", "Zombie", "Daemon", "Renice", "Loopback",
    "Localhost", "Segfault", "Kernel", "Lsof", "Rsync", "Chmod", "Umask",
    "Inode", "Symlink", "Tarball", "Bashrc", "Grub", "Initrd", "Mmap",
    "Ionice", "Netstat", "Traceroute", "Vmstat", "Iotop", "Sparsefile",
)

# How long you have to be away before the pet takes it personally.
GUILT_THRESHOLD_S = 3 * 86400


@dataclass
class PetState:
    """Everything that survives between sessions."""

    name: str
    born_at: float
    last_seen_at: float
    health: float = 100.0
    mood: str = "content"
    generation: int = 1
    predecessor: dict | None = None
    recent_comments: list[str] = field(default_factory=list)
    total_runtime_s: float = 0.0
    cpu_hot_since: float | None = None
    last_self_report_at: float = 0.0
    schema_version: int = SCHEMA_VERSION

    # -- derived ------------------------------------------------------------

    @property
    def alive(self) -> bool:
        return self.health > 0.0

    def age_s(self, now: float | None = None) -> float:
        return max(0.0, (time.time() if now is None else now) - self.born_at)

    def absence_s(self, now: float | None = None) -> float:
        return max(0.0, (time.time() if now is None else now) - self.last_seen_at)

    def owes_a_guilt_trip(self, now: float | None = None) -> bool:
        return self.absence_s(now) >= GUILT_THRESHOLD_S

    # -- comment history ----------------------------------------------------

    def remember_comment(self, line_id: str, keep: int = 10) -> None:
        """Ring buffer of the last N line ids, so nothing repeats too soon."""
        self.recent_comments.append(line_id)
        if len(self.recent_comments) > keep:
            del self.recent_comments[:-keep]

    # -- serialisation ------------------------------------------------------

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> "PetState":
        known = set(cls.__dataclass_fields__)
        clean = {k: v for k, v in data.items() if k in known}
        clean.setdefault("name", random.choice(NAMES))
        now = time.time()
        clean.setdefault("born_at", now)
        clean.setdefault("last_seen_at", now)
        state = cls(**clean)
        # A hand-edited or truncated file should not produce an impossible pet.
        state.health = max(0.0, min(100.0, float(state.health)))
        state.generation = max(1, int(state.generation))
        if not isinstance(state.recent_comments, list):
            state.recent_comments = []
        return state


def new_pet(
    now: float | None = None,
    generation: int = 1,
    predecessor: dict | None = None,
    rng: random.Random | None = None,
) -> PetState:
    """Hatch a pet. Avoids reusing the predecessor's name - that would be grim."""
    now = time.time() if now is None else now
    rng = rng or random
    pool = list(NAMES)
    if predecessor and predecessor.get("name") in pool and len(pool) > 1:
        pool.remove(predecessor["name"])
    return PetState(
        name=rng.choice(pool),
        born_at=now,
        last_seen_at=now,
        health=100.0,
        generation=generation,
        predecessor=predecessor,
    )


def load(path: Path | None = None) -> PetState | None:
    """Read state.json. A missing or corrupt file means 'no pet yet', not a crash."""
    path = path or state_path()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(data, dict):
        return None
    try:
        return PetState.from_dict(data)
    except (TypeError, ValueError):
        return None


def save(state: PetState, path: Path | None = None) -> Path:
    """Atomic write. A crash mid-save must not destroy the previous state."""
    path = path or state_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + f".{os.getpid()}.tmp")
    try:
        tmp.write_text(json.dumps(state.to_dict(), indent=2), encoding="utf-8")
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)
    return path


def load_or_create(path: Path | None = None, now: float | None = None) -> tuple[PetState, bool]:
    """Returns (state, is_new)."""
    existing = load(path)
    if existing is not None:
        return existing, False
    return new_pet(now), True


def touch(state: PetState, now: float | None = None, ran_for: float = 0.0) -> PetState:
    """Mark the pet as seen. Call this on the way out of a session."""
    now = time.time() if now is None else now
    return replace(
        state,
        last_seen_at=now,
        total_runtime_s=state.total_runtime_s + max(0.0, ran_for),
    )
