"""Health arithmetic. Pure, like the mood engine: dt arrives as an argument.

Health decays while any metric is red, regenerates slowly when everything is
green, and hitting zero is fatal and irreversible within a life.
"""

from __future__ import annotations

from dataclasses import dataclass

from tamagoctl.config import Config
from tamagoctl.metrics import Metrics
from tamagoctl.mood import Mood, Verdict, evaluate


@dataclass(frozen=True)
class Tick:
    """The full result of one step of the simulation."""

    verdict: Verdict
    health: float
    previous_health: float
    delta: float
    died: bool

    @property
    def mood(self) -> Mood:
        # Keyed on health, not on `died`: `died` is the transition and fires once,
        # but the pet stays dead on every tick after it.
        return Mood.DEAD if self.health <= 0.0 else self.verdict.mood

    @property
    def cause(self) -> str | None:
        return self.verdict.cause


def health_delta(verdict: Verdict, dt: float, config: Config) -> float:
    """Per-red-metric decay, or a slow trickle back up when nothing is wrong."""
    if dt <= 0:
        return 0.0
    rules = config.health
    if verdict.red:
        return -rules.decay_per_red_per_s * len(verdict.red) * dt
    return rules.regen_per_s * dt


def apply_health(health: float, delta: float, config: Config) -> float:
    return max(0.0, min(config.health.max_health, health + delta))


def step(metrics: Metrics, health: float, dt: float, config: Config) -> Tick:
    """Evaluate metrics and advance health by dt seconds.

    This is the composed pure function the whole simulation runs on. Feed it
    fabricated metrics and it will tell you exactly what the pet would do.
    """
    verdict = evaluate(metrics, config)
    if health <= 0.0:
        # Already dead. Death does not decay further and does not heal.
        return Tick(verdict, 0.0, health, 0.0, died=False)

    delta = health_delta(verdict, dt, config)
    new_health = apply_health(health, delta, config)
    return Tick(
        verdict=verdict,
        health=new_health,
        previous_health=health,
        delta=new_health - health,
        died=new_health <= 0.0,
    )


def time_to_live(health: float, verdict: Verdict, config: Config) -> float | None:
    """Seconds until death at the current decay rate. None if not dying."""
    if not verdict.red or health <= 0:
        return None
    rate = config.health.decay_per_red_per_s * len(verdict.red)
    return health / rate if rate > 0 else None
