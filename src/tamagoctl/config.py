"""Configuration: thresholds, health rules, network probe settings, sass level.

Everything here is a frozen dataclass so the pure modules (mood, health) can take
config as an argument instead of reaching for a global.
"""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field, replace
from pathlib import Path

MIN_SASS = 0
MAX_SASS = 3


def home() -> Path:
    """Root directory for state, config and the graveyard.

    Honours ``TAMAGOCTL_HOME`` so tests get a sandbox and so you can keep more
    than one pet if you are that sort of person.
    """
    env = os.environ.get("TAMAGOCTL_HOME")
    return Path(env).expanduser() if env else Path.home() / ".tamagoctl"


def config_path() -> Path:
    return home() / "config.toml"


def state_path() -> Path:
    return home() / "state.json"


def graveyard_dir() -> Path:
    return home() / "graveyard"


@dataclass(frozen=True)
class Thresholds:
    """The line between "fine" and "the pet has opinions"."""

    cpu_pct: float = 85.0
    cpu_sustain_s: float = 60.0
    ram_pct: float = 90.0
    disk_free_pct: float = 10.0
    latency_ms: float = 150.0
    battery_pct: float = 20.0
    uptime_days: float = 7.0


@dataclass(frozen=True)
class HealthRules:
    max_health: float = 100.0
    # One red metric drains a full-health pet in ~33 minutes. Three do it in ~11.
    decay_per_red_per_s: float = 0.05
    regen_per_s: float = 0.02


@dataclass(frozen=True)
class NetworkConfig:
    """The only outbound call this program makes. Off is a supported answer."""

    enabled: bool = True
    host: str = "1.1.1.1"
    port: int = 443
    probes: int = 3
    timeout_s: float = 1.0
    interval_s: float = 30.0


@dataclass(frozen=True)
class Config:
    sass_level: int = 2
    refresh_s: float = 2.0
    self_report_interval_s: float = 600.0
    # Process enumeration is the expensive syscall, so it runs on its own slow
    # cadence rather than every tick. This is load-bearing for the CPU budget.
    process_scan_interval_s: float = 10.0
    feed_lines: int = 8
    thresholds: Thresholds = field(default_factory=Thresholds)
    health: HealthRules = field(default_factory=HealthRules)
    network: NetworkConfig = field(default_factory=NetworkConfig)


DEFAULT_CONFIG_TOML = """\
# tamagoctl configuration

# 0 = supportive (a plain system monitor with a face)
# 1 = dry
# 2 = pointed
# 3 = unhinged
sass_level = 2

refresh_s = 2.0                 # TUI tick, in seconds
self_report_interval_s = 600.0  # how often it apologises for its own CPU usage
process_scan_interval_s = 10.0  # process enumeration cadence (keeps CPU under 1%)
feed_lines = 8                  # comment feed height

[thresholds]
cpu_pct = 85.0        # -> sweating, once sustained
cpu_sustain_s = 60.0
ram_pct = 90.0        # -> bloated
disk_free_pct = 10.0  # -> constipated (free space below this)
latency_ms = 150.0    # -> dizzy
battery_pct = 20.0    # -> hangry (only when unplugged)
uptime_days = 7.0     # -> smug

[health]
max_health = 100.0
decay_per_red_per_s = 0.05  # per red metric
regen_per_s = 0.02          # only while everything is green

[network]
enabled = true    # the only network call in the program; false disables `dizzy`
host = "1.1.1.1"
port = 443
probes = 3
timeout_s = 1.0
interval_s = 30.0
"""


def _sub(cls, defaults, table: object):
    """Build a frozen dataclass from a TOML table, ignoring unknown keys."""
    if not isinstance(table, dict):
        return defaults
    known = {f for f in defaults.__dataclass_fields__}
    updates = {}
    for key, value in table.items():
        if key not in known:
            continue
        current = getattr(defaults, key)
        try:
            if isinstance(current, bool):
                updates[key] = bool(value)
            elif isinstance(current, int) and not isinstance(current, bool):
                updates[key] = int(value)
            elif isinstance(current, float):
                updates[key] = float(value)
            else:
                updates[key] = value
        except (TypeError, ValueError):
            continue  # a broken value should not stop the pet from booting
    return replace(defaults, **updates)


def config_from_dict(data: dict) -> Config:
    base = Config()
    sass = data.get("sass_level", base.sass_level)
    try:
        sass = int(sass)
    except (TypeError, ValueError):
        sass = base.sass_level
    scalars = {}
    for key in ("refresh_s", "self_report_interval_s", "process_scan_interval_s"):
        if key in data:
            try:
                scalars[key] = float(data[key])
            except (TypeError, ValueError):
                pass
    if "feed_lines" in data:
        try:
            scalars["feed_lines"] = max(1, int(data["feed_lines"]))
        except (TypeError, ValueError):
            pass
    return Config(
        sass_level=clamp_sass(sass),
        thresholds=_sub(Thresholds, base.thresholds, data.get("thresholds")),
        health=_sub(HealthRules, base.health, data.get("health")),
        network=_sub(NetworkConfig, base.network, data.get("network")),
        **scalars,
    )


def clamp_sass(level: int) -> int:
    return max(MIN_SASS, min(MAX_SASS, level))


def load_config(path: Path | None = None) -> Config:
    """Load config.toml. A missing or malformed file yields defaults, never an error."""
    path = path or config_path()
    try:
        with open(path, "rb") as fh:
            data = tomllib.load(fh)
    except (OSError, tomllib.TOMLDecodeError):
        return Config()
    return config_from_dict(data)


def ensure_config(path: Path | None = None) -> Path:
    """Write the commented default config if none exists. Returns the path."""
    path = path or config_path()
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(DEFAULT_CONFIG_TOML, encoding="utf-8")
    return path
