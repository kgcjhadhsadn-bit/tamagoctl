"""Death, tombstones and lineage."""

import json
from dataclasses import replace

import pytest
from typer.testing import CliRunner

from tamagoctl import graveyard, state as state_mod
from tamagoctl.cli import EXIT_OK, EXIT_UNHAPPY, app
from tamagoctl.config import Config, NetworkConfig, graveyard_dir
from tamagoctl.graveyard import Tombstone, bury, epitaph_for, graves, latest
from tamagoctl.lines import EPITAPHS
from tamagoctl.session import Session
from tamagoctl.state import new_pet
from tests import factories as f

runner = CliRunner()
OFFLINE_CFG = replace(Config(), network=NetworkConfig(enabled=False))
OFFLINE = ["--no-network"]


class FakeCollector:
    def __init__(self, metrics, step_s=10.0):
        self.metrics, self.step_s, self.i = metrics, step_s, 0

    def sample(self, now=None):
        sample = replace(self.metrics, now=f.NOW + self.i * self.step_s)
        self.i += 1
        return sample

    def start(self):
        return self

    def stop(self):
        pass


def dying_session(metrics=None, health=0.4, cfg=OFFLINE_CFG):
    pet = new_pet(now=f.NOW)
    pet.health = health
    return Session(cfg, pet, FakeCollector(metrics or f.constipated()), clock=lambda: f.NOW)


class TestTombstone:
    def test_death_writes_a_tombstone(self):
        sess = dying_session()
        sess.advance()
        sess.advance()
        assert sess.died is True
        assert len(graves()) == 1

    def test_the_tombstone_names_the_metric_that_killed_it(self):
        sess = dying_session(f.constipated())
        sess.advance()
        sess.advance()
        stone = latest()
        assert stone.killer == "disk"
        assert "free on /" in stone.cause

    def test_the_worst_metric_gets_the_blame(self):
        sess = dying_session(f.hangry(disk_free_pct=1.0, ram_pct=99.0))
        sess.advance()
        sess.advance()
        assert latest().killer == "battery"

    def test_the_tombstone_records_the_final_metrics(self):
        sess = dying_session(f.constipated())
        sess.advance()
        sess.advance()
        snapshot = latest().final_metrics
        assert snapshot["disk_free_pct"] == 4.0
        assert snapshot["red"] == ["disk"]
        assert "Disk 4.0% free" in snapshot["checks"]["disk"]

    def test_the_tombstone_records_the_lifespan(self):
        sess = dying_session()
        sess.advance()
        sess.advance()
        assert latest().lifespan_s == pytest.approx(10.0)

    def test_only_one_grave_per_death(self):
        """The death transition fires once, however long the session runs on."""
        sess = dying_session()
        for _ in range(8):
            sess.advance()
        assert len(graves()) == 1

    def test_the_pet_stays_dead_for_the_rest_of_the_session(self):
        sess = dying_session()
        for _ in range(6):
            frame = sess.advance()
        assert str(frame.tick.mood) == "dead"
        assert frame.tick.health == 0.0

    def test_the_feed_says_how_to_revive(self):
        sess = dying_session()
        sess.advance()
        sess.advance()
        assert any("revive" in line for line in sess.feed)

    def test_state_is_flushed_immediately_on_death(self):
        """Do not wait for the periodic save to record something irreversible."""
        sess = dying_session()
        sess.advance()
        sess.advance()
        assert state_mod.load().health == 0.0


class TestEpitaphs:
    @pytest.mark.parametrize("killer", sorted(EPITAPHS))
    def test_every_killer_has_its_own_epitaphs(self, killer):
        assert epitaph_for(killer) in EPITAPHS[killer]

    def test_an_unattributed_death_still_gets_an_epitaph(self):
        assert epitaph_for(None)
        assert epitaph_for("something-unknown")


class TestGraveyardFiles:
    def test_graves_are_listed_oldest_first(self):
        for index, name in enumerate(["Awk", "Sed", "Grep"]):
            bury(Tombstone(name=name, generation=index + 1, born_at=0.0,
                           died_at=1000.0 + index, lifespan_s=10.0,
                           cause="x", killer="ram", epitaph="y"))
        assert [stone.name for stone in graves()] == ["Awk", "Sed", "Grep"]

    def test_an_unreadable_grave_is_skipped_not_fatal(self):
        bury(Tombstone(name="Awk", generation=1, born_at=0.0, died_at=1000.0,
                       lifespan_s=1.0, cause="x", killer="ram", epitaph="y"))
        (graveyard_dir() / "corrupt.json").write_text("{{{ not json")
        assert [stone.name for stone in graves()] == ["Awk"]

    def test_a_hostile_pet_name_cannot_escape_the_graveyard_directory(self):
        stone = Tombstone(name="../../etc/passwd", generation=1, born_at=0.0,
                          died_at=1000.0, lifespan_s=1.0, cause="x", killer=None,
                          epitaph="y")
        path = bury(stone)
        # The property that matters is that the file lands inside the graveyard,
        # not that the name is pretty. Separators are what would let it escape.
        assert path.resolve().parent == graveyard_dir().resolve()
        assert "/" not in path.name and "\\" not in path.name
        assert graves()[0].name == "../../etc/passwd"  # the record itself is intact

    def test_an_empty_graveyard_is_not_an_error(self):
        assert graves() == []
        assert latest() is None

    def test_a_grave_from_a_future_schema_still_reads(self):
        graveyard_dir().mkdir(parents=True, exist_ok=True)
        (graveyard_dir() / "1000-Awk.json").write_text(json.dumps({
            "name": "Awk", "generation": 1, "died_at": 1000.0,
            "cause": "x", "psychic_damage": 9,
        }))
        assert graves()[0].name == "Awk"

    def test_no_temp_files_are_left_behind(self):
        bury(Tombstone(name="Awk", generation=1, born_at=0.0, died_at=1.0,
                       lifespan_s=1.0, cause="x", killer=None, epitaph="y"))
        assert list(graveyard_dir().glob("*.tmp")) == []


class TestGraveyardCommand:
    def test_an_empty_graveyard_says_so(self):
        result = runner.invoke(app, ["graveyard"])
        assert result.exit_code == EXIT_OK
        assert "empty" in result.stdout

    def test_it_lists_the_dead_with_cause_and_lifespan(self):
        bury(Tombstone(name="Segfault", generation=2, born_at=0.0, died_at=1000.0,
                       lifespan_s=90000.0, cause="Disk 0.4% free on /", killer="disk",
                       epitaph="Filled up. Gave up."))
        result = runner.invoke(app, ["graveyard"])
        assert "Segfault" in result.stdout
        assert "disk" in result.stdout
        assert "1d" in result.stdout
        assert "Filled up" in result.stdout


class TestRevive:
    def test_revive_refuses_to_replace_a_living_pet(self):
        state_mod.save(new_pet(now=f.NOW))
        result = runner.invoke(app, OFFLINE + ["revive"])
        assert result.exit_code == EXIT_UNHAPPY
        assert "still alive" in result.stdout
        assert graves() == []

    def test_force_replaces_a_living_pet_and_buries_it(self):
        pet = new_pet(now=f.NOW)
        state_mod.save(pet)
        result = runner.invoke(app, OFFLINE + ["revive", "--force"])
        assert result.exit_code == EXIT_OK
        assert latest().name == pet.name
        assert "Did nothing wrong" in latest().epitaph

    def test_reviving_a_dead_pet_increments_the_generation(self):
        pet = new_pet(now=f.NOW)
        pet.health = 0.0
        state_mod.save(pet)
        runner.invoke(app, OFFLINE + ["revive"])
        assert state_mod.load().generation == 2

    def test_the_new_pet_knows_its_predecessor(self):
        sess = dying_session()
        sess.advance()
        sess.advance()
        dead_name = sess.pet.name
        sess.finish()

        runner.invoke(app, OFFLINE + ["revive"])
        fresh = state_mod.load()
        assert fresh.predecessor["name"] == dead_name
        assert fresh.predecessor["killer"] == "disk"
        assert "free on /" in fresh.predecessor["cause"]

    def test_reviving_does_not_bury_the_same_pet_twice(self):
        """The session already wrote the grave; revive must not add a second."""
        sess = dying_session()
        sess.advance()
        sess.advance()
        sess.finish()
        runner.invoke(app, OFFLINE + ["revive"])
        assert len(graves()) == 1

    def test_the_new_pet_starts_at_full_health_with_a_clean_history(self):
        pet = new_pet(now=f.NOW)
        pet.health = 0.0
        pet.remember_comment("cpu-01")
        state_mod.save(pet)
        runner.invoke(app, OFFLINE + ["revive"])
        fresh = state_mod.load()
        assert fresh.health == 100.0
        assert fresh.recent_comments == []

    def test_revive_with_no_pet_at_all_just_hatches_one(self):
        result = runner.invoke(app, OFFLINE + ["revive"])
        assert result.exit_code == EXIT_OK
        assert "There was no pet" in result.stdout
        assert state_mod.load().generation == 1

    def test_generations_accumulate_across_deaths(self):
        for expected in (2, 3, 4):
            pet, _ = state_mod.load_or_create()
            pet.health = 0.0
            state_mod.save(pet)
            runner.invoke(app, OFFLINE + ["revive"])
            assert state_mod.load().generation == expected
        assert len(graves()) == 3


class TestGraveIdentity:
    """Two pets can share a name or a second of death. Neither may confuse revive."""

    def _stone(self, name, generation, born_at, died_at, cause="x"):
        return Tombstone(name=name, generation=generation, born_at=born_at,
                         died_at=died_at, lifespan_s=died_at - born_at, cause=cause,
                         killer="ram", epitaph="y")

    def test_two_pets_dying_in_the_same_second_get_separate_graves(self):
        bury(self._stone("Awk", 1, 100.0, 1000.0))
        bury(self._stone("Sed", 2, 500.0, 1000.0))
        assert len(graves()) == 2

    def test_the_same_name_across_generations_does_not_overwrite(self):
        bury(self._stone("Awk", 1, 100.0, 1000.0))
        bury(self._stone("Awk", 3, 500.0, 1000.0))
        assert len(graves()) == 2

    def test_a_pet_is_found_by_birth_not_by_name(self):
        bury(self._stone("Awk", 1, 100.0, 1000.0, cause="first"))
        bury(self._stone("Awk", 3, 500.0, 1000.0, cause="third"))
        assert graveyard.find_by_birth(100.0).cause == "first"
        assert graveyard.find_by_birth(500.0).cause == "third"
        assert graveyard.find_by_birth(999.0) is None

    def test_revive_finds_an_existing_grave_even_when_it_is_not_the_newest(self):
        """Regression: revive used to bury a pet a second time in this case."""
        sess = dying_session()
        sess.advance()
        sess.advance()
        sess.finish()
        dead = state_mod.load()

        # Another pet dies in the same second, landing after it in the listing.
        bury(self._stone("Someone", 9, dead.born_at + 1, latest().died_at))

        runner.invoke(app, OFFLINE + ["revive"])
        mine = [stone for stone in graves() if stone.born_at == dead.born_at]
        assert len(mine) == 1
        assert mine[0].killer == "disk"  # the real cause, not a fabricated one
