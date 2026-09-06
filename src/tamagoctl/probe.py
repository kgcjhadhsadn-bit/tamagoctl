"""The one network call: a latency/loss check against a configurable host.

TCP connect rather than ICMP - raw sockets need root on Linux, and parsing
`ping` output differs on every platform this is supposed to run on. Runs on its
own slow cadence in a daemon thread so the 2s render tick never blocks on it.
"""

from __future__ import annotations

import socket
import statistics
import threading
import time
from dataclasses import dataclass

from tamagoctl.config import NetworkConfig


@dataclass(frozen=True)
class LatencyResult:
    latency_ms: float | None
    packet_loss: bool | None
    loss_ratio: float | None
    at: float


UNKNOWN = LatencyResult(None, None, None, 0.0)


def probe_once(cfg: NetworkConfig, now: float | None = None) -> LatencyResult:
    """Synchronous probe. Returns UNKNOWN-ish values when disabled or unreachable."""
    now = time.time() if now is None else now
    if not cfg.enabled:
        return LatencyResult(None, None, None, now)

    samples: list[float] = []
    failures = 0
    for _ in range(max(1, cfg.probes)):
        start = time.perf_counter()
        try:
            with socket.create_connection((cfg.host, cfg.port), timeout=cfg.timeout_s):
                samples.append((time.perf_counter() - start) * 1000.0)
        except OSError:
            failures += 1

    total = failures + len(samples)
    loss_ratio = failures / total if total else None
    if not samples:
        # Everything failed. That is packet loss as far as the pet is concerned,
        # and we have no latency number to report.
        return LatencyResult(None, True, loss_ratio, now)
    return LatencyResult(statistics.median(samples), failures > 0, loss_ratio, now)


class LatencyProbe:
    """Background poller. Never raises; the worst it does is report nothing."""

    def __init__(self, cfg: NetworkConfig):
        self._cfg = cfg
        self._result = LatencyResult(None, None, None, 0.0)
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    @property
    def enabled(self) -> bool:
        return self._cfg.enabled

    def read(self) -> LatencyResult:
        with self._lock:
            return self._result

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                result = probe_once(self._cfg)
            except Exception:  # pragma: no cover - defensive
                result = LatencyResult(None, None, None, time.time())
            with self._lock:
                self._result = result
            self._stop.wait(max(1.0, self._cfg.interval_s))

    def start(self) -> "LatencyProbe":
        if not self._cfg.enabled or self._thread is not None:
            return self
        self._thread = threading.Thread(
            target=self._run, name="tamagoctl-probe", daemon=True
        )
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        thread, self._thread = self._thread, None
        if thread is not None:
            thread.join(timeout=2.0)

    def __enter__(self) -> "LatencyProbe":
        return self.start()

    def __exit__(self, *exc) -> None:
        self.stop()
