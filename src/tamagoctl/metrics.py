"""All system I/O lives here. Nothing below this module touches psutil.

Every reading is Optional. A field of ``None`` means "this box cannot tell me",
which is a normal state - laptops have batteries, servers do not, containers lie
about both - and never an error.
"""

from __future__ import annotations

import os
import time
from dataclasses import dataclass, field, replace
from pathlib import Path

import psutil

from tamagoctl.config import Config, NetworkConfig
from tamagoctl.probe import LatencyProbe, LatencyResult, probe_once

# Process names we are prepared to be rude about, mapped to a display name.
BROWSER_HINTS = {
    "chrome": "Chrome",
    "chromium": "Chromium",
    "firefox": "Firefox",
    "safari": "Safari",
    "msedge": "Edge",
    "brave": "Brave",
    "opera": "Opera",
    "vivaldi": "Vivaldi",
    "arc": "Arc",
}


@dataclass(frozen=True)
class ProcInfo:
    pid: int
    name: str
    cpu_pct: float
    rss_bytes: int | None = None
    rss_pct: float | None = None
    username: str | None = None
    cmdline: str | None = None
    created_at: float | None = None


@dataclass(frozen=True)
class Metrics:
    """One sample. Frozen so the mood engine cannot accidentally mutate history."""

    now: float
    cpu_pct: float | None = None
    cpu_hot_since: float | None = None
    ram_pct: float | None = None
    ram_used_bytes: int | None = None
    ram_total_bytes: int | None = None
    swap_pct: float | None = None
    disk_free_pct: float | None = None
    disk_free_bytes: int | None = None
    disk_path: str | None = None
    latency_ms: float | None = None
    packet_loss: bool | None = None
    loss_ratio: float | None = None
    battery_pct: float | None = None
    power_plugged: bool | None = None
    battery_secs_left: int | None = None
    uptime_s: float | None = None
    load1: float | None = None
    proc_count: int | None = None
    browser_procs: dict[str, int] = field(default_factory=dict)
    top_cpu_proc: ProcInfo | None = None
    top_ram_proc: ProcInfo | None = None
    self_cpu_pct: float = 0.0
    self_rss_bytes: int | None = None

    @property
    def cpu_hot_for(self) -> float:
        """Seconds the CPU has been above threshold, 0.0 if it is not."""
        if self.cpu_hot_since is None:
            return 0.0
        return max(0.0, self.now - self.cpu_hot_since)


def _safe(fn, default=None):
    """psutil raises a zoo of exceptions on exotic platforms. None is fine."""
    try:
        return fn()
    except Exception:
        return default


class CpuSustainTracker:
    """Remembers when the CPU first went hot, so `sustained 60s` means something.

    Stateful on purpose, and deliberately outside the pure mood engine: it hands
    the engine a plain timestamp.
    """

    def __init__(self, hot_since: float | None = None):
        self.hot_since = hot_since

    def update(self, cpu_pct: float | None, threshold: float, now: float) -> float | None:
        if cpu_pct is None:
            return self.hot_since  # unknown is not "cool"; hold the existing streak
        if cpu_pct >= threshold:
            if self.hot_since is None:
                self.hot_since = now
        else:
            self.hot_since = None
        return self.hot_since


def disk_target() -> str:
    """The filesystem we care about: whichever one holds the user's home."""
    try:
        return str(Path.home().anchor or os.sep)
    except Exception:
        return os.sep


class MetricsCollector:
    """Samples the machine. Cheap on every tick, expensive only occasionally."""

    def __init__(
        self,
        config: Config,
        probe: LatencyProbe | None = None,
        hot_since: float | None = None,
    ):
        self.config = config
        self.tracker = CpuSustainTracker(hot_since)
        self.probe = probe if probe is not None else LatencyProbe(config.network)
        self._self_proc = _safe(psutil.Process)
        self._disk_path = disk_target()
        self._proc_cache: dict = {}
        self._proc_cache_at = 0.0
        self._primed = False
        # First call to cpu_percent(interval=None) always returns 0.0; burn it now
        # so the first real tick is honest.
        _safe(lambda: psutil.cpu_percent(interval=None))
        if self._self_proc is not None:
            _safe(self._self_proc.cpu_percent)

    def start(self) -> "MetricsCollector":
        self.probe.start()
        return self

    def stop(self) -> None:
        self.probe.stop()

    # -- expensive path -----------------------------------------------------

    def _scan_processes(self, now: float) -> dict:
        """Enumerate processes. Runs every process_scan_interval_s, not every tick."""
        if self._proc_cache and now - self._proc_cache_at < self.config.process_scan_interval_s:
            return self._proc_cache

        procs: list[ProcInfo] = []
        browsers: dict[str, int] = {}
        attrs = ["pid", "name", "cpu_percent", "memory_info", "memory_percent", "username"]
        try:
            for proc in psutil.process_iter(attrs=attrs):
                info = proc.info
                name = info.get("name") or "?"
                mem = info.get("memory_info")
                procs.append(
                    ProcInfo(
                        pid=info.get("pid") or -1,
                        name=name,
                        cpu_pct=info.get("cpu_percent") or 0.0,
                        rss_bytes=getattr(mem, "rss", None),
                        rss_pct=info.get("memory_percent"),
                        username=info.get("username"),
                    )
                )
                lowered = name.lower()
                for hint, label in BROWSER_HINTS.items():
                    if hint in lowered:
                        browsers[label] = browsers.get(label, 0) + 1
                        break
        except Exception:
            pass

        cache = {
            "count": len(procs) or None,
            "browsers": browsers,
            "top_cpu": max(procs, key=lambda p: p.cpu_pct, default=None),
            "top_ram": max(procs, key=lambda p: (p.rss_bytes or 0), default=None),
        }
        if cache["count"] is None:
            cache["count"] = _safe(lambda: len(psutil.pids()))
        self._proc_cache = cache
        self._proc_cache_at = now
        return cache

    # -- cheap path ---------------------------------------------------------

    def sample(self, now: float | None = None) -> Metrics:
        now = time.time() if now is None else now
        th = self.config.thresholds

        cpu = _safe(lambda: psutil.cpu_percent(interval=None))
        hot_since = self.tracker.update(cpu, th.cpu_pct, now)

        vmem = _safe(psutil.virtual_memory)
        smem = _safe(psutil.swap_memory)
        disk = _safe(lambda: psutil.disk_usage(self._disk_path))
        battery = _safe(lambda: getattr(psutil, "sensors_battery", lambda: None)())
        boot = _safe(psutil.boot_time)
        load = _safe(lambda: os.getloadavg()[0]) if hasattr(os, "getloadavg") else None

        latency: LatencyResult = self.probe.read()
        procs = self._scan_processes(now)

        self_cpu = 0.0
        self_rss = None
        if self._self_proc is not None:
            raw = _safe(self._self_proc.cpu_percent, 0.0) or 0.0
            # Normalise to whole-machine percentage, matching cpu_percent()'s scale.
            cores = _safe(psutil.cpu_count, 1) or 1
            self_cpu = raw / cores
            mem = _safe(self._self_proc.memory_info)
            self_rss = getattr(mem, "rss", None)

        secs_left = None
        if battery is not None:
            raw_secs = getattr(battery, "secsleft", None)
            if isinstance(raw_secs, int) and raw_secs >= 0:
                secs_left = raw_secs

        return Metrics(
            now=now,
            cpu_pct=cpu,
            cpu_hot_since=hot_since,
            ram_pct=getattr(vmem, "percent", None),
            ram_used_bytes=getattr(vmem, "used", None),
            ram_total_bytes=getattr(vmem, "total", None),
            swap_pct=getattr(smem, "percent", None),
            disk_free_pct=(
                100.0 - disk.percent if disk is not None and disk.percent is not None else None
            ),
            disk_free_bytes=getattr(disk, "free", None),
            disk_path=self._disk_path,
            latency_ms=latency.latency_ms,
            packet_loss=latency.packet_loss,
            loss_ratio=latency.loss_ratio,
            battery_pct=getattr(battery, "percent", None),
            power_plugged=getattr(battery, "power_plugged", None),
            battery_secs_left=secs_left,
            uptime_s=(now - boot) if boot else None,
            load1=load,
            proc_count=procs.get("count"),
            browser_procs=dict(procs.get("browsers") or {}),
            top_cpu_proc=procs.get("top_cpu"),
            top_ram_proc=procs.get("top_ram"),
            self_cpu_pct=self_cpu,
            self_rss_bytes=self_rss,
        )


def one_shot(config: Config) -> Metrics:
    """A single synchronous sample including a blocking latency probe.

    Used by `status` and `top`, which have no render loop to keep responsive.
    """
    collector = MetricsCollector(config, probe=_NullProbe())
    time.sleep(0.12)  # give cpu_percent a delta worth reporting
    metrics = collector.sample()
    if config.network.enabled:
        result = probe_once(config.network)
        metrics = replace(
            metrics,
            latency_ms=result.latency_ms,
            packet_loss=result.packet_loss,
            loss_ratio=result.loss_ratio,
        )
    return metrics


class _NullProbe(LatencyProbe):
    """A probe that never starts a thread."""

    def __init__(self):
        super().__init__(NetworkConfig(enabled=False))


def sample_processes(interval: float = 0.4, cmdline: bool = True) -> list[ProcInfo]:
    """Two-pass CPU sample of every process. Only `top` pays for this.

    psutil's per-process cpu_percent needs two readings separated by time; a
    single pass would report 0.0 for everything and blame the wrong process.
    """
    watched = []
    for proc in psutil.process_iter():
        try:
            proc.cpu_percent()  # prime the delta
            watched.append(proc)
        except Exception:
            continue

    time.sleep(max(0.05, interval))

    out: list[ProcInfo] = []
    for proc in watched:
        try:
            with proc.oneshot():
                mem = _safe(proc.memory_info)
                args = None
                if cmdline:
                    parts = _safe(proc.cmdline) or []
                    args = " ".join(parts).strip() or None
                out.append(
                    ProcInfo(
                        pid=proc.pid,
                        name=_safe(proc.name) or "?",
                        cpu_pct=_safe(proc.cpu_percent, 0.0) or 0.0,
                        rss_bytes=getattr(mem, "rss", None),
                        rss_pct=_safe(proc.memory_percent),
                        username=_safe(proc.username),
                        cmdline=args,
                        created_at=_safe(proc.create_time),
                    )
                )
        except Exception:
            continue  # processes die mid-scan; that is not our problem
    return out
