"""The metrics layer must degrade, never crash. Containers, VMs and servers all
lie about something."""

import time
from dataclasses import replace

import pytest

from tamagoctl import metrics as metrics_mod
from tamagoctl.config import Config, NetworkConfig
from tamagoctl.metrics import CpuSustainTracker, MetricsCollector, one_shot
from tamagoctl.probe import LatencyProbe, probe_once

OFFLINE = replace(Config(), network=NetworkConfig(enabled=False))


class TestCpuSustainTracker:
    def test_records_when_cpu_first_goes_hot(self):
        tracker = CpuSustainTracker()
        assert tracker.update(50.0, 85.0, 1000.0) is None
        assert tracker.update(90.0, 85.0, 1010.0) == 1010.0

    def test_streak_start_does_not_move_while_hot(self):
        tracker = CpuSustainTracker()
        tracker.update(90.0, 85.0, 1000.0)
        assert tracker.update(99.0, 85.0, 1060.0) == 1000.0

    def test_cooling_off_resets_the_streak(self):
        tracker = CpuSustainTracker()
        tracker.update(90.0, 85.0, 1000.0)
        assert tracker.update(10.0, 85.0, 1030.0) is None

    def test_unknown_cpu_holds_the_streak_rather_than_clearing_it(self):
        tracker = CpuSustainTracker(hot_since=1000.0)
        assert tracker.update(None, 85.0, 1050.0) == 1000.0

    def test_threshold_is_inclusive(self):
        tracker = CpuSustainTracker()
        assert tracker.update(85.0, 85.0, 5.0) == 5.0


class TestGracefulDegradation:
    @pytest.fixture
    def hostile_psutil(self, monkeypatch):
        """A machine where every single sensor is broken or absent."""

        def boom(*args, **kwargs):
            raise RuntimeError("this platform does not do that")

        for name in (
            "cpu_percent",
            "virtual_memory",
            "swap_memory",
            "disk_usage",
            "boot_time",
            "pids",
            "process_iter",
            "cpu_count",
            "Process",
            "sensors_battery",
        ):
            monkeypatch.setattr(metrics_mod.psutil, name, boom, raising=False)

    def test_sampling_a_hostile_machine_yields_nones_not_exceptions(self, hostile_psutil):
        sample = MetricsCollector(OFFLINE).sample(now=1000.0)
        assert sample.cpu_pct is None
        assert sample.ram_pct is None
        assert sample.disk_free_pct is None
        assert sample.uptime_s is None
        assert sample.battery_pct is None
        assert sample.proc_count is None
        assert sample.top_cpu_proc is None
        assert sample.self_cpu_pct == 0.0

    def test_missing_sensors_battery_attribute_is_survivable(self, monkeypatch):
        """Some platforms do not ship sensors_battery at all."""
        monkeypatch.delattr(metrics_mod.psutil, "sensors_battery", raising=False)
        sample = MetricsCollector(OFFLINE).sample(now=1000.0)
        assert sample.battery_pct is None
        assert sample.power_plugged is None

    def test_unknown_battery_secsleft_sentinel_is_dropped(self, monkeypatch):
        class Battery:
            percent = 55.0
            power_plugged = False
            secsleft = -2  # psutil's POWER_TIME_UNKNOWN

        monkeypatch.setattr(
            metrics_mod.psutil, "sensors_battery", lambda: Battery(), raising=False
        )
        sample = MetricsCollector(OFFLINE).sample(now=1000.0)
        assert sample.battery_pct == 55.0
        assert sample.battery_secs_left is None


class TestRealSample:
    def test_a_real_sample_has_the_basics(self):
        sample = one_shot(OFFLINE)
        assert sample.ram_pct is not None
        assert sample.uptime_s is not None and sample.uptime_s > 0
        assert sample.disk_free_pct is not None
        assert 0.0 <= sample.disk_free_pct <= 100.0

    def test_cpu_hot_for_is_zero_when_not_hot(self):
        sample = one_shot(OFFLINE)
        assert sample.cpu_hot_for == 0.0

    def test_cpu_hot_for_measures_the_streak(self, monkeypatch):
        monkeypatch.setattr(metrics_mod.psutil, "cpu_percent", lambda **kw: 95.0)
        collector = MetricsCollector(OFFLINE, hot_since=1000.0)
        sample = collector.sample(now=1090.0)
        assert sample.cpu_hot_for == pytest.approx(90.0, abs=1.0)

    def test_streak_clears_once_the_cpu_calms_down(self, monkeypatch):
        monkeypatch.setattr(metrics_mod.psutil, "cpu_percent", lambda **kw: 3.0)
        collector = MetricsCollector(OFFLINE, hot_since=1000.0)
        assert collector.sample(now=1090.0).cpu_hot_for == 0.0

    def test_process_scan_is_cached_between_ticks(self):
        """Process enumeration is the expensive syscall; it must not run per tick."""
        collector = MetricsCollector(OFFLINE)
        collector.sample(now=1000.0)
        first = collector._proc_cache_at
        collector.sample(now=1001.0)  # inside process_scan_interval_s
        assert collector._proc_cache_at == first
        collector.sample(now=1000.0 + OFFLINE.process_scan_interval_s + 1)
        assert collector._proc_cache_at != first


class TestLatencyProbe:
    def test_disabled_probe_reports_nothing_and_never_connects(self):
        result = probe_once(NetworkConfig(enabled=False), now=5.0)
        assert result.latency_ms is None
        assert result.packet_loss is None

    def test_unreachable_host_counts_as_loss(self):
        # Reserved TEST-NET-1 address; guaranteed not to answer.
        cfg = NetworkConfig(host="192.0.2.1", port=9, probes=1, timeout_s=0.15)
        result = probe_once(cfg)
        assert result.packet_loss is True
        assert result.latency_ms is None
        assert result.loss_ratio == 1.0

    def test_disabled_probe_starts_no_thread(self):
        probe = LatencyProbe(NetworkConfig(enabled=False)).start()
        assert probe._thread is None
        probe.stop()

    def test_probe_read_before_first_result_is_blank(self):
        probe = LatencyProbe(NetworkConfig(enabled=True))
        assert probe.read().latency_ms is None
