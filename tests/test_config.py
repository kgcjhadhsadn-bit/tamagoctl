"""Configuration, and guards against the three copies of it drifting apart.

The defaults exist in three places - the dataclasses, the commented config.toml
that ships to users, and the block in the README. Nothing keeps them in step
except these tests.
"""

import re
import tomllib
from dataclasses import fields
from pathlib import Path

import pytest

from tamagoctl.config import (
    DEFAULT_CONFIG_TOML,
    Config,
    clamp_sass,
    config_from_dict,
    ensure_config,
    load_config,
)

README = Path(__file__).resolve().parents[1] / "README.md"
SECTIONS = ("thresholds", "health", "network")


def flatten(data: dict, prefix: str = "") -> dict:
    out = {}
    for key, value in data.items():
        if isinstance(value, dict):
            out.update(flatten(value, f"{prefix}{key}."))
        else:
            out[f"{prefix}{key}"] = value
    return out


def declared_keys() -> set[str]:
    """Every knob the dataclasses actually define."""
    keys = {f.name for f in fields(Config) if f.name not in SECTIONS}
    for section in SECTIONS:
        keys |= {f"{section}.{f.name}" for f in fields(getattr(Config(), section))}
    return keys


class TestNoDrift:
    def test_the_shipped_config_parses(self):
        assert tomllib.loads(DEFAULT_CONFIG_TOML)

    def test_the_shipped_config_reproduces_the_dataclass_defaults(self):
        """A user who never edits the file must get exactly the built-in behaviour."""
        assert config_from_dict(tomllib.loads(DEFAULT_CONFIG_TOML)) == Config()

    def test_every_shipped_key_is_a_real_setting(self):
        """Catches a knob documented to users that nothing reads."""
        shipped = set(flatten(tomllib.loads(DEFAULT_CONFIG_TOML)))
        assert shipped - declared_keys() == set()

    def test_every_setting_is_documented_in_the_shipped_config(self):
        shipped = set(flatten(tomllib.loads(DEFAULT_CONFIG_TOML)))
        assert declared_keys() - shipped == set()

    def test_the_readme_config_block_matches_what_ships(self):
        block = re.search(r"```toml\n(.*?)```", README.read_text(), re.S)
        assert block, "the README no longer has a toml config block"
        documented = flatten(tomllib.loads(block.group(1)))
        shipped = flatten(tomllib.loads(DEFAULT_CONFIG_TOML))
        assert documented == shipped


class TestLoading:
    def test_a_missing_file_yields_defaults(self, tmp_path):
        assert load_config(tmp_path / "nope.toml") == Config()

    def test_a_corrupt_file_yields_defaults_rather_than_raising(self, tmp_path):
        path = tmp_path / "config.toml"
        path.write_text("this is not toml {{{")
        assert load_config(path) == Config()

    def test_unknown_keys_are_ignored(self, tmp_path):
        path = tmp_path / "config.toml"
        path.write_text("sass_level = 1\nnonsense = true\n[thresholds]\nbogus = 5\n")
        assert load_config(path).sass_level == 1

    def test_a_broken_value_does_not_stop_the_pet_from_booting(self, tmp_path):
        path = tmp_path / "config.toml"
        path.write_text('[thresholds]\nram_pct = "ninety"\n')
        assert load_config(path).thresholds.ram_pct == Config().thresholds.ram_pct

    def test_partial_sections_keep_the_other_defaults(self, tmp_path):
        path = tmp_path / "config.toml"
        path.write_text("[thresholds]\nram_pct = 50.0\n")
        cfg = load_config(path)
        assert cfg.thresholds.ram_pct == 50.0
        assert cfg.thresholds.cpu_pct == Config().thresholds.cpu_pct

    def test_ensure_config_writes_the_commented_default_once(self, sandbox_home):
        path = ensure_config()
        assert "sass_level" in path.read_text()
        path.write_text("sass_level = 0\n")
        ensure_config()  # must not clobber an edited file
        assert path.read_text() == "sass_level = 0\n"

    @pytest.mark.parametrize(
        "given,expected", [(-5, 0), (0, 0), (2, 2), (3, 3), (9, 3)]
    )
    def test_sass_is_clamped(self, given, expected):
        assert clamp_sass(given) == expected

    def test_a_non_integer_sass_falls_back_to_the_default(self):
        assert config_from_dict({"sass_level": "loud"}).sass_level == Config().sass_level
