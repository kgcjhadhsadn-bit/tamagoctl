"""The command line surface."""

from __future__ import annotations

import json
import time
from dataclasses import replace
from typing import Optional

import psutil
import typer
from rich.console import Console
from rich.table import Table
from rich.text import Text

from tamagoctl import comments, fmt, graveyard as graveyard_mod, roast, session, sprites, state as state_mod
from tamagoctl.config import (
    Config,
    NetworkConfig,
    clamp_sass,
    config_path,
    ensure_config,
    home,
    load_config,
)
from tamagoctl.health import Tick, step, time_to_live
from tamagoctl.metrics import Metrics, one_shot, sample_processes
from tamagoctl.mood import LABELS, Mood
from tamagoctl.tui import health_bar, view

app = typer.Typer(
    add_completion=False,
    no_args_is_help=False,
    rich_markup_mode="rich",
    help="A terminal pet whose mood and health are driven by real system metrics.",
)
config_app = typer.Typer(add_completion=False, help="Inspect configuration.")
app.add_typer(config_app, name="config")

console = Console()
err_console = Console(stderr=True)

EXIT_OK = 0
EXIT_UNHAPPY = 1
EXIT_DEAD = 2


def resolve_config(sass: int | None = None, no_network: bool = False) -> Config:
    """Load config.toml, writing the commented default on first run."""
    ensure_config()
    cfg = load_config()
    if sass is not None:
        cfg = replace(cfg, sass_level=clamp_sass(sass))
    if no_network:
        cfg = replace(cfg, network=replace(cfg.network, enabled=False))
    return cfg


def _one_shot_tick(cfg: Config) -> tuple[state_mod.PetState, Tick, Metrics]:
    """Sample once and advance the simulation by the real elapsed absence."""
    pet, _ = state_mod.load_or_create()
    metrics = one_shot(cfg)
    # A one-shot advances health by the render interval only. Time spent with
    # tamagoctl closed is not held against you; the guilt trip covers that.
    tick = step(metrics, pet.health, cfg.refresh_s, cfg)
    return pet, tick, metrics


@app.callback(invoke_without_command=True)
def default(
    ctx: typer.Context,
    sass: Optional[int] = typer.Option(
        None, "--sass", "-s", min=0, max=3,
        help="Override sass_level for this run: 0 supportive, 3 unhinged.",
    ),
    no_network: bool = typer.Option(
        False, "--no-network", help="Skip the latency check entirely."
    ),
) -> None:
    ctx.obj = {"sass": sass, "no_network": no_network}
    if ctx.invoked_subcommand is None:
        ctx.invoke(run, ctx=ctx)


@app.command()
def run(
    ctx: typer.Context,
    once: bool = typer.Option(False, "--once", help="Render a single frame and exit."),
    ticks: Optional[int] = typer.Option(
        None, "--ticks", min=1, hidden=True, help="Stop after N refreshes."
    ),
) -> None:
    """Open the live pet. This is the default when you run `tamagoctl`."""
    opts = ctx.obj or {}
    cfg = resolve_config(opts.get("sass"), opts.get("no_network", False))

    if once:
        pet, tick, metrics = _one_shot_tick(cfg)
        console.print(view(pet, tick, metrics, cfg, feed=[]))
        return

    sess = session.build(cfg)
    frame = sess.run(console, max_ticks=ticks)
    console.print(_farewell(frame))


def _farewell(frame: "session.Frame") -> str:
    return (
        f"\n[dim]{frame.pet.name} is at {frame.tick.health:.0f}/100 and "
        f"{LABELS[frame.tick.mood]}. State saved.[/dim]"
    )


@app.command()
def status(
    ctx: typer.Context,
    as_json: bool = typer.Option(False, "--json", help="Machine-readable output."),
) -> None:
    """One-shot report, no TUI. Exits 1 if a metric is red, 2 if the pet is dead."""
    opts = ctx.obj or {}
    cfg = resolve_config(opts.get("sass"), opts.get("no_network", False))
    pet, tick, metrics = _one_shot_tick(cfg)

    if as_json:
        console.print_json(json.dumps(_status_payload(pet, tick, metrics)))
    else:
        console.print(view(pet, tick, metrics, cfg))
        for check in tick.verdict.checks:
            style = "red" if check.red else "yellow" if check.triggered else "dim"
            marker = "!" if check.triggered else " "
            console.print(f"  [{style}]{marker} {check.detail}[/{style}]")
        ttl = time_to_live(tick.health, tick.verdict, cfg)
        if ttl is not None:
            console.print(f"\n  [red]dead in {fmt.duration(ttl)} at this rate[/red]")

    if not pet.alive:
        raise typer.Exit(EXIT_DEAD)
    raise typer.Exit(EXIT_UNHAPPY if tick.verdict.red else EXIT_OK)


def _status_payload(pet: state_mod.PetState, tick: Tick, metrics: Metrics) -> dict:
    return {
        "pet": {
            "name": pet.name,
            "generation": pet.generation,
            "health": round(tick.health, 2),
            "mood": str(tick.mood),
            "label": LABELS[tick.mood],
            "alive": tick.health > 0,
            "age_s": round(pet.age_s(metrics.now), 1),
        },
        "verdict": {
            "red": list(tick.verdict.red),
            "triggered": list(tick.verdict.triggered),
            "unavailable": list(tick.verdict.unavailable),
            "cause": tick.verdict.cause,
        },
        "metrics": {
            "cpu_pct": metrics.cpu_pct,
            "cpu_hot_for_s": round(metrics.cpu_hot_for, 1),
            "ram_pct": metrics.ram_pct,
            "disk_free_pct": metrics.disk_free_pct,
            "latency_ms": metrics.latency_ms,
            "packet_loss": metrics.packet_loss,
            "battery_pct": metrics.battery_pct,
            "power_plugged": metrics.power_plugged,
            "uptime_s": round(metrics.uptime_s, 1) if metrics.uptime_s else None,
            "self_cpu_pct": round(metrics.self_cpu_pct, 3),
        },
    }


@app.command()
def top(
    ctx: typer.Context,
    interval: float = typer.Option(
        0.6, "--interval", "-i", min=0.05, max=5.0,
        help="Seconds between the two CPU samples.",
    ),
    limit: int = typer.Option(5, "--limit", "-n", min=1, max=25, help="Runners-up to list."),
) -> None:
    """Name the single worst-behaved process and take it personally."""
    import os

    opts = ctx.obj or {}
    cfg = resolve_config(opts.get("sass"), opts.get("no_network", False))
    cpu_count = psutil.cpu_count() or 1

    with console.status("[dim]watching everyone...[/dim]", spinner="dots"):
        procs = sample_processes(interval)
    offender = roast.pick_worst(procs, cpu_count, exclude_pids={os.getpid()})

    if offender is None:
        console.print("[dim]Nothing is running. Suspicious, but nothing is running.[/dim]")
        raise typer.Exit(EXIT_OK)

    proc = offender.proc
    header = Text()
    header.append(proc.name, style="bold red")
    header.append(f"  pid {proc.pid}", style="dim")
    console.print(header)
    console.print(f"  [italic]{comments.roast(offender, cfg)}[/italic]")
    console.print(
        f"  [red]{offender.cpu_share:.1f}%[/red] cpu   "
        f"[red]{offender.ram_share:.1f}%[/red] ram"
        + (f"   {fmt.size(proc.rss_bytes)}" if proc.rss_bytes else "")
        + (f"   [dim]{proc.username}[/dim]" if proc.username else "")
    )
    if proc.cmdline:
        line = proc.cmdline if len(proc.cmdline) <= 100 else proc.cmdline[:97] + "..."
        console.print(f"  [dim]{line}[/dim]")

    runners = [p for p in roast.rank(procs, cpu_count, limit + 1) if p.pid != proc.pid][:limit]
    if runners:
        table = Table(box=None, padding=(0, 2), show_header=True, header_style="dim")
        table.add_column("pid", justify="right", style="dim")
        table.add_column("process")
        table.add_column("cpu", justify="right")
        table.add_column("ram", justify="right")
        for other in runners:
            table.add_row(
                str(other.pid), other.name,
                f"{roast.cpu_share(other, cpu_count):.1f}%",
                f"{(other.rss_pct or 0.0):.1f}%",
            )
        console.print()
        console.print(table)


@app.command()
def graveyard(
    limit: int = typer.Option(20, "--limit", "-n", min=1, help="How many to list."),
) -> None:
    """List the pets that did not make it."""
    stones = graveyard_mod.graves()
    if not stones:
        console.print("[dim]The graveyard is empty. So far.[/dim]")
        return

    console.print(sprites.headstone(stones[-1].name, stones[-1].generation))
    console.print(f"[italic dim]{stones[-1].epitaph}[/italic dim]\n")

    table = Table(box=None, padding=(0, 2), header_style="dim")
    table.add_column("gen", justify="right", style="dim")
    table.add_column("name")
    table.add_column("lived")
    table.add_column("killed by", style="red")
    table.add_column("cause", style="dim", overflow="fold")
    for stone in list(reversed(stones))[:limit]:
        table.add_row(
            str(stone.generation), stone.name, stone.lifespan,
            stone.killer or "-", stone.cause,
        )
    console.print(table)

    hidden = max(0, len(stones) - limit)
    if hidden:
        console.print(f"\n[dim]{hidden} more not shown.[/dim]")


@app.command()
def revive(
    ctx: typer.Context,
    force: bool = typer.Option(
        False, "--force", help="Replace a pet that is still alive. It will be buried."
    ),
) -> None:
    """Hatch a new pet. It will know about its predecessor."""
    opts = ctx.obj or {}
    cfg = resolve_config(opts.get("sass"), opts.get("no_network", False))
    current, is_new = state_mod.load_or_create()

    if is_new:
        state_mod.save(current)
        console.print(f"There was no pet. There is now: [bold]{current.name}[/bold].")
        return

    if current.alive and not force:
        console.print(
            f"[yellow]{current.name} is still alive[/yellow] at "
            f"{current.health:.0f}/100. Use [bold]--force[/bold] to replace them anyway."
        )
        raise typer.Exit(EXIT_UNHAPPY)

    predecessor = _bury_if_needed(current, cfg, force)
    fresh = state_mod.new_pet(
        generation=current.generation + 1, predecessor=predecessor
    )
    state_mod.save(fresh)

    console.print(
        f"[bold]{fresh.name}[/bold], generation {fresh.generation}, "
        f"{fresh.health:.0f}/100."
    )
    if predecessor:
        console.print(
            f"[dim]Successor to {predecessor['name']}, "
            f"who died of: {predecessor.get('cause', 'unknown')}[/dim]"
        )


def _bury_if_needed(current: state_mod.PetState, cfg: Config, forced: bool) -> dict | None:
    """A pet that died in a session already has a grave; a forced one does not."""
    existing = graveyard_mod.find_by_birth(current.born_at)
    if existing is not None:
        return existing.as_predecessor()

    metrics = one_shot(cfg)
    tick = step(metrics, max(current.health, 0.0), 0.0, cfg)
    stone = graveyard_mod.make(current, tick, metrics)
    if forced and current.alive:
        stone = replace(
            stone,
            cause="Replaced by their owner while still alive.",
            killer=None,
            epitaph="Did nothing wrong.",
        )
    graveyard_mod.bury(stone)
    return stone.as_predecessor()


@config_app.command("path")
def config_path_cmd() -> None:
    """Print the config file location."""
    console.print(str(ensure_config()))


@config_app.command("show")
def config_show() -> None:
    """Print the effective configuration."""
    path = ensure_config()
    cfg = load_config()
    console.print(f"[dim]{path}[/dim]\n")
    table = Table(box=None, padding=(0, 2), show_header=False)
    table.add_column(style="dim", justify="right")
    table.add_column()
    table.add_row("sass_level", f"{cfg.sass_level}")
    table.add_row("refresh_s", f"{cfg.refresh_s}")
    table.add_row("home", str(home()))
    for section, values in (
        ("thresholds", cfg.thresholds),
        ("health", cfg.health),
        ("network", cfg.network),
    ):
        table.add_row("", "")
        table.add_row(f"[{section}]", "")
        for key in values.__dataclass_fields__:
            table.add_row(key, str(getattr(values, key)))
    console.print(table)


def main() -> None:
    app()


if __name__ == "__main__":
    main()
