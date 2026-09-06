"""Every mood, every transition, every degraded-sensor case.

The engine is pure, so all of this runs on fabricated metrics with no machine
state involved.
"""

from dataclasses import replace

import pytest

from tamagoctl.config import Config, NetworkConfig, Thresholds
from tamagoctl.mood import DAMAGING, LABELS, PRIORITY, Mood, evaluate
from tests import factories as f
from tests.factories import CONFIG


class TestEachMood:
    def test_all_metrics_green_is_insufferably_content(self):
        verdict = evaluate(f.green(), CONFIG)
        assert verdict.mood is Mood.CONTENT
        assert verdict.label == "insufferably content"
        assert verdict.all_green
        assert verdict.killer is None

    def test_sustained_cpu_is_sweating(self):
        verdict = evaluate(f.sweating(), CONFIG)
        assert verdict.mood is Mood.SWEATING
        assert verdict.red == ("cpu",)

    def test_high_ram_is_bloated(self):
        assert evaluate(f.bloated(), CONFIG).mood is Mood.BLOATED

    def test_low_disk_is_constipated(self):
        assert evaluate(f.constipated(), CONFIG).mood is Mood.CONSTIPATED

    def test_high_latency_is_dizzy(self):
        assert evaluate(f.dizzy(), CONFIG).mood is Mood.DIZZY

    def test_packet_loss_is_dizzy_even_without_a_latency_number(self):
        metrics = f.green(packet_loss=True, latency_ms=None, loss_ratio=1.0)
        verdict = evaluate(metrics, CONFIG)
        assert verdict.mood is Mood.DIZZY
        assert "No answer from" in verdict.cause

    def test_low_battery_unplugged_is_hangry(self):
        assert evaluate(f.hangry(), CONFIG).mood is Mood.HANGRY

    def test_long_uptime_is_smug(self):
        verdict = evaluate(f.smug(), CONFIG)
        assert verdict.mood is Mood.SMUG
        assert verdict.all_green  # smug is an attitude, not an injury

    def test_every_mood_has_a_label(self):
        for mood in Mood:
            assert LABELS[mood]


class TestThresholdEdges:
    def test_cpu_at_threshold_but_not_yet_sustained_is_still_content(self):
        metrics = f.green(cpu_pct=99.0, cpu_hot_since=f.NOW - 59.0)
        verdict = evaluate(metrics, CONFIG)
        assert verdict.mood is Mood.CONTENT
        assert "not yet sustained" in verdict.check("cpu").detail

    def test_cpu_becomes_sweating_exactly_at_the_sustain_boundary(self):
        metrics = f.green(cpu_pct=86.0, cpu_hot_since=f.NOW - 60.0)
        assert evaluate(metrics, CONFIG).mood is Mood.SWEATING

    def test_cpu_spike_that_drops_below_threshold_is_not_sweating(self):
        """A long streak means nothing if the CPU is idle right now."""
        metrics = f.green(cpu_pct=12.0, cpu_hot_since=f.NOW - 600.0)
        assert evaluate(metrics, CONFIG).mood is Mood.CONTENT

    def test_ram_exactly_at_threshold_triggers(self):
        assert evaluate(f.green(ram_pct=90.0), CONFIG).mood is Mood.BLOATED

    def test_ram_just_under_threshold_does_not(self):
        assert evaluate(f.green(ram_pct=89.9), CONFIG).mood is Mood.CONTENT

    def test_disk_exactly_at_threshold_is_not_yet_constipated(self):
        assert evaluate(f.green(disk_free_pct=10.0), CONFIG).mood is Mood.CONTENT

    def test_disk_just_under_threshold_is(self):
        assert evaluate(f.green(disk_free_pct=9.9), CONFIG).mood is Mood.CONSTIPATED

    def test_latency_exactly_at_threshold_is_fine(self):
        assert evaluate(f.green(latency_ms=150.0), CONFIG).mood is Mood.CONTENT

    def test_latency_over_threshold_is_dizzy(self):
        assert evaluate(f.green(latency_ms=150.1), CONFIG).mood is Mood.DIZZY

    def test_battery_at_threshold_is_not_hangry(self):
        metrics = f.green(battery_pct=20.0, power_plugged=False)
        assert evaluate(metrics, CONFIG).mood is Mood.CONTENT

    def test_uptime_exactly_seven_days_is_smug(self):
        assert evaluate(f.green(uptime_s=7 * 86400), CONFIG).mood is Mood.SMUG

    def test_uptime_just_under_seven_days_is_not(self):
        assert evaluate(f.green(uptime_s=7 * 86400 - 1), CONFIG).mood is Mood.CONTENT

    def test_thresholds_are_configurable(self):
        strict = replace(CONFIG, thresholds=Thresholds(ram_pct=40.0))
        assert evaluate(f.green(ram_pct=41.0), strict).mood is Mood.BLOATED


class TestPriority:
    def test_low_battery_outranks_everything_else(self):
        metrics = f.hangry(ram_pct=99.0, disk_free_pct=1.0, latency_ms=900.0,
                           cpu_pct=99.0, cpu_hot_since=f.NOW - 300)
        verdict = evaluate(metrics, CONFIG)
        assert verdict.mood is Mood.HANGRY
        assert verdict.killer == "battery"

    def test_disk_outranks_ram_cpu_and_net(self):
        metrics = f.constipated(ram_pct=99.0, latency_ms=900.0,
                                cpu_pct=99.0, cpu_hot_since=f.NOW - 300)
        assert evaluate(metrics, CONFIG).mood is Mood.CONSTIPATED

    def test_ram_outranks_cpu_and_net(self):
        metrics = f.bloated(latency_ms=900.0, cpu_pct=99.0, cpu_hot_since=f.NOW - 300)
        assert evaluate(metrics, CONFIG).mood is Mood.BLOATED

    def test_cpu_outranks_net(self):
        assert evaluate(f.sweating(latency_ms=900.0), CONFIG).mood is Mood.SWEATING

    def test_any_red_metric_outranks_smug(self):
        """Nine days of uptime does not make a full disk charming."""
        assert evaluate(f.smug(disk_free_pct=2.0), CONFIG).mood is Mood.CONSTIPATED

    def test_red_metrics_are_reported_in_priority_order(self):
        metrics = f.hangry(ram_pct=99.0, disk_free_pct=1.0)
        assert evaluate(metrics, CONFIG).red == ("battery", "disk", "ram")

    def test_smug_never_appears_in_red(self):
        verdict = evaluate(f.smug(), CONFIG)
        assert "uptime" not in verdict.red
        assert verdict.triggered == ("uptime",)

    def test_priority_and_mood_maps_agree(self):
        from tamagoctl.mood import CHECK_MOODS

        assert set(PRIORITY) == set(CHECK_MOODS)
        assert DAMAGING < set(PRIORITY)


class TestDegradedSensors:
    def test_a_machine_with_no_battery_can_never_be_hangry(self):
        verdict = evaluate(f.headless(), CONFIG)
        assert verdict.mood is Mood.CONTENT
        assert verdict.check("battery").available is False
        assert "No battery" in verdict.check("battery").detail

    def test_low_battery_while_plugged_in_is_not_hangry(self):
        metrics = f.green(battery_pct=4.0, power_plugged=True)
        assert evaluate(metrics, CONFIG).mood is Mood.CONTENT

    def test_unknown_power_state_does_not_trigger_hangry(self):
        """psutil reports None for power_plugged on some platforms. Do not guess."""
        metrics = f.green(battery_pct=4.0, power_plugged=None)
        assert evaluate(metrics, CONFIG).mood is Mood.CONTENT

    def test_disabled_network_makes_dizzy_unreachable(self):
        offline = replace(CONFIG, network=NetworkConfig(enabled=False))
        metrics = f.green(latency_ms=5000.0, packet_loss=True)
        verdict = evaluate(metrics, offline)
        assert verdict.mood is Mood.CONTENT
        assert verdict.check("net").available is False

    def test_no_latency_reading_yet_is_not_dizzy(self):
        metrics = f.green(latency_ms=None, packet_loss=None)
        assert evaluate(metrics, CONFIG).mood is Mood.CONTENT

    def test_every_sensor_missing_still_yields_a_mood(self):
        blind = f.green(cpu_pct=None, ram_pct=None, disk_free_pct=None,
                        latency_ms=None, packet_loss=None, battery_pct=None,
                        power_plugged=None, uptime_s=None)
        verdict = evaluate(blind, CONFIG)
        assert verdict.mood is Mood.CONTENT
        assert set(verdict.unavailable) == set(PRIORITY)

    def test_unavailable_checks_are_never_red(self):
        blind = f.green(cpu_pct=None, ram_pct=None, disk_free_pct=None,
                        latency_ms=None, packet_loss=None, battery_pct=None)
        assert evaluate(blind, CONFIG).red == ()


class TestCause:
    def test_cause_names_the_worst_metric_with_a_number(self):
        verdict = evaluate(f.constipated(), CONFIG)
        assert verdict.killer == "disk"
        assert "4.0% free" in verdict.cause

    def test_cause_is_none_when_nothing_is_wrong(self):
        assert evaluate(f.green(), CONFIG).cause is None

    def test_cause_reports_cpu_sustain_duration(self):
        verdict = evaluate(f.sweating(), CONFIG)
        assert "sustained for 1m 30s" in verdict.cause

    @pytest.mark.parametrize("key", sorted(DAMAGING))
    def test_every_damaging_check_can_produce_a_cause(self, key):
        maker = {"cpu": f.sweating, "ram": f.bloated, "disk": f.constipated,
                 "net": f.dizzy, "battery": f.hangry}[key]
        verdict = evaluate(maker(), CONFIG)
        assert verdict.killer == key
        assert verdict.cause
