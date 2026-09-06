"""Rendering. Builds a rich renderable from a tick; the live loop drives it.

The view is a pure function of (state, tick, feed) so `status` and the live TUI
render through exactly the same code path.
"""

from __future__ import annotations

from rich.align import Align
from rich.console import Group, RenderableType
from rich.panel import Panel
from rich.table import Table
from rich.text import Text

from tamagoctl import fmt, sprites
from tamagoctl.config import Config
from tamagoctl.health import Tick
from tamagoctl.metrics import Metrics
from tamagoctl.mood import LABELS, Mood, Verdict
from tamagoctl.state import PetState

HEALTH_STYLES = ((60.0, "green"), (30.0, "yellow"), (0.0, "red"))


def health_style(health: float) -> str:
    for floor, style in HEALTH_STYLES:
        if health >= floor:
            return style
    return "red"


def health_bar(health: float, width: int = 24, max_health: float = 100.0) -> Text:
    ratio = max(0.0, min(1.0, health / max_health if max_health else 0.0))
    filled = int(round(ratio * width))
    if health > 0 and filled == 0:
        filled = 1
    style = health_style(health)
    bar = Text()
    bar.append("[", style="dim")
    bar.append("=" * filled, style=style)
    bar.append("-" * (width - filled), style="dim")
    bar.append("] ", style="dim")
    bar.append(f"{health:5.1f}", style=style)
    return bar


def _short(key: str, metrics: Metrics, cfg: Config) -> tuple[str, str] | None:
    """Compact label/value for the metric strip. None means 'do not show'."""
    m = metrics
    if key == "cpu":
        return ("CPU", fmt.pct(m.cpu_pct, 0) if m.cpu_pct is not None else "--")
    if key == "ram":
        return ("RAM", fmt.pct(m.ram_pct, 0) if m.ram_pct is not None else "--")
    if key == "disk":
        if m.disk_free_pct is None:
            return ("DISK", "--")
        return ("DISK", f"{m.disk_free_pct:.0f}% free")
    if key == "net":
        if not cfg.network.enabled:
            return ("NET", "off")
        if m.packet_loss:
            return ("NET", "loss")
        if m.latency_ms is None:
            return ("NET", "...")
        return ("NET", f"{m.latency_ms:.0f}ms")
    if key == "battery":
        if m.battery_pct is None:
            return None  # desktops do not need an empty battery column
        suffix = "AC" if m.power_plugged else "batt"
        return ("PWR", f"{m.battery_pct:.0f}% {suffix}")
    if key == "uptime":
        return ("UP", fmt.duration(m.uptime_s, precision=1))
    return None


# The mood engine orders checks worst-first; the strip reads better in the
# order people are used to scanning.
DISPLAY_ORDER = ("cpu", "ram", "disk", "net", "battery", "uptime")


def metric_strip(metrics: Metrics, verdict: Verdict, cfg: Config) -> Table:
    grid = Table.grid(padding=(0, 2))
    cells: list[Text] = []
    ordered = [c for key in DISPLAY_ORDER if (c := verdict.check(key)) is not None]
    for check in ordered:
        pair = _short(check.key, metrics, cfg)
        if pair is None:
            continue
        label, value = pair
        style = "red bold" if check.red else "yellow" if check.triggered else (
            "dim" if not check.available else "white"
        )
        cell = Text()
        cell.append(f"{label} ", style="dim")
        cell.append(value, style=style)
        cells.append(cell)
    for _ in cells:
        grid.add_column(no_wrap=True)
    grid.add_row(*cells)
    return grid


def status_block(state: PetState, tick: Tick, metrics: Metrics, cfg: Config) -> Table:
    mood = tick.mood
    grid = Table.grid(padding=(0, 1))
    grid.add_column(justify="right", style="dim", no_wrap=True)
    grid.add_column(justify="left")

    grid.add_row("name", Text(f"{state.name}", style="bold") + Text(
        f"  gen {state.generation}", style="dim"))
    grid.add_row("mood", Text(LABELS[mood], style=f"bold {sprites.color(mood)}"))
    grid.add_row("health", health_bar(tick.health, max_health=cfg.health.max_health))
    grid.add_row("age", Text(fmt.duration(state.age_s(metrics.now)), style="white"))

    if tick.verdict.red:
        reason = tick.verdict.cause or ""
        grid.add_row("because", Text(reason, style="red"))
    elif mood is Mood.SMUG:
        grid.add_row("because", Text(tick.verdict.check("uptime").detail, style="blue"))

    if state.predecessor:
        pred = state.predecessor
        grid.add_row(
            "successor to",
            Text(f"{pred.get('name', '?')} (gen {pred.get('generation', '?')})", style="dim"),
        )
    return grid


def head(state: PetState, tick: Tick, metrics: Metrics, cfg: Config) -> Table:
    mood = tick.mood
    art = Text(sprites.sprite(mood), style=sprites.color(mood))
    grid = Table.grid(padding=(0, 3), expand=True)
    grid.add_column(no_wrap=True)
    grid.add_column(ratio=1)
    grid.add_row(Align.center(art, vertical="middle"),
                 Align.left(status_block(state, tick, metrics, cfg), vertical="middle"))
    return grid


# Session.push formats every entry as "HH:MM:SS" plus two spaces, so the split
# point is fixed and the timestamp can go in its own column.
STAMP_WIDTH = 8
STAMP_GAP = 2


def feed_panel(lines: list[str], height: int) -> Panel:
    """Timestamps in their own column so wrapped text aligns under the message."""
    grid = Table.grid(padding=(0, 1), expand=True)
    grid.add_column(width=STAMP_WIDTH, no_wrap=True, style="dim")
    grid.add_column(ratio=1, overflow="fold")

    visible = lines[-height:] if height > 0 else lines
    for _ in range(max(0, height - len(visible))):
        grid.add_row("", "")
    for index, line in enumerate(visible):
        stamp, text = line[:STAMP_WIDTH], line[STAMP_WIDTH + STAMP_GAP:]
        newest = index == len(visible) - 1
        grid.add_row(stamp, Text(text, style="white" if newest else "dim"))

    return Panel(grid, title="[dim]feed[/dim]", title_align="left",
                 border_style="dim", padding=(0, 1))


def view(
    state: PetState,
    tick: Tick,
    metrics: Metrics,
    cfg: Config,
    feed: list[str] | None = None,
) -> RenderableType:
    """The whole screen. Pure - hand it a tick and it hands you a renderable."""
    mood = tick.mood
    parts: list[RenderableType] = [
        head(state, tick, metrics, cfg),
        Text(""),
        metric_strip(metrics, tick.verdict, cfg),
    ]
    if feed is not None:
        parts += [Text(""), feed_panel(feed, cfg.feed_lines)]

    footer = Text()
    footer.append(f"self {metrics.self_cpu_pct:.2f}% cpu", style="dim")
    footer.append("  |  ", style="dim")
    footer.append(f"sass {cfg.sass_level}", style="dim")
    if not cfg.network.enabled:
        footer.append("  |  net check off", style="dim")
    footer.append("  |  ctrl-c to leave", style="dim")
    parts += [Text(""), footer]

    return Panel(
        Group(*parts),
        title=f"[bold]tamagoctl[/bold] [dim]:: {state.name}[/dim]",
        title_align="left",
        border_style=sprites.color(mood),
        padding=(1, 2),
    )
