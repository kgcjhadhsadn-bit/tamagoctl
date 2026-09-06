"""The comment engine: no-repeat window, sass gating, template integrity."""

import random
from dataclasses import replace

import pytest

from tamagoctl import comments, lines as banks
from tamagoctl.comments import (
    FactualNarrator,
    SassyNarrator,
    facts_for,
    narrator_for,
    pick,
    roast,
    roast_facts,
)
from tamagoctl.health import step
from tamagoctl.lines import Line, bank
from tamagoctl.roast import pick_worst
from tamagoctl.state import new_pet
from tests import factories as f
from tests.factories import CONFIG

MOOD_BANKS = sorted(banks.BY_MOOD.items())


def full_facts(sass=3):
    """Facts from a machine where every single sensor reports something."""
    pet = new_pet(now=f.NOW)
    pet.predecessor = {"name": "Awk", "generation": 1, "cause": "Disk 0.4% free",
                       "lifespan_s": 90000}
    metrics = f.sweating(browser_procs={"Chrome": 47})
    tick = step(metrics, 80.0, 2.0, CONFIG)
    return facts_for(pet, tick, metrics, CONFIG)


class TestBankIntegrity:
    @pytest.mark.parametrize("mood,lines", MOOD_BANKS)
    def test_every_live_mood_has_at_least_twenty_lines(self, mood, lines):
        if mood == "dead":
            pytest.skip("the dead bank is a holding pattern, not a mood")
        assert len(lines) >= 20

    @pytest.mark.parametrize("mood,lines", MOOD_BANKS)
    def test_line_ids_are_unique_within_a_bank(self, mood, lines):
        ids = [line.id for line in lines]
        assert len(ids) == len(set(ids))

    def test_line_ids_are_unique_across_every_bank(self):
        """Shared history means a duplicate id would silently suppress a line."""
        every = [
            line
            for name in dir(banks)
            if isinstance(getattr(banks, name), tuple)
            for line in getattr(banks, name)
            if isinstance(line, Line)
        ]
        ids = [line.id for line in every]
        assert len(ids) == len(set(ids))

    @pytest.mark.parametrize("mood,lines", MOOD_BANKS)
    def test_every_template_field_is_a_fact_the_engine_produces(self, mood, lines):
        """Catches a typo like {disk_pct} that would render as '?' at runtime."""
        known = set(full_facts()) | set(
            roast_facts(_offender(), now=f.NOW)
        ) | {"proc_age"}
        for line in lines:
            assert set(line.needs) <= known, f"{line.id} wants {set(line.needs) - known}"

    def test_roast_templates_only_use_process_facts(self):
        known = set(roast_facts(_offender(), now=f.NOW))
        for line in banks.ROAST:
            assert set(line.needs) <= known, f"{line.id} wants {set(line.needs) - known}"

    @pytest.mark.parametrize("mood,lines", MOOD_BANKS)
    def test_every_line_renders_without_a_missing_field(self, mood, lines):
        facts = full_facts()
        for line in lines:
            if set(line.needs) <= set(facts):
                line.text.format(**facts)  # raises KeyError on an unknown field

    @pytest.mark.parametrize("sass", [1, 2, 3])
    @pytest.mark.parametrize("mood,lines", MOOD_BANKS)
    def test_every_sass_level_has_more_lines_than_the_no_repeat_window(
        self, sass, mood, lines
    ):
        """Otherwise the window would force repeats on every single pick."""
        available = comments.eligible(lines, full_facts(), sass)
        assert len(available) > comments.NO_REPEAT_WINDOW


def _offender():
    from tamagoctl.metrics import ProcInfo

    procs = [ProcInfo(pid=4821, name="Chrome Helper", cpu_pct=800.0, rss_pct=31.0,
                      username="you", cmdline="/opt/chrome --type=renderer",
                      created_at=f.NOW - 3600)]
    return pick_worst(procs, cpu_count=8)


class TestNoRepeat:
    def test_a_line_is_never_repeated_inside_the_window(self):
        rng = random.Random(1)
        recent: list[str] = []
        for _ in range(200):
            line = pick(banks.CONTENT, full_facts(), 3, recent, rng)
            assert line.id not in recent
            recent.append(line.id)
            del recent[:-comments.NO_REPEAT_WINDOW]

    def test_a_narrator_does_not_repeat_within_ten_lines(self):
        cfg = replace(CONFIG, sass_level=3)
        pet = new_pet(now=f.NOW)
        narrator = SassyNarrator(pet, random.Random(3))
        said = []
        for i in range(40):
            metrics = f.green(now=f.NOW + i * 20)
            tick = step(metrics, 100.0, 20.0, cfg)
            line = narrator.observe(pet, tick, metrics, cfg)
            if line:
                said.append(line)
        for index, line in enumerate(said):
            assert line not in said[max(0, index - 9):index], line

    def test_a_tiny_bank_falls_back_to_least_recently_used_instead_of_silence(self):
        tiny = bank("tiny", "one {cpu}", "two {cpu}")
        rng = random.Random(0)
        recent = ["tiny00", "tiny01"]
        line = pick(tiny, {"cpu": "9%"}, 3, recent, rng)
        assert line is not None
        assert line.id == "tiny00"  # the older of the two

    def test_history_is_shared_with_the_pet_so_it_survives_a_restart(self):
        cfg = replace(CONFIG, sass_level=2)
        pet = new_pet(now=f.NOW)
        narrator = SassyNarrator(pet, random.Random(5))
        metrics = f.green()
        narrator.observe(pet, step(metrics, 100.0, 2.0, cfg), metrics, cfg)
        assert len(pet.recent_comments) == 1

        # A new session picks up where the old one left off.
        resumed = SassyNarrator(pet, random.Random(5))
        assert resumed._recent == pet.recent_comments


class TestSassGating:
    def test_level_zero_is_the_factual_narrator(self):
        pet = new_pet()
        assert isinstance(narrator_for(replace(CONFIG, sass_level=0), pet), FactualNarrator)

    def test_levels_one_to_three_have_opinions(self):
        pet = new_pet()
        for level in (1, 2, 3):
            assert isinstance(narrator_for(replace(CONFIG, sass_level=level), pet),
                              SassyNarrator)

    def test_unhinged_lines_never_appear_at_level_one(self):
        facts = full_facts()
        rng = random.Random(11)
        unhinged = {line.id for line in banks.SMUG if line.sass == banks.UNHINGED}
        assert unhinged  # the bank must actually contain some
        for _ in range(300):
            assert pick(banks.SMUG, facts, 1, [], rng).id not in unhinged

    def test_level_zero_reports_facts_and_nothing_else(self):
        """Someone who wanted a system monitor has to be able to use level 0."""
        cfg = replace(CONFIG, sass_level=0)
        pet = new_pet(now=f.NOW)
        narrator = FactualNarrator()
        metrics = f.constipated()
        line = narrator.observe(pet, step(metrics, 100.0, 2.0, cfg), metrics, cfg)
        assert line == "Disk 4.0% free on / (2.0GB)"

    def test_level_zero_does_not_editorialise_when_healthy(self):
        cfg = replace(CONFIG, sass_level=0)
        pet = new_pet(now=f.NOW)
        metrics = f.green()
        line = FactualNarrator().observe(pet, step(metrics, 100.0, 2.0, cfg), metrics, cfg)
        assert line == "All monitored metrics within thresholds."


class TestFactGating:
    def test_the_chrome_line_never_fires_on_a_machine_without_chrome(self):
        pet = new_pet(now=f.NOW)
        metrics = f.bloated(browser_procs={})
        facts = facts_for(pet, step(metrics, 100.0, 2.0, CONFIG), metrics, CONFIG)
        assert "chrome" not in facts
        chrome_lines = [line for line in banks.BLOATED if "chrome" in line.needs]
        assert chrome_lines
        for line in chrome_lines:
            assert not line.eligible(3, facts)

    def test_the_laptop_line_never_fires_on_a_desktop(self):
        pet = new_pet(now=f.NOW)
        metrics = f.headless(cpu_pct=93.0, cpu_hot_since=f.NOW - 90)
        facts = facts_for(pet, step(metrics, 100.0, 2.0, CONFIG), metrics, CONFIG)
        laptop = [line for line in banks.SWEATING if "battery" in line.needs]
        assert laptop
        for line in laptop:
            assert not line.eligible(3, facts)

    def test_a_blind_machine_still_produces_a_line(self):
        """Every sensor missing must not mean silence."""
        cfg = replace(CONFIG, sass_level=3)
        pet = new_pet(now=f.NOW)
        metrics = f.green(cpu_pct=None, ram_pct=None, disk_free_pct=None,
                          latency_ms=None, packet_loss=None, battery_pct=None,
                          uptime_s=None, proc_count=None, browser_procs={},
                          top_cpu_proc=None, top_ram_proc=None,
                          ram_used_bytes=None, ram_total_bytes=None,
                          disk_free_bytes=None)
        narrator = SassyNarrator(pet, random.Random(2))
        assert narrator.observe(pet, step(metrics, 100.0, 2.0, cfg), metrics, cfg)


class TestAbsence:
    @pytest.mark.parametrize(
        "days,expected_bank",
        [(4, banks.ABSENCE_SHORT), (12, banks.ABSENCE_MEDIUM), (400, banks.ABSENCE_LONG)],
    )
    def test_the_guilt_trip_scales_with_time_away(self, days, expected_bank):
        cfg = replace(CONFIG, sass_level=3)
        pet = new_pet(now=f.NOW)
        pet.last_seen_at = f.NOW - days * 86400
        narrator = SassyNarrator(pet, random.Random(0))
        greeting = narrator.greeting(pet, f.green(), cfg)
        ids = set(pet.recent_comments)
        assert ids & {line.id for line in expected_bank}
        assert greeting

    def test_two_days_away_earns_no_guilt_trip(self):
        cfg = replace(CONFIG, sass_level=3)
        pet = new_pet(now=f.NOW)
        pet.last_seen_at = f.NOW - 2 * 86400
        greeting = SassyNarrator(pet, random.Random(0)).greeting(pet, f.green(), cfg)
        assert not set(pet.recent_comments) & {line.id for line in banks.ABSENCE_SHORT}
        assert pet.name in greeting[0]

    def test_the_guilt_trip_states_how_long_you_were_gone(self):
        cfg = replace(CONFIG, sass_level=2)
        pet = new_pet(now=f.NOW)
        pet.last_seen_at = f.NOW - 9 * 86400
        greeting = SassyNarrator(pet, random.Random(4)).greeting(pet, f.green(), cfg)
        assert "9d" in greeting[0]


class TestSelfReport:
    def test_it_names_its_own_cpu_usage(self):
        cfg = replace(CONFIG, sass_level=2)
        pet = new_pet()
        metrics = f.green(self_cpu_pct=0.19)
        assert "0.19%" in SassyNarrator(pet, random.Random(0)).self_report(metrics, cfg)

    def test_level_zero_reports_it_plainly(self):
        metrics = f.green(self_cpu_pct=0.19)
        line = FactualNarrator().self_report(metrics, replace(CONFIG, sass_level=0))
        assert line.startswith("tamagoctl is using 0.19% CPU")


class TestPredecessor:
    def test_a_pet_with_a_predecessor_eventually_mentions_it(self):
        cfg = replace(CONFIG, sass_level=3)
        pet = new_pet(now=f.NOW, generation=2,
                      predecessor={"name": "Awk", "generation": 1,
                                   "cause": "Disk 0.4% free", "lifespan_s": 90000})
        narrator = SassyNarrator(pet, random.Random(1))
        mentions = 0
        for i in range(400):
            metrics = f.green(now=f.NOW + i * 20)
            line = narrator.observe(pet, step(metrics, 100.0, 20.0, cfg), metrics, cfg)
            if line and "Awk" in line:
                mentions += 1
        assert mentions > 0

    def test_it_mentions_the_predecessor_only_occasionally(self):
        cfg = replace(CONFIG, sass_level=3)
        pet = new_pet(now=f.NOW, generation=2,
                      predecessor={"name": "Awk", "generation": 1, "cause": "x"})
        narrator = SassyNarrator(pet, random.Random(1))
        said, mentions = 0, 0
        for i in range(400):
            metrics = f.green(now=f.NOW + i * 20)
            line = narrator.observe(pet, step(metrics, 100.0, 20.0, cfg), metrics, cfg)
            if line:
                said += 1
                mentions += "Awk" in line
        assert mentions / said < 0.25

    def test_a_first_generation_pet_never_mentions_one(self):
        cfg = replace(CONFIG, sass_level=3)
        pet = new_pet(now=f.NOW)
        narrator = SassyNarrator(pet, random.Random(1))
        for i in range(200):
            metrics = f.green(now=f.NOW + i * 20)
            line = narrator.observe(pet, step(metrics, 100.0, 20.0, cfg), metrics, cfg)
            assert not line or "generation" not in line.lower() or "pred" not in line


class TestRoast:
    def test_the_roast_names_the_process(self):
        offender = _offender()
        text = roast(offender, replace(CONFIG, sass_level=3), random.Random(0), now=f.NOW)
        assert "Chrome Helper" in text or str(offender.proc.pid) in text

    def test_level_zero_gives_a_plain_report(self):
        text = roast(_offender(), replace(CONFIG, sass_level=0), now=f.NOW)
        assert "largest consumer" in text
        assert "4821" in text

    def test_every_roast_line_renders_for_a_fully_described_process(self):
        facts = roast_facts(_offender(), now=f.NOW)
        for line in banks.ROAST:
            if set(line.needs) <= set(facts):
                line.text.format(**facts)  # raises KeyError on an unknown field

    def test_a_process_with_no_cmdline_still_gets_roasted(self):
        from tamagoctl.metrics import ProcInfo

        bare = pick_worst([ProcInfo(pid=7, name="kthreadd", cpu_pct=99.0)], cpu_count=1)
        text = roast(bare, replace(CONFIG, sass_level=3), random.Random(0), now=f.NOW)
        assert text
        assert "kthreadd" in text or "7" in text


class TestRateLimiting:
    def test_a_mood_change_always_speaks_immediately(self):
        cfg = replace(CONFIG, sass_level=1)  # the slowest cadence
        pet = new_pet(now=f.NOW)
        narrator = SassyNarrator(pet, random.Random(0))
        green = f.green(now=f.NOW)
        narrator.observe(pet, step(green, 100.0, 2.0, cfg), green, cfg)
        red = f.constipated(now=f.NOW + 2)
        assert narrator.observe(pet, step(red, 100.0, 2.0, cfg), red, cfg) is not None

    def test_a_steady_mood_stays_quiet_between_intervals(self):
        cfg = replace(CONFIG, sass_level=1)
        pet = new_pet(now=f.NOW)
        narrator = SassyNarrator(pet, random.Random(0))
        first = f.green(now=f.NOW)
        narrator.observe(pet, step(first, 100.0, 2.0, cfg), first, cfg)
        second = f.green(now=f.NOW + 2)
        assert narrator.observe(pet, step(second, 100.0, 2.0, cfg), second, cfg) is None

    def test_higher_sass_talks_more(self):
        assert comments.COMMENT_INTERVAL_S[3] < comments.COMMENT_INTERVAL_S[1]
