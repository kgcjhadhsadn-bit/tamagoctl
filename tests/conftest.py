import os
import sys
from pathlib import Path

import pytest

SRC = Path(__file__).resolve().parents[1] / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))


@pytest.fixture(autouse=True)
def sandbox_home(tmp_path, monkeypatch):
    """Never touch the real ~/.tamagoctl during tests."""
    home = tmp_path / "tamagoctl-home"
    monkeypatch.setenv("TAMAGOCTL_HOME", str(home))
    yield home
