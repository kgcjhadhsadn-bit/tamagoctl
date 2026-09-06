"""The live loop: timing, clamping, feed behaviour and the CPU budget."""

import time
from dataclasses import replace

import pytest
from rich.console import Console

from tamagoctl import state as state_mod
from tamagoctl.comments import FactualNarrator
from tamagoctl.config import Config, NetworkConfig
from tamagoctl.metrics import MetricsCollector
from tamagoctl.session import MAX_DT_FACTOR, SAVE_INTERVAL_S, Session
from tamagoctl.state import new_pet
from tests import factories as f

OFFLINE = replace(Config(), network=NetworkConfig(enabled=False))


class FakeCollector:
    """Replays a scripted list of metrics instead of touching the machine."""

    def __init__(self, samples):
        self.samples = list(samples)
        self.index = 0
        self.started = False

    def sample(self, now=None):
        sample = self.samples[min(self.index, len(self.samples) - 1)]
        self.index += 1
        return sample

    def start(self):
        self.started = True
        return self

    def stop(self):
        self.started = False


def make_session(samples, config=OFFLINE, pet=None):
    pet = pet or new_pet(now=f.NOW)
    return Session(config, pet, FakeCollector(samples), narrator=FactualNarrator(),
                   clock=lambda: f.NOW)


class TestTiming:
    def test_the_first_tick_advances_nothing(self):
        """There is no previous tick to measure from, so dt is zero."""
        sess = make_session([f.bloated()])
        frame = sess.advance()
        assert frame.tick.delta == 0.0
        assert frame.tick.health == 100.0

    def test_the_second_tick_uses_the_real_elapsed_time(self):
        first = f.bloated(now=f.NOW)
        second = f.bloated(now=f.NOW + 2.0)
        sess = make_session([first, second])
        sess.advance()
        frame = sess.advance()
        assert frame.tick.delta == pytest.approx(-OFFLINE.health.decay_per_red_per_s * 2.0)

    def test_a_suspended_laptop_does_not_come_back_to_a_corpse(self):
        """Six hours of sleep must not be simulated as six hours of decay."""
        sess = make_session([f.bloated(now=f.NOW), f.bloated(now=f.NOW + 6 * 3600)])
        sess.advance()
        frame = sess.advance()
        cap = OFFLINE.refresh_s * MAX_DT_FACTOR
        assert frame.tick.delta == pytest.approx(
            -OFFLINE.health.decay_per_red_per_s * cap
        )
        assert frame.tick.health > 99.0

    def test_a_backwards_clock_advances_nothing(self):
        sess = make_session([f.green(now=f.NOW), f.green(now=f.NOW - 500)])
        sess.advance()
        assert sess.advance().tick.delta == 0.0


class TestFeed:
    def test_the_greeting_leads_the_feed(self):
        pet = new_pet(now=f.NOW)
        sess = make_session([f.green()], pet=pet)
        sess.advance()
        assert pet.name in sess.feed[0]
        assert "generation 1" in sess.feed[0]

    def test_lines_are_timestamped(self):
        sess = make_session([f.green()])
        sess.advance()
        assert sess.feed[0][2] == ":" and sess.feed[0][5] == ":"

    def test_a_mood_change_is_always_reported(self):
        sess = make_session([f.green(now=f.NOW), f.constipated(now=f.NOW + 2)])
        sess.advance()
        before = len(sess.feed)
        sess.advance()
        assert len(sess.feed) > before
        assert "free on /" in sess.feed[-1]

    def test_a_steady_mood_does_not_spam_the_feed(self):
        """Level 0 is a monitor. It must not print a line every two seconds."""
        samples = [f.green(now=f.NOW + i * 2) for i in range(10)]
        sess = make_session(samples)
        for _ in samples:
            sess.advance()
        assert len(sess.feed) <= 3

    def test_the_feed_is_bounded(self):
        sess = make_session([f.green()])
        for i in range(500):
            sess.push(f"line {i}")
        assert len(sess.feed) <= 200

    def test_empty_lines_are_not_pushed(self):
        sess = make_session([f.green()])
        sess.push(None)
        sess.push("")
        assert sess.feed == []

    def test_the_greeting_mentions_a_predecessor(self):
        pet = new_pet(now=f.NOW, generation=2,
                      predecessor={"name": "Awk", "generation": 1, "cause": "Disk 0.4% free"})
        sess = make_session([f.green()], pet=pet)
        sess.advance()
        assert any("Awk" in line for line in sess.feed)


class TestSelfReport:
    def test_it_apologises_for_its_own_cpu_on_a_timer(self):
        cfg = replace(OFFLINE, self_report_interval_s=600.0)
        samples = [f.green(now=f.NOW), f.green(now=f.NOW + 601)]
        sess = make_session(samples, config=cfg)
        sess.advance()
        sess.advance()
        assert any("% CPU" in line for line in sess.feed)

    def test_it_does_not_report_before_the_interval(self):
        cfg = replace(OFFLINE, self_report_interval_s=600.0)
        samples = [f.green(now=f.NOW), f.green(now=f.NOW + 100)]
        sess = make_session(samples, config=cfg)
        sess.advance()
        sess.advance()
        assert not any("% CPU" in line for line in sess.feed)

    def test_a_zero_interval_disables_the_report(self):
        cfg = replace(OFFLINE, self_report_interval_s=0.0)
        sess = make_session([f.green(now=f.NOW), f.green(now=f.NOW + 10_000)], config=cfg)
        sess.advance()
        sess.advance()
        assert not any("% CPU" in line for line in sess.feed)


class TestPersistence:
    def test_state_is_written_periodically_during_a_long_session(self):
        samples = [f.green(now=f.NOW), f.green(now=f.NOW + SAVE_INTERVAL_S + 1)]
        sess = make_session(samples)
        sess.advance()
        sess.advance()
        assert state_mod.load() is not None

    def test_finish_records_the_visit(self):
        sess = make_session([f.green()])
        sess.advance()
        sess.finish()
        saved = state_mod.load()
        assert saved.last_seen_at == pytest.approx(f.NOW)
        assert saved.sessions >= 0

    def test_health_survives_the_round_trip(self):
        sess = make_session([f.bloated(now=f.NOW), f.bloated(now=f.NOW + 100)])
        sess.advance()
        sess.advance()
        sess.finish()
        assert state_mod.load().health < 100.0

    def test_the_cpu_streak_is_carried_across_sessions(self):
        sess = make_session([f.sweating()])
        sess.advance()
        sess.finish()
        assert state_mod.load().cpu_hot_since == f.sweating().cpu_hot_since


class TestCpuBudget:
    """Under 1% CPU is a hard requirement and also the running joke."""

    def test_a_full_tick_costs_well_under_one_percent_of_the_refresh_interval(self):
        cfg = OFFLINE
        pet = new_pet()
        sess = Session(cfg, pet, MetricsCollector(cfg), narrator=FactualNarrator())
        console = Console(file=open("/dev/null", "w"), width=100, force_terminal=False)

        ticks = 25
        sess.advance()  # prime caches so we measure the steady state
        start = time.process_time()
        for _ in range(ticks):
            frame = sess.advance()
            console.print(sess.render(frame))
        cpu_per_tick = (time.process_time() - start) / ticks

        budget = cfg.refresh_s * 0.01  # 1% of a 2s tick = 20ms
        assert cpu_per_tick < budget, (
            f"{cpu_per_tick * 1000:.1f}ms of CPU per tick exceeds the "
            f"{budget * 1000:.0f}ms budget"
        )
        console.file.close()

    def test_process_enumeration_does_not_run_every_tick(self, monkeypatch):
        """Enumerating processes is the syscall that would blow the budget."""
        from tamagoctl import metrics as metrics_mod

        cfg = OFFLINE
        collector = MetricsCollector(cfg)
        enumerations = []
        real_iter = metrics_mod.psutil.process_iter

        def counted(*args, **kwargs):
            enumerations.append(1)
            return real_iter(*args, **kwargs)

        monkeypatch.setattr(metrics_mod.psutil, "process_iter", counted)

        base = time.time()
        ticks = 30
        for i in range(ticks):
            collector.sample(now=base + i * cfg.refresh_s)  # 60 seconds of ticks

        # 60 seconds of 2s ticks at a 10s scan interval: 6-7 scans, not 30.
        expected = (ticks * cfg.refresh_s) / cfg.process_scan_interval_s
        assert len(enumerations) <= expected + 1
        assert len(enumerations) < ticks
