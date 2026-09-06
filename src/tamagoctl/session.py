"""The live loop: sampling, timing, the feed, and writing state back to disk.

Rendering lives in tui.py and the simulation lives in mood/health; this module
is only responsible for making them happen at the right moments.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from rich.console import Console
from rich.live import Live

from tamagoctl import comments, graveyard, state as state_mod
from tamagoctl.config import Config
from tamagoctl.health import Tick, step
from pathlib import Path

from tamagoctl.metrics import Metrics, MetricsCollector
from tamagoctl.state import PetState
from tamagoctl.tui import view

# A laptop that sleeps for six hours must not come back to a dead pet: clamp the
# elapsed time we are willing to simulate in one tick.
MAX_DT_FACTOR = 5.0

# How often state hits the disk. Frequent enough to survive a kill -9, rare
# enough not to matter.
SAVE_INTERVAL_S = 30.0

FEED_HISTORY = 200


@dataclass
class Frame:
    tick: Tick
    metrics: Metrics
    pet: PetState


class Session:
    """One run of the pet. Drives the simulation; owns nothing it can avoid."""

    def __init__(
        self,
        config: Config,
        pet: PetState,
        collector: MetricsCollector,
        narrator: comments.Narrator | None = None,
        clock=time.time,
    ):
        self.config = config
        self.pet = pet
        self.collector = collector
        self.narrator = narrator or comments.narrator_for(config, pet)
        self.clock = clock
        self.feed: list[str] = []
        self.started_at = clock()
        self.last_tick_at: float | None = None
        self.last_save_at = self.started_at
        self.last_self_report_at = self.started_at
        self.ticks = 0
        self.died = False
        self.grave: "Path | None" = None

    # -- feed ---------------------------------------------------------------

    def push(self, text: str | None, at: float | None = None) -> None:
        if not text:
            return
        stamp = time.strftime("%H:%M:%S", time.localtime(at if at else self.clock()))
        self.feed.append(f"{stamp}  {text}")
        del self.feed[:-FEED_HISTORY]

    def greet(self, metrics: Metrics) -> None:
        for line in self.narrator.greeting(self.pet, metrics, self.config):
            self.push(line, at=metrics.now)

    # -- simulation ---------------------------------------------------------

    def _elapsed(self, now: float) -> float:
        """Seconds since the previous tick, clamped against suspend and clock jumps."""
        if self.last_tick_at is None:
            return 0.0
        raw = now - self.last_tick_at
        if raw < 0:
            return 0.0
        return min(raw, self.config.refresh_s * MAX_DT_FACTOR)

    def advance(self) -> Frame:
        """Sample the machine and move the simulation forward one tick."""
        metrics = self.collector.sample()
        dt = self._elapsed(metrics.now)
        self.last_tick_at = metrics.now
        self.ticks += 1

        tick = step(metrics, self.pet.health, dt, self.config)
        self.pet.health = tick.health
        self.pet.mood = str(tick.mood)
        self.pet.cpu_hot_since = metrics.cpu_hot_since

        if self.ticks == 1:
            # The greeting needs a sample to talk about, but it has to lead the feed.
            self.greet(metrics)

        if tick.died:
            self._die(tick, metrics)
        else:
            self.push(
                self.narrator.observe(self.pet, tick, metrics, self.config), at=metrics.now
            )
            self._maybe_self_report(metrics)

        self._maybe_save(metrics.now, force=tick.died)
        return Frame(tick, metrics, self.pet)

    def _die(self, tick: Tick, metrics: Metrics) -> None:
        """Health hit zero. Write the tombstone once and say so."""
        stone = graveyard.make(self.pet, tick, metrics)
        self.grave = graveyard.bury(stone)
        self.died = True
        self.push(self.narrator.eulogy(self.pet, tick, metrics, self.config), at=metrics.now)
        self.push(f"Epitaph: {stone.epitaph}", at=metrics.now)
        self.push("Run `tamagoctl revive` when you are ready.", at=metrics.now)

    def _maybe_self_report(self, metrics: Metrics) -> None:
        """It reports its own CPU usage on a timer, apologetically."""
        interval = self.config.self_report_interval_s
        if interval <= 0:
            return
        if metrics.now - self.last_self_report_at < interval:
            return
        self.last_self_report_at = metrics.now
        self.pet.last_self_report_at = metrics.now
        self.push(self.narrator.self_report(metrics, self.config), at=metrics.now)

    def _maybe_save(self, now: float, force: bool = False) -> None:
        if not force and now - self.last_save_at < SAVE_INTERVAL_S:
            return
        self.last_save_at = now
        state_mod.save(self.pet)

    def finish(self) -> None:
        """Record the visit on the way out."""
        now = self.clock()
        self.pet = state_mod.touch(self.pet, now=now, ran_for=now - self.started_at)
        state_mod.save(self.pet)

    # -- rendering ----------------------------------------------------------

    def render(self, frame: Frame):
        return view(frame.pet, frame.tick, frame.metrics, self.config, feed=self.feed)

    def run(self, console: Console, max_ticks: int | None = None) -> Frame:
        """Block until ctrl-c, rendering every refresh_s seconds."""
        self.collector.start()
        frame = self.advance()

        try:
            # auto_refresh off: we repaint when there is new data, not on a timer.
            with Live(
                self.render(frame), console=console, auto_refresh=False,
                screen=False, transient=False,
            ) as live:
                while max_ticks is None or self.ticks < max_ticks:
                    deadline = time.monotonic() + self.config.refresh_s
                    remaining = deadline - time.monotonic()
                    if remaining > 0:
                        time.sleep(remaining)
                    frame = self.advance()
                    live.update(self.render(frame), refresh=True)
        except KeyboardInterrupt:
            pass
        finally:
            self.collector.stop()
            self.finish()
        return frame


def build(config: Config, pet: PetState | None = None) -> Session:
    if pet is None:
        pet, _ = state_mod.load_or_create()
    return Session(config, pet, MetricsCollector(config, hot_since=pet.cpu_hot_since))

