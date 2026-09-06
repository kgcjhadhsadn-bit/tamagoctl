# tamagoctl

A terminal pet whose mood and health are driven by real system metrics.
Tamagotchi meets a passive-aggressive sysadmin.

It polls your machine every two seconds, forms an opinion, and tells you about
it. If you neglect the machine badly enough for long enough, the pet dies and
leaves a tombstone naming the metric that killed it.

```
     .-------.        name Inode  gen 1
    /  >   <  \       mood constipated
   |   -----   |    health [=================-------]  69.5
    \    _    /        age 41d
     '-------'     because Disk 7.0% free on / (21.4GB)
```

## Install

```sh
pipx install .                  # from a clone
pipx install git+https://github.com/kgcjhadhsadn-bit/tamagoctl
```

Python 3.11 or newer. Three dependencies: `psutil`, `rich`, `typer`. No daemon
— it polls while it is running and remembers where it left off.

For development:

```sh
git clone https://github.com/kgcjhadhsadn-bit/tamagoctl && cd tamagoctl
pip install -e ".[dev]"
pytest
```

## Moods

Each state has its own sprite. Several metrics go red at once constantly, so
moods are **priority-ordered**, worst first:

| # | mood | trigger |
|---|------|---------|
| 1 | `hangry` | battery < 20% and unplugged |
| 2 | `constipated` | disk free < 10% |
| 3 | `bloated` | RAM > 90% |
| 4 | `sweating` | CPU > 85% sustained for 60s |
| 5 | `dizzy` | packet loss, or > 150ms to 1.1.1.1 |
| 6 | `smug` | uptime > 7 days (it has seen things) |
| 7 | `insufferably content` | everything green |

The order is the design. Low battery means the machine is about to stop
existing; a full disk breaks writes; RAM and CPU are recoverable; latency is
someone else's fault. **`smug` is an attitude, not an injury** — long uptime
never costs health, and never makes `tamagoctl status` exit non-zero.

Health runs 0–100. It drains at 0.05/s for **each** red metric and regenerates
at 0.02/s only while everything is green, so one red metric kills a
full-health pet in about 33 minutes and three do it in about 11. Recovery is
deliberately slower than damage. All of it is configurable.

## Commands

| command | what it does |
|---------|--------------|
| `tamagoctl` | open the live pet (default) |
| `tamagoctl run` | the same thing, explicitly |
| `tamagoctl top` | name the single worst-behaved process and roast it personally |
| `tamagoctl status` | one-shot report, no TUI, script-friendly |
| `tamagoctl status --json` | the same as machine-readable JSON |
| `tamagoctl graveyard` | list the pets that did not make it |
| `tamagoctl revive` | hatch a new pet that knows about its predecessor |
| `tamagoctl config show` | print the effective configuration |
| `tamagoctl config path` | print the config file location |

Global flags go **before** the subcommand, the way `git` and `docker` do it:

```sh
tamagoctl --sass 0 status        # override sass for one run
tamagoctl --no-network run       # skip the latency check entirely
```

`status` puts the verdict in its exit code, so it works in a monitoring script:

| code | meaning |
|------|---------|
| `0` | everything within thresholds |
| `1` | at least one metric is red |
| `2` | the pet is dead |

## Sample session

The pet below is rendered by the real code path; the metrics behind it are
fabricated, because a healthy machine only ever shows you one mood.

```
$ tamagoctl
╭─ tamagoctl :: Inode ───────────────────────────────────────────────────────╮
│                                                                            │
│       .-------.        name Inode  gen 1                                   │
│      /  >   <  \       mood constipated                                    │
│     |   -----   |    health [=================-------]  69.5               │
│      \    _    /        age 41d                                            │
│       '-------'     because Disk 7.0% free on / (21.4GB)                   │
│                                                                            │
│  CPU 91%  RAM 93%  DISK 7% free  NET 18ms  UP 9d                           │
│                                                                            │
│  ╭─ feed ───────────────────────────────────────────────────────────────╮  │
│  │                                                                      │  │
│  │                                                                      │  │
│  │                                                                      │  │
│  │ 22:13:20 11d. I would say I missed you, but I would have had to be   │  │
│  │          running.                                                    │  │
│  │ 22:13:20 7% free and you are still downloading something. Bold.      │  │
│  │ 22:13:34 7% free. The database has opinions about this.              │  │
│  │ 22:13:48 Something is about to fail to save, and it is going to be   │  │
│  │          something you cared about.                                  │  │
│  │ 22:14:02 I would log this but there is nowhere to log it to.         │  │
│  ╰──────────────────────────────────────────────────────────────────────╯  │
│                                                                            │
│  self 0.19% cpu  |  sass 2  |  ctrl-c to leave                             │
│                                                                            │
╰────────────────────────────────────────────────────────────────────────────╯

... a few minutes later ...
╭─ tamagoctl :: Inode ───────────────────────────────────────────────────────╮
│                                                                            │
│       .-------.        name Inode  gen 1                                   │
│      /  >   <  \       mood constipated                                    │
│     |   -----   |    health [=============-----------]  56.0               │
│      \    _    /        age 41d                                            │
│       '-------'     because Disk 7.0% free on / (21.4GB)                   │
│                                                                            │
│  CPU 91%  RAM 93%  DISK 7% free  NET 18ms  UP 9d                           │
│                                                                            │
│  ╭─ feed ───────────────────────────────────────────────────────────────╮  │
│  │ 22:13:48 / is 7% free. The next thing you install will be the        │  │
│  │          deciding vote.                                              │  │
│  │ 22:14:02 You could empty the trash. You will not. But you could.     │  │
│  │ 22:14:16 Free space: 21.4GB. Ambition: undiminished.                 │  │
│  │ 22:14:30 The last 21.4GB are load-bearing.                           │  │
│  │ 22:14:44 At 7% free, df becomes a horror genre.                      │  │
│  │ 22:14:58 7% free and you are still downloading something. Bold.      │  │
│  │ 22:15:12 There are old kernels on this machine. Several. From        │  │
│  │          previous eras.                                              │  │
│  │ 22:15:26 Log rotation is a thing people do. Other people.            │  │
│  ╰──────────────────────────────────────────────────────────────────────╯  │
│                                                                            │
│  self 0.19% cpu  |  sass 2  |  ctrl-c to leave                             │
│                                                                            │
╰────────────────────────────────────────────────────────────────────────────╯

$ tamagoctl top
Chrome Helper (Renderer)  pid 4821
  pid 4821. Name: Chrome Helper (Renderer). Priors: extensive.
  76.5% cpu   19.4% ram   3.1GB   you
  /Applications/Google Chrome.app/.../chrome --type=renderer

   pid    process    cpu     ram
   901    node      11.0%    8.7%
  1204    Docker     5.1%   13.9%

... some time later ...
╭─ tamagoctl :: Awk ─────────────────────────────────────────────────────────╮
│                                                                            │
│       .-------.        name Awk  gen 1                                     │
│      /  x   x  \       mood dead                                           │
│     |     _     |    health [------------------------]   0.0               │
│      \  _____  /        age 41d                                            │
│       '-------'     because Disk 0.4% free on / (1.2GB)                    │
│                                                                            │
│  CPU 91%  RAM 93%  DISK 0% free  NET 18ms  UP 9d                           │
│                                                                            │
│  ╭─ feed ───────────────────────────────────────────────────────────────╮  │
│  │                                                                      │  │
│  │ 22:13:20 It has been 41d. I was starting to think you had been       │  │
│  │          promoted.                                                   │  │
│  │ 22:13:20 The filesystem is 0% free and everything you own is in one  │  │
│  │          directory.                                                  │  │
│  │ 22:13:34 41d old. Killed by disk. I want that on the record.         │  │
│  │ 22:13:34 Epitaph: There was nowhere left to write this.              │  │
│  │ 22:13:34 Run `tamagoctl revive` when you are ready.                  │  │
│  │ 22:13:48 There is nothing to monitor. There is nothing to monitor    │  │
│  │          for.                                                        │  │
│  │ 22:14:02 The graveyard has a new entry. It has your machine's name   │  │
│  │          on it.                                                      │  │
│  ╰──────────────────────────────────────────────────────────────────────╯  │
│                                                                            │
│  self 0.19% cpu  |  sass 2  |  ctrl-c to leave                             │
│                                                                            │
╰────────────────────────────────────────────────────────────────────────────╯

$ tamagoctl graveyard
 __________________
/                  \
|      R.I.P.      |
|                  |
|   Awk (gen 1)    |
|__________________|
There was nowhere left to write this.

  gen    name    lived      killed by    cause
    1    Awk     41d        disk         Disk 0.4% free on / (1.2GB)

$ tamagoctl revive
Tmux, generation 2, 100/100.
Successor to Awk, who died of: Disk 0.4% free on / (1.2GB)
```

## Configuration

`~/.tamagoctl/config.toml` is written with comments on first run. Every key is
optional, and a broken value is ignored rather than stopping the pet from
starting.

```toml
sass_level = 2                  # 0 supportive .. 3 unhinged
refresh_s = 2.0                 # TUI tick
self_report_interval_s = 600.0  # how often it apologises for its own CPU usage
process_scan_interval_s = 10.0  # process enumeration cadence
feed_lines = 8

[thresholds]
cpu_pct = 85.0
cpu_sustain_s = 60.0
ram_pct = 90.0
disk_free_pct = 10.0
latency_ms = 150.0
battery_pct = 20.0
uptime_days = 7.0

[health]
max_health = 100.0
decay_per_red_per_s = 0.05
regen_per_s = 0.02
critical_health = 25.0

[network]
enabled = true
host = "1.1.1.1"
port = 443
probes = 3
timeout_s = 1.0
interval_s = 30.0
```

### Sass levels

**Level 0 is a real system monitor.** If you actually want a monitor and not a
personality, set `sass_level = 0`: you get the sprite, the health bar and the
metric strip, and the feed becomes a plain log that reports state changes and a
periodic summary. No jokes.

```
09:14:22  Disk 7.0% free on / (21.4GB)
09:14:52  CPU 91.0%  RAM 93.0% (14.9GB of 16.0GB)  Disk 7.0% free on /  1.1.1.1 at 18ms
```

Levels 1–3 escalate from dry to unhinged. Each mood has 23–25 lines, weighted,
with nothing repeating inside the last 10 lines said — and that history is
persisted, so restarting does not reset it.

### Files

Everything lives under `~/.tamagoctl/`:

```
config.toml      settings
state.json       the current pet (health, mood, lineage, comment history)
graveyard/       one JSON tombstone per dead pet
```

Set `TAMAGOCTL_HOME` to move all of it, or to keep more than one pet.

## Death and the graveyard

At zero health the pet dies and a tombstone is written naming the metric that
killed it, the full metric snapshot at the time, the lifespan, and an epitaph
drawn from that specific killer's set. Death is irreversible within a life;
green metrics do not bring anyone back.

```sh
tamagoctl graveyard     # who died, how long they lived, what got them
tamagoctl revive        # generation N+1, carrying a record of its predecessor
```

The new pet knows about the one before it and mentions it occasionally:

```
Awk lasted 41d. I am not competitive, but I am aware of the number.
Awk is in the graveyard and I am in the terminal. We each made choices.
```

`revive` refuses to replace a living pet unless you pass `--force`, and a
forced burial is recorded honestly.

## Cross-platform behaviour

Runs on Linux, macOS and Windows. Every metric is optional, and a missing one
degrades rather than crashing:

| unavailable | consequence |
|-------------|-------------|
| no battery (desktop, server, VM) | `hangry` is unreachable, `PWR` is hidden |
| network check disabled | `dizzy` is unreachable |
| any sensor psutil cannot read | that check reports as unavailable and is never red |

Comment lines follow the same rule: a line is only eligible if the machine can
supply every fact it references. The Chrome-tab joke simply never fires on a
machine with no Chrome, and the space-heater line never fires on a desktop.

Sprites are 7-bit ASCII on purpose — Windows consoles still default to code
pages that mangle anything cleverer.

## CPU usage

Under 1% is a hard requirement and also the running joke. Measured cost is
**3.8ms of CPU per tick including a full render — 0.19% of a 2s tick.**
`tests/test_session.py` asserts the budget, so a regression fails the suite.

How it stays there: a single non-blocking `cpu_percent` sample per tick,
process enumeration cached on a 10s cadence instead of running every tick, the
latency probe on its own 30s cadence in a background thread so the render loop
never blocks on it, and `rich.Live` with auto-refresh off so the screen
repaints only when there is new data.

Every ten minutes it tells you what it is costing you, apologetically.

## Network

The latency check is the only network call the program makes. It is a TCP
connect to `1.1.1.1:443` — three probes every 30 seconds, reporting the median
and whether any failed. Not ICMP: raw sockets need root on Linux and `ping`
output parsing differs on every platform this targets.

Change the host with `[network] host`, or turn it off entirely with
`enabled = false` or `--no-network`.

## Design

The mood engine is pure. `mood.evaluate()` and `health.step()` import no clock,
no `psutil` and no randomness — everything arrives as an argument, so the whole
simulation can be driven from fabricated metrics.

```
metrics.py   all system I/O lives here; nothing below it touches psutil
probe.py     the latency check, on a background thread
mood.py      PURE: (Metrics, Config) -> Verdict
health.py    PURE: decay, regeneration, death
lines.py     the comment banks
comments.py  facts, weighted picking, the narrators
session.py   the loop: timing, the feed, persistence
tui.py       rendering; a pure function of (state, tick, feed)
graveyard.py tombstones
cli.py       the command surface
```

`271 tests` cover every mood, both sides of every threshold, the full
priority ordering, missing-sensor degradation, decay and regeneration, death
and lineage, the no-repeat window, and the CPU budget.

```sh
pytest                  # all of it, ~2 seconds
pytest tests/test_mood.py -v
```

## Licence

MIT.
