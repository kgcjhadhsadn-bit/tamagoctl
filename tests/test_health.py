"""Health decay, regeneration and death. Also pure - dt is an argument."""

from dataclasses import replace

import pytest

from tamagoctl.config import Config, HealthRules
from tamagoctl.health import apply_health, bar, health_delta, step, time_to_live
from tamagoctl.mood import Mood, evaluate
from tests import factories as f
from tests.factories import CONFIG

RULES = CONFIG.health


class TestDecay:
    def test_one_red_metric_drains_at_the_configured_rate(self):
        tick = step(f.bloated(), health=100.0, dt=10.0, config=CONFIG)
        assert tick.delta == pytest.approx(-RULES.decay_per_red_per_s * 10.0)
        assert tick.health == pytest.approx(99.5)

    def test_decay_scales_with_the_number_of_red_metrics(self):
        one = step(f.bloated(), 100.0, 10.0, CONFIG)
        three = step(f.hangry(ram_pct=99.0, disk_free_pct=1.0), 100.0, 10.0, CONFIG)
        assert three.delta == pytest.approx(one.delta * 3)

    def test_smug_does_not_damage_health(self):
        """Uptime is a personality trait. It must not kill the pet."""
        tick = step(f.smug(), 80.0, 60.0, CONFIG)
        assert tick.verdict.mood is Mood.SMUG
        assert tick.delta > 0  # in fact it regenerates, because nothing is red

    def test_health_never_goes_below_zero(self):
        tick = step(f.bloated(), 0.4, dt=1000.0, config=CONFIG)
        assert tick.health == 0.0

    def test_a_red_metric_reports_as_dying(self):
        assert step(f.sweating(), 50.0, 2.0, CONFIG).dying is True


class TestRegen:
    def test_all_green_regenerates_slowly(self):
        tick = step(f.green(), 50.0, 100.0, CONFIG)
        assert tick.delta == pytest.approx(RULES.regen_per_s * 100.0)

    def test_regen_is_slower_than_decay(self):
        """Recovering must cost more than breaking. Otherwise nothing matters."""
        assert RULES.regen_per_s < RULES.decay_per_red_per_s

    def test_health_is_capped_at_max(self):
        tick = step(f.green(), 99.9, dt=10_000.0, config=CONFIG)
        assert tick.health == RULES.max_health

    def test_a_machine_with_no_readable_sensors_regenerates(self):
        blind = f.green(cpu_pct=None, ram_pct=None, disk_free_pct=None,
                        latency_ms=None, packet_loss=None, battery_pct=None)
        assert step(blind, 50.0, 10.0, CONFIG).delta > 0

    def test_zero_dt_changes_nothing(self):
        assert step(f.bloated(), 50.0, 0.0, CONFIG).delta == 0.0

    def test_negative_dt_changes_nothing(self):
        """Clock jumps backwards. NTP, suspend, timezones. Do not resurrect anyone."""
        assert health_delta(evaluate(f.green(), CONFIG), -30.0, CONFIG) == 0.0


class TestDeath:
    def test_health_reaching_zero_is_death(self):
        tick = step(f.bloated(), health=0.5, dt=20.0, config=CONFIG)
        assert tick.health == 0.0
        assert tick.died is True
        assert tick.mood is Mood.DEAD

    def test_death_names_the_metric_that_did_it(self):
        tick = step(f.constipated(), 0.1, 10.0, CONFIG)
        assert tick.died is True
        assert tick.verdict.killer == "disk"
        assert "free on /" in tick.cause

    def test_the_worst_metric_gets_the_blame_when_several_are_red(self):
        metrics = f.hangry(disk_free_pct=1.0, ram_pct=99.0)
        tick = step(metrics, 0.1, 10.0, CONFIG)
        assert tick.verdict.killer == "battery"

    def test_death_fires_exactly_once(self):
        """`died` is the transition, not the state. It must not re-fire."""
        first = step(f.bloated(), 0.1, 10.0, CONFIG)
        assert first.died is True
        second = step(f.bloated(), first.health, 10.0, CONFIG)
        assert second.died is False

    def test_the_dead_do_not_decay_further(self):
        tick = step(f.hangry(disk_free_pct=1.0), 0.0, 10_000.0, CONFIG)
        assert tick.health == 0.0
        assert tick.delta == 0.0

    def test_the_dead_do_not_heal(self):
        """Green metrics do not bring anyone back. That is what `revive` is for."""
        tick = step(f.green(), 0.0, 10_000.0, CONFIG)
        assert tick.health == 0.0
        assert tick.died is False

    def test_death_is_reachable_in_finite_time_from_full_health(self):
        health, elapsed = RULES.max_health, 0.0
        while health > 0 and elapsed < 86400:
            health = step(f.bloated(), health, 2.0, CONFIG).health
            elapsed += 2.0
        assert health == 0.0
        assert elapsed == pytest.approx(2000.0, abs=2.0)  # ~33 minutes


class TestTimeToLive:
    def test_ttl_is_none_when_healthy(self):
        assert time_to_live(100.0, evaluate(f.green(), CONFIG), CONFIG) is None

    def test_ttl_shrinks_as_more_metrics_go_red(self):
        one = time_to_live(100.0, evaluate(f.bloated(), CONFIG), CONFIG)
        two = time_to_live(100.0, evaluate(f.bloated(disk_free_pct=1.0), CONFIG), CONFIG)
        assert two == pytest.approx(one / 2)

    def test_ttl_is_none_once_dead(self):
        assert time_to_live(0.0, evaluate(f.bloated(), CONFIG), CONFIG) is None


class TestCustomRules:
    def test_decay_rate_is_configurable(self):
        brutal = replace(CONFIG, health=HealthRules(decay_per_red_per_s=10.0))
        assert step(f.bloated(), 100.0, 10.0, brutal).health == 0.0

    def test_a_pacifist_config_never_kills(self):
        gentle = replace(CONFIG, health=HealthRules(decay_per_red_per_s=0.0))
        assert step(f.hangry(disk_free_pct=1.0), 1.0, 100_000.0, gentle).died is False


class TestBar:
    def test_full_and_empty(self):
        assert bar(100.0, width=10) == "#" * 10
        assert bar(0.0, width=10) == "-" * 10

    def test_a_barely_alive_pet_still_shows_one_block(self):
        assert bar(0.2, width=20).startswith("#")

    def test_bar_width_is_respected(self):
        assert len(bar(37.0, width=33)) == 33

    def test_clamps_out_of_range_values(self):
        assert bar(500.0, width=10) == "#" * 10
        assert bar(-5.0, width=10) == "-" * 10


class TestApplyHealth:
    @pytest.mark.parametrize(
        "start,delta,expected", [(50, 10, 60), (50, -60, 0), (95, 20, 100), (0, -5, 0)]
    )
    def test_clamping(self, start, delta, expected):
        assert apply_health(float(start), float(delta), CONFIG) == expected
