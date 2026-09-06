"""CLI surface: exit codes, JSON contract, and the option plumbing."""

import json

import pytest
from typer.testing import CliRunner

from tamagoctl import cli
from tamagoctl.cli import EXIT_DEAD, EXIT_OK, EXIT_UNHAPPY, app
from tamagoctl.config import config_path
from tests import factories as f

runner = CliRunner()

OFFLINE = ["--no-network"]


@pytest.fixture
def fake_machine(monkeypatch):
    """Pin the metrics so the CLI tests do not depend on the host's mood."""

    def pin(metrics):
        monkeypatch.setattr(cli, "one_shot", lambda cfg: metrics)

    return pin


class TestStatus:
    def test_healthy_machine_exits_zero(self, fake_machine):
        fake_machine(f.green())
        assert runner.invoke(app, OFFLINE + ["status"]).exit_code == EXIT_OK

    def test_red_metric_exits_one(self, fake_machine):
        fake_machine(f.bloated())
        result = runner.invoke(app, OFFLINE + ["status"])
        assert result.exit_code == EXIT_UNHAPPY

    def test_smug_is_not_an_unhappy_exit_code(self, fake_machine):
        """Long uptime is not a fault condition. Do not fail someone's script."""
        fake_machine(f.smug())
        assert runner.invoke(app, OFFLINE + ["status"]).exit_code == EXIT_OK

    def test_dead_pet_exits_two(self, fake_machine, sandbox_home):
        from tamagoctl import state as state_mod

        pet = state_mod.new_pet()
        pet.health = 0.0
        state_mod.save(pet)
        fake_machine(f.green())
        assert runner.invoke(app, OFFLINE + ["status"]).exit_code == EXIT_DEAD

    def test_json_output_is_valid_and_complete(self, fake_machine):
        fake_machine(f.constipated())
        result = runner.invoke(app, OFFLINE + ["status", "--json"])
        payload = json.loads(result.stdout)
        assert payload["pet"]["mood"] == "constipated"
        assert payload["verdict"]["red"] == ["disk"]
        assert "free on /" in payload["verdict"]["cause"]
        assert payload["metrics"]["disk_free_pct"] == 4.0

    def test_json_reports_unavailable_sensors(self, fake_machine):
        fake_machine(f.headless())
        payload = json.loads(runner.invoke(app, OFFLINE + ["status", "--json"]).stdout)
        assert "battery" in payload["verdict"]["unavailable"]

    def test_status_shows_time_to_live_when_dying(self, fake_machine):
        fake_machine(f.bloated())
        result = runner.invoke(app, OFFLINE + ["status"])
        assert "dead in" in result.stdout


class TestRun:
    def test_bare_invocation_renders_the_pet(self, fake_machine):
        fake_machine(f.green())
        result = runner.invoke(app, OFFLINE)
        assert result.exit_code == EXIT_OK
        assert "tamagoctl" in result.stdout
        assert "insufferably content" in result.stdout

    def test_run_renders_the_mood_sprite(self, fake_machine):
        fake_machine(f.hangry())
        result = runner.invoke(app, OFFLINE + ["run", "--once"])
        assert "hangry" in result.stdout

    def test_sass_override_is_reported(self, fake_machine):
        fake_machine(f.green())
        result = runner.invoke(app, ["--no-network", "--sass", "0", "run", "--once"])
        assert "sass 0" in result.stdout

    def test_sass_is_clamped_by_the_parser(self, fake_machine):
        fake_machine(f.green())
        assert runner.invoke(app, OFFLINE + ["--sass", "9"]).exit_code != EXIT_OK


class TestConfigCommands:
    def test_config_path_creates_the_default_file(self, sandbox_home):
        result = runner.invoke(app, ["config", "path"])
        assert result.exit_code == EXIT_OK
        assert config_path().exists()
        assert "sass_level" in config_path().read_text()

    def test_config_show_lists_effective_values(self):
        result = runner.invoke(app, ["config", "show"])
        assert "sass_level" in result.stdout
        assert "decay_per_red_per_s" in result.stdout

    def test_a_corrupt_config_does_not_stop_the_pet(self, sandbox_home, fake_machine):
        sandbox_home.mkdir(parents=True, exist_ok=True)
        config_path().write_text("this is not toml {{{")
        fake_machine(f.green())
        assert runner.invoke(app, OFFLINE + ["status"]).exit_code == EXIT_OK


class TestTop:
    def test_top_names_the_worst_process_with_its_pid(self, monkeypatch):
        from tamagoctl.metrics import ProcInfo

        procs = [
            ProcInfo(pid=101, name="quiet-daemon", cpu_pct=0.2, rss_pct=0.1),
            ProcInfo(pid=4821, name="Chrome Helper", cpu_pct=800.0, rss_pct=31.0),
        ]
        monkeypatch.setattr(cli, "sample_processes", lambda interval: procs)
        result = runner.invoke(app, OFFLINE + ["top"])
        assert "Chrome Helper" in result.stdout
        assert "4821" in result.stdout

    def test_top_survives_a_machine_with_no_visible_processes(self, monkeypatch):
        monkeypatch.setattr(cli, "sample_processes", lambda interval: [])
        result = runner.invoke(app, OFFLINE + ["top"])
        assert result.exit_code == EXIT_OK
        assert "Nothing is running" in result.stdout
