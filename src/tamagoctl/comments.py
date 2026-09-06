"""The commentary. sass_level 0 lives here too - it is a narrator like any
other, it just refuses to be rude.
"""

from __future__ import annotations

from typing import Protocol

from tamagoctl import fmt
from tamagoctl.config import Config
from tamagoctl.health import Tick
from tamagoctl.metrics import Metrics
from tamagoctl.mood import Mood
from tamagoctl.state import PetState

SUMMARY_INTERVAL_S = 30.0


class Narrator(Protocol):
    """What the session needs from whoever is doing the talking."""

    def greeting(self, pet: PetState, metrics: Metrics, cfg: Config) -> list[str]:
        ...

    def observe(self, pet: PetState, tick: Tick, metrics: Metrics, cfg: Config) -> str | None:
        ...

    def self_report(self, metrics: Metrics, cfg: Config) -> str:
        ...


class FactualNarrator:
    """sass_level 0. A system monitor with a face and no opinions.

    Someone who genuinely wants a monitor has to be able to use this, so it
    reports state changes and a periodic summary and nothing else.
    """

    def __init__(self) -> None:
        self._last_mood: Mood | None = None
        self._last_summary_at = 0.0

    def greeting(self, pet: PetState, metrics: Metrics, cfg: Config) -> list[str]:
        lines = [f"{pet.name} (generation {pet.generation}), health {pet.health:.0f}/100."]
        away = pet.absence_s(metrics.now)
        if away > 3600:
            lines.append(f"Last session ended {fmt.duration(away)} ago.")
        if pet.predecessor:
            pred = pet.predecessor
            lines.append(
                f"Previous pet: {pred.get('name', '?')}, "
                f"cause of death {pred.get('cause', 'unknown')}."
            )
        return lines

    def observe(self, pet: PetState, tick: Tick, metrics: Metrics, cfg: Config) -> str | None:
        mood = tick.mood
        if mood is not self._last_mood:
            self._last_mood = mood
            self._last_summary_at = metrics.now
            if tick.verdict.cause:
                return tick.verdict.cause
            if mood is Mood.SMUG:
                return tick.verdict.check("uptime").detail
            return "All monitored metrics within thresholds."

        if metrics.now - self._last_summary_at >= SUMMARY_INTERVAL_S:
            self._last_summary_at = metrics.now
            return self._summary(tick, cfg)
        return None

    def _summary(self, tick: Tick, cfg: Config) -> str:
        parts = [c.detail for c in tick.verdict.checks if c.available]
        return "  ".join(parts) if parts else "No metrics available on this machine."

    def self_report(self, metrics: Metrics, cfg: Config) -> str:
        rss = f", {fmt.size(metrics.self_rss_bytes)} rss" if metrics.self_rss_bytes else ""
        return f"tamagoctl is using {metrics.self_cpu_pct:.2f}% CPU{rss}."


def narrator_for(cfg: Config, pet: PetState) -> Narrator:
    """Pick a narrator for the configured sass level."""
    return FactualNarrator()
