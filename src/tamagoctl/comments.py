"""The commentary engine: facts, weighted picking with a no-repeat window, and
the narrators.

sass_level 0 is the FactualNarrator - a plain metric log, because someone who
genuinely wanted a system monitor has to be able to use this. Levels 1-3 draw
from the banks in lines.py.
"""

from __future__ import annotations

import random
import time
from typing import Protocol, Sequence

from tamagoctl import fmt, lines as banks
from tamagoctl.config import Config
from tamagoctl.health import Tick
from tamagoctl.lines import Line
from tamagoctl.metrics import Metrics, ProcInfo
from tamagoctl.mood import Mood
from tamagoctl.state import PetState

SUMMARY_INTERVAL_S = 30.0
NO_REPEAT_WINDOW = 10

# How often a sassy pet says something unprompted. Mood changes always speak.
COMMENT_INTERVAL_S = {1: 26.0, 2: 14.0, 3: 9.0}

# Chance a scheduled comment is about the predecessor instead of the metrics.
PREDECESSOR_CHANCE = 0.06
PREDECESSOR_GREETING_CHANCE = 0.4

ABSENCE_TIERS = (
    (30 * 86400, banks.ABSENCE_LONG),
    (7 * 86400, banks.ABSENCE_MEDIUM),
    (3 * 86400, banks.ABSENCE_SHORT),
)


class Narrator(Protocol):
    """What the session needs from whoever is doing the talking."""

    def greeting(self, pet: PetState, metrics: Metrics, cfg: Config) -> list[str]:
        ...

    def observe(self, pet: PetState, tick: Tick, metrics: Metrics, cfg: Config) -> str | None:
        ...

    def self_report(self, metrics: Metrics, cfg: Config) -> str:
        ...

    def eulogy(self, pet: PetState, tick: Tick, metrics: Metrics, cfg: Config) -> str:
        ...


# --------------------------------------------------------------------------
# facts
# --------------------------------------------------------------------------


class _Safe(dict):
    """A missing field renders as '?' rather than taking down the TUI."""

    def __missing__(self, key: str) -> str:
        return "?"


def render(line: Line, facts: dict) -> str:
    return line.text.format_map(_Safe(facts))


def facts_for(pet: PetState, tick: Tick, metrics: Metrics, cfg: Config) -> dict:
    """Everything a template may reference. A None means 'do not use this line'."""
    m = metrics
    facts: dict[str, object] = {
        "name": pet.name,
        "gen": pet.generation,
        "health": f"{tick.health:.0f}",
        "age": fmt.duration(pet.age_s(m.now)),
        "away": fmt.duration(pet.absence_s(m.now)),
        "cause": tick.verdict.cause,
        "killer": tick.verdict.killer,
        "host": cfg.network.host if cfg.network.enabled else None,
        "self_cpu": f"{m.self_cpu_pct:.2f}%",
        "self_rss": fmt.size(m.self_rss_bytes) if m.self_rss_bytes else None,
        "procs": m.proc_count,
    }

    if m.cpu_pct is not None:
        facts["cpu"] = f"{m.cpu_pct:.0f}%"
        facts["cpu_hot"] = fmt.duration(m.cpu_hot_for) if m.cpu_hot_since else None
    if m.ram_pct is not None:
        facts["ram"] = f"{m.ram_pct:.0f}%"
    if m.ram_used_bytes and m.ram_total_bytes:
        facts["ram_used"] = fmt.size(m.ram_used_bytes)
        facts["ram_total"] = fmt.size(m.ram_total_bytes)
        facts["ram_free"] = fmt.size(max(0, m.ram_total_bytes - m.ram_used_bytes))
    if m.disk_free_pct is not None:
        facts["disk_free"] = f"{m.disk_free_pct:.0f}%"
        facts["disk_path"] = m.disk_path
    if m.disk_free_bytes is not None:
        facts["disk_free_bytes"] = fmt.size(m.disk_free_bytes)
    if m.latency_ms is not None:
        facts["latency"] = f"{m.latency_ms:.0f}ms"
    if m.battery_pct is not None:
        facts["battery"] = f"{m.battery_pct:.0f}%"
    if m.battery_secs_left:
        facts["battery_left"] = fmt.duration(m.battery_secs_left)
    if m.uptime_s is not None:
        facts["uptime"] = fmt.duration(m.uptime_s)
        # Floored, not rounded: "10 days" next to "9d 12h" reads like a bug.
        facts["uptime_days"] = f"{int(fmt.days(m.uptime_s))}"

    if m.browser_procs:
        top_browser = max(m.browser_procs.items(), key=lambda kv: kv[1])
        facts["browser"] = top_browser[0]
        chrome = m.browser_procs.get("Chrome") or m.browser_procs.get("Chromium")
        if chrome:
            facts["chrome"] = chrome
    if m.top_cpu_proc and m.top_cpu_proc.cpu_pct > 0:
        facts["top_name"] = m.top_cpu_proc.name
        facts["top_pid"] = m.top_cpu_proc.pid
        facts["top_cpu"] = f"{m.top_cpu_proc.cpu_pct:.0f}%"
    if m.top_ram_proc and m.top_ram_proc.rss_pct:
        facts["top_ram_name"] = m.top_ram_proc.name
        facts["top_ram_pid"] = m.top_ram_proc.pid
        facts["top_ram"] = f"{m.top_ram_proc.rss_pct:.0f}%"

    pred = pet.predecessor or {}
    if pred.get("name"):
        facts["pred_name"] = pred["name"]
        facts["pred_gen"] = pred.get("generation")
        facts["pred_cause"] = pred.get("cause")
        if pred.get("lifespan_s"):
            facts["pred_lifespan"] = fmt.duration(pred["lifespan_s"])

    return {k: v for k, v in facts.items() if v is not None}


def roast_facts(offender, now: float | None = None) -> dict:
    """Facts for `tamagoctl top`, about one specific process."""
    proc: ProcInfo = offender.proc
    now = time.time() if now is None else now
    facts: dict[str, object] = {
        "proc_name": proc.name,
        "proc_pid": proc.pid,
        "proc_cpu": f"{offender.cpu_share:.1f}%",
        "proc_ram": f"{offender.ram_share:.1f}%",
    }
    if proc.username:
        facts["proc_user"] = proc.username
    if proc.cmdline:
        cmd = proc.cmdline
        facts["proc_cmd"] = cmd if len(cmd) <= 70 else cmd[:67] + "..."
    if proc.created_at:
        facts["proc_age"] = fmt.duration(max(0.0, now - proc.created_at))
    return facts


# --------------------------------------------------------------------------
# picking
# --------------------------------------------------------------------------


def eligible(bank: Sequence[Line], facts: dict, sass: int) -> list[Line]:
    return [line for line in bank if line.eligible(sass, facts)]


def pick(
    bank: Sequence[Line],
    facts: dict,
    sass: int,
    recent: Sequence[str] = (),
    rng: random.Random | None = None,
) -> Line | None:
    """Weighted random, excluding anything said in the last `recent` lines.

    If every eligible line is inside the no-repeat window - a small bank on a
    machine missing most sensors - fall back to the least recently used rather
    than saying nothing.
    """
    rng = rng or random
    pool = eligible(bank, facts, sass)
    if not pool:
        return None

    fresh = [line for line in pool if line.id not in recent]
    if not fresh:
        seen = {line_id: index for index, line_id in enumerate(recent)}
        oldest = min(seen.get(line.id, -1) for line in pool)
        fresh = [line for line in pool if seen.get(line.id, -1) == oldest]

    return rng.choices(fresh, weights=[line.weight for line in fresh], k=1)[0]


# --------------------------------------------------------------------------
# narrators
# --------------------------------------------------------------------------


class FactualNarrator:
    """sass_level 0. A system monitor with a face and no opinions.

    It reports state changes and a periodic summary, and nothing else.
    """

    def __init__(self, rng: random.Random | None = None) -> None:
        self._last_mood: Mood | None = None
        self._last_summary_at = 0.0

    def greeting(self, pet: PetState, metrics: Metrics, cfg: Config) -> list[str]:
        out = [f"{pet.name} (generation {pet.generation}), health {pet.health:.0f}/100."]
        away = pet.absence_s(metrics.now)
        if away > 3600:
            out.append(f"Last session ended {fmt.duration(away)} ago.")
        if pet.predecessor:
            pred = pet.predecessor
            out.append(
                f"Previous pet: {pred.get('name', '?')}, "
                f"cause of death {pred.get('cause', 'unknown')}."
            )
        return out

    def observe(self, pet: PetState, tick: Tick, metrics: Metrics, cfg: Config) -> str | None:
        mood = tick.mood
        if mood is not self._last_mood:
            self._last_mood = mood
            self._last_summary_at = metrics.now
            if tick.verdict.cause:
                return tick.verdict.cause
            if mood is Mood.SMUG:
                return tick.verdict.check("uptime").detail
            if mood is Mood.DEAD:
                return "Health reached zero. Pet is dead."
            return "All monitored metrics within thresholds."

        if metrics.now - self._last_summary_at >= SUMMARY_INTERVAL_S:
            self._last_summary_at = metrics.now
            parts = [c.detail for c in tick.verdict.checks if c.available]
            return "  ".join(parts) if parts else "No metrics available on this machine."
        return None

    def self_report(self, metrics: Metrics, cfg: Config) -> str:
        rss = f", {fmt.size(metrics.self_rss_bytes)} rss" if metrics.self_rss_bytes else ""
        return f"tamagoctl is using {metrics.self_cpu_pct:.2f}% CPU{rss}."

    def eulogy(self, pet: PetState, tick: Tick, metrics: Metrics, cfg: Config) -> str:
        return (
            f"{pet.name} (generation {pet.generation}) died after "
            f"{fmt.duration(pet.age_s(metrics.now))}. Cause: {tick.verdict.cause}."
        )


class SassyNarrator:
    """sass_level 1-3. Draws from the banks, remembers what it just said."""

    def __init__(self, pet: PetState, rng: random.Random | None = None) -> None:
        self.rng = rng or random.Random()
        self._recent: list[str] = list(pet.recent_comments)
        self._last_mood: Mood | None = None
        self._last_spoke_at = 0.0

    # -- internals ----------------------------------------------------------

    def _say(self, bank, facts: dict, sass: int, pet: PetState) -> str | None:
        line = pick(bank, facts, sass, self._recent, self.rng)
        if line is None:
            return None
        self._recent.append(line.id)
        del self._recent[:-NO_REPEAT_WINDOW]
        pet.remember_comment(line.id, keep=NO_REPEAT_WINDOW)
        return render(line, facts)

    def _interval(self, sass: int) -> float:
        return COMMENT_INTERVAL_S.get(sass, COMMENT_INTERVAL_S[2])

    # -- protocol -----------------------------------------------------------

    def greeting(self, pet: PetState, metrics: Metrics, cfg: Config) -> list[str]:
        facts = _greeting_facts(pet, metrics, cfg)
        out: list[str] = []

        away = pet.absence_s(metrics.now)
        bank = next((b for threshold, b in ABSENCE_TIERS if away >= threshold), None)
        if bank is not None:
            out.append(self._say(bank, facts, cfg.sass_level, pet) or "")
        else:
            out.append(
                f"{pet.name}, generation {pet.generation}, {pet.health:.0f}/100. Watching."
            )

        if pet.predecessor and self.rng.random() < PREDECESSOR_GREETING_CHANCE:
            out.append(self._say(banks.PREDECESSOR, facts, cfg.sass_level, pet) or "")

        return [line for line in out if line]

    def observe(self, pet: PetState, tick: Tick, metrics: Metrics, cfg: Config) -> str | None:
        mood = tick.mood
        changed = mood is not self._last_mood
        due = metrics.now - self._last_spoke_at >= self._interval(cfg.sass_level)
        if not (changed or due):
            return None

        self._last_mood = mood
        self._last_spoke_at = metrics.now
        facts = facts_for(pet, tick, metrics, cfg)

        if (
            not changed
            and pet.predecessor
            and self.rng.random() < PREDECESSOR_CHANCE
        ):
            said = self._say(banks.PREDECESSOR, facts, cfg.sass_level, pet)
            if said:
                return said

        bank = banks.BY_MOOD.get(str(mood), banks.CONTENT)
        said = self._say(bank, facts, cfg.sass_level, pet)
        if said is None and tick.verdict.cause:
            # Nothing in the bank fits this machine; fall back to the plain fact.
            return tick.verdict.cause
        return said

    def self_report(self, metrics: Metrics, cfg: Config) -> str:
        facts = {
            "self_cpu": f"{metrics.self_cpu_pct:.2f}%",
            "self_rss": fmt.size(metrics.self_rss_bytes) if metrics.self_rss_bytes else None,
            "age": fmt.duration(metrics.uptime_s),
        }
        facts = {k: v for k, v in facts.items() if v is not None}
        line = pick(banks.SELF_REPORT, facts, cfg.sass_level, self._recent, self.rng)
        if line is None:
            return f"I am using {metrics.self_cpu_pct:.2f}% CPU. Sorry about that."
        self._recent.append(line.id)
        del self._recent[:-NO_REPEAT_WINDOW]
        return render(line, facts)

    def eulogy(self, pet: PetState, tick: Tick, metrics: Metrics, cfg: Config) -> str:
        facts = facts_for(pet, tick, metrics, cfg)
        line = pick(banks.EULOGY, facts, cfg.sass_level, (), self.rng)
        if line is None:
            return f"{pet.name} is dead. Cause: {tick.verdict.cause}."
        return render(line, facts)


def _greeting_facts(pet: PetState, metrics: Metrics, cfg: Config) -> dict:
    """Greetings happen before the first tick, so there is no verdict yet."""
    facts = {
        "name": pet.name,
        "gen": pet.generation,
        "health": f"{pet.health:.0f}",
        "away": fmt.duration(pet.absence_s(metrics.now)),
        "age": fmt.duration(pet.age_s(metrics.now)),
    }
    pred = pet.predecessor or {}
    if pred.get("name"):
        facts["pred_name"] = pred["name"]
        facts["pred_gen"] = pred.get("generation")
        facts["pred_cause"] = pred.get("cause")
        if pred.get("lifespan_s"):
            facts["pred_lifespan"] = fmt.duration(pred["lifespan_s"])
    return {k: v for k, v in facts.items() if v is not None}


def roast(offender, cfg: Config, rng: random.Random | None = None,
          now: float | None = None) -> str:
    """One line about the single worst-behaved process."""
    facts = roast_facts(offender, now)
    if cfg.sass_level <= 0:
        return (
            f"{facts['proc_name']} (pid {facts['proc_pid']}) is the largest consumer: "
            f"{facts['proc_cpu']} CPU, {facts['proc_ram']} RAM."
        )
    line = pick(banks.ROAST, facts, cfg.sass_level, (), rng or random)
    return render(line, facts) if line else ""


def narrator_for(cfg: Config, pet: PetState, rng: random.Random | None = None) -> Narrator:
    """Level 0 is a monitor; everything above it has opinions."""
    if cfg.sass_level <= 0:
        return FactualNarrator(rng)
    return SassyNarrator(pet, rng)
