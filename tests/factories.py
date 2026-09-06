"""Fabricated metrics for the pure-engine tests.

`green()` is a machine with nothing wrong with it; every helper below breaks
exactly one thing, which is what lets the priority tests be unambiguous.
"""

from dataclasses import replace

from tamagoctl.config import Config
from tamagoctl.metrics import Metrics, ProcInfo

NOW = 1_700_000_000.0
CONFIG = Config()


def green(**overrides) -> Metrics:
    """Everything nominal: quiet CPU, roomy RAM, empty disk, fast net, charged."""
    base = Metrics(
        now=NOW,
        cpu_pct=7.0,
        cpu_hot_since=None,
        ram_pct=41.0,
        ram_used_bytes=6 * 1024**3,
        ram_total_bytes=16 * 1024**3,
        disk_free_pct=62.0,
        disk_free_bytes=300 * 1024**3,
        disk_path="/",
        latency_ms=12.0,
        packet_loss=False,
        loss_ratio=0.0,
        battery_pct=96.0,
        power_plugged=True,
        battery_secs_left=9000,
        uptime_s=3600.0,
        proc_count=310,
        browser_procs={"Chrome": 47},
        top_cpu_proc=ProcInfo(pid=4821, name="Chrome Helper (Renderer)", cpu_pct=142.3,
                              rss_bytes=2 * 1024**3, rss_pct=12.5,
                              username="you", cmdline="/opt/chrome --type=renderer",
                              created_at=NOW - 7200),
        top_ram_proc=ProcInfo(pid=901, name="java", cpu_pct=3.0,
                              rss_bytes=5 * 1024**3, rss_pct=31.0,
                              username="you", cmdline="java -Xmx8g -jar thing.jar",
                              created_at=NOW - 90000),
        self_cpu_pct=0.3,
        self_rss_bytes=18 * 1024**2,
    )
    return replace(base, **overrides) if overrides else base


def sweating(**kw) -> Metrics:
    """CPU over threshold and sustained past the 60s grace period."""
    return green(cpu_pct=93.0, cpu_hot_since=NOW - 90.0, **kw)


def bloated(**kw) -> Metrics:
    return green(ram_pct=94.0, **kw)


def constipated(**kw) -> Metrics:
    return green(disk_free_pct=4.0, disk_free_bytes=2 * 1024**3, **kw)


def dizzy(**kw) -> Metrics:
    return green(latency_ms=312.0, **kw)


def hangry(**kw) -> Metrics:
    return green(battery_pct=11.0, power_plugged=False, **kw)


def smug(**kw) -> Metrics:
    return green(uptime_s=9.5 * 86400, **kw)


def headless(**kw) -> Metrics:
    """A server: no battery, no browser, nothing to be smug about yet."""
    return green(battery_pct=None, power_plugged=None, browser_procs={}, **kw)
