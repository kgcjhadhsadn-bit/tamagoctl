"""Persistence: it must survive a crash mid-write and a hand-edited file."""

import json
import random

from tamagoctl import state as state_mod
from tamagoctl.config import state_path
from tamagoctl.state import GUILT_THRESHOLD_S, PetState, load, new_pet, save, touch

NOW = 1_700_000_000.0


class TestRoundTrip:
    def test_a_saved_pet_comes_back_identical(self):
        pet = new_pet(now=NOW)
        pet.health = 63.5
        pet.mood = "sweating"
        pet.remember_comment("cpu-07")
        save(pet)
        loaded = load()
        assert loaded.name == pet.name
        assert loaded.health == 63.5
        assert loaded.mood == "sweating"
        assert loaded.recent_comments == ["cpu-07"]

    def test_no_state_file_means_no_pet_yet(self):
        assert load() is None

    def test_load_or_create_hatches_one_on_first_run(self):
        pet, is_new = state_mod.load_or_create()
        assert is_new is True
        assert pet.health == 100.0
        assert pet.generation == 1

    def test_load_or_create_returns_the_existing_pet_afterwards(self):
        first, _ = state_mod.load_or_create()
        save(first)
        second, is_new = state_mod.load_or_create()
        assert is_new is False
        assert second.name == first.name

    def test_save_creates_the_home_directory(self, sandbox_home):
        assert not sandbox_home.exists()
        save(new_pet())
        assert state_path().exists()


class TestCorruptFiles:
    def test_truncated_json_is_treated_as_no_pet(self):
        state_path().parent.mkdir(parents=True, exist_ok=True)
        state_path().write_text('{"name": "Fsck", "hea')
        assert load() is None

    def test_a_json_list_is_not_a_pet(self):
        state_path().parent.mkdir(parents=True, exist_ok=True)
        state_path().write_text("[1, 2, 3]")
        assert load() is None

    def test_unknown_keys_from_a_future_version_are_ignored(self):
        state_path().parent.mkdir(parents=True, exist_ok=True)
        state_path().write_text(json.dumps({
            "name": "Grep", "born_at": NOW, "last_seen_at": NOW,
            "health": 50.0, "telepathy": True,
        }))
        assert load().name == "Grep"

    def test_hand_edited_health_is_clamped(self):
        """Nobody gets a 9000hp pet by editing the file."""
        state_path().parent.mkdir(parents=True, exist_ok=True)
        state_path().write_text(json.dumps({
            "name": "Grep", "born_at": NOW, "last_seen_at": NOW, "health": 9000.0,
        }))
        assert load().health == 100.0

    def test_negative_health_is_clamped_to_dead(self):
        state_path().parent.mkdir(parents=True, exist_ok=True)
        state_path().write_text(json.dumps({
            "name": "Grep", "born_at": NOW, "last_seen_at": NOW, "health": -40.0,
        }))
        assert load().health == 0.0

    def test_save_leaves_no_temp_files_behind(self, sandbox_home):
        save(new_pet())
        assert list(sandbox_home.glob("*.tmp")) == []


class TestCommentHistory:
    def test_history_is_capped_at_ten(self):
        pet = new_pet()
        for i in range(25):
            pet.remember_comment(f"line-{i}")
        assert len(pet.recent_comments) == 10
        assert pet.recent_comments[-1] == "line-24"
        assert pet.recent_comments[0] == "line-15"

    def test_history_survives_a_save(self):
        pet = new_pet()
        for i in range(4):
            pet.remember_comment(f"line-{i}")
        save(pet)
        assert load().recent_comments == ["line-0", "line-1", "line-2", "line-3"]


class TestAbsence:
    def test_a_fresh_pet_is_owed_nothing(self):
        pet = new_pet(now=NOW)
        assert pet.absence_s(NOW) == 0.0
        assert pet.owes_a_guilt_trip(NOW) is False

    def test_two_days_away_is_forgivable(self):
        pet = new_pet(now=NOW)
        assert pet.owes_a_guilt_trip(NOW + 2 * 86400) is False

    def test_three_days_away_is_not(self):
        pet = new_pet(now=NOW)
        assert pet.owes_a_guilt_trip(NOW + GUILT_THRESHOLD_S) is True

    def test_touch_records_the_visit_and_the_runtime(self):
        pet = new_pet(now=NOW)
        updated = touch(pet, now=NOW + 500, ran_for=120.0)
        assert updated.last_seen_at == NOW + 500
        assert updated.total_runtime_s == 120.0
        assert updated.absence_s(NOW + 500) == 0.0

    def test_a_backwards_clock_does_not_produce_negative_absence(self):
        pet = new_pet(now=NOW)
        assert pet.absence_s(NOW - 9999) == 0.0


class TestNaming:
    def test_a_new_pet_avoids_its_predecessors_name(self):
        rng = random.Random(0)
        for _ in range(50):
            pet = new_pet(generation=2, predecessor={"name": "Grep"}, rng=rng)
            assert pet.name != "Grep"

    def test_generation_is_carried(self):
        pet = new_pet(generation=4, predecessor={"name": "Awk", "generation": 3})
        assert pet.generation == 4
        assert pet.predecessor["name"] == "Awk"

    def test_generation_is_never_below_one(self):
        assert PetState.from_dict({"name": "X", "generation": 0}).generation == 1
