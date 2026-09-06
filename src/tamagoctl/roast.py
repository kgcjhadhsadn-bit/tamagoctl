"""Picking the single worst-behaved process. Selection is pure; the insults
live in comments.py.
"""

from __future__ import annotations

from dataclasses import dataclass

from tamagoctl.metrics import ProcInfo


@dataclass(frozen=True)
class Offender:
    proc: ProcInfo
    cpu_share: float  # whole-machine CPU percentage
    ram_share: float
    score: float
    guilty_of: str  # "cpu", "ram" or "both"


def cpu_share(proc: ProcInfo, cpu_count: int) -> float:
    """psutil reports per-core percentages; normalise to whole-machine share."""
    cores = max(1, cpu_count)
    return (proc.cpu_pct or 0.0) / cores


def score(proc: ProcInfo, cpu_count: int) -> float:
    """CPU and RAM share weighted equally. Both are ways of being rude."""
    return cpu_share(proc, cpu_count) + (proc.rss_pct or 0.0)


def classify(cpu: float, ram: float) -> str:
    if cpu >= 5.0 and ram >= 5.0:
        return "both"
    return "cpu" if cpu >= ram else "ram"


def pick_worst(
    procs: list[ProcInfo], cpu_count: int = 1, exclude_pids: set[int] | None = None
) -> Offender | None:
    """The single worst offender. Ties broken by PID so the answer is stable."""
    exclude = exclude_pids or set()
    candidates = [p for p in procs if p.pid not in exclude and p.pid > 0]
    if not candidates:
        return None
    best = max(candidates, key=lambda p: (score(p, cpu_count), -p.pid))
    cpu = cpu_share(best, cpu_count)
    ram = best.rss_pct or 0.0
    return Offender(
        proc=best, cpu_share=cpu, ram_share=ram, score=cpu + ram,
        guilty_of=classify(cpu, ram),
    )


def rank(procs: list[ProcInfo], cpu_count: int = 1, limit: int = 5) -> list[ProcInfo]:
    """The runners-up, for context under the roast."""
    return sorted(procs, key=lambda p: score(p, cpu_count), reverse=True)[:limit]
