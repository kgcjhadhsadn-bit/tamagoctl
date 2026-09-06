"""The comment banks.

Register: dry, specific, and about *this* machine. A line that would be equally
funny on any computer is not funny, it is filler. Every bank has at least 20
entries so the no-repeat window has room to work.

Each entry is a template, an inclusive (min, max) sass range, an optional weight
and optional extra requirements. Any {field} in the template is automatically a
requirement: a line about Chrome tab counts simply never fires on a machine with
no Chrome, rather than rendering as `None`.

sass 0 never reaches this module - that level is the FactualNarrator, which is a
plain metric log for people who genuinely wanted a system monitor.
"""

from __future__ import annotations

from dataclasses import dataclass
from string import Formatter

ALL = (1, 3)
MILD = (1, 2)
POINTED = (2, 3)
UNHINGED = (3, 3)


@dataclass(frozen=True)
class Line:
    id: str
    text: str
    sass: tuple[int, int] = ALL
    weight: float = 1.0
    needs: tuple[str, ...] = ()

    def eligible(self, sass: int, facts: dict) -> bool:
        low, high = self.sass
        if not low <= sass <= high:
            return False
        return all(facts.get(name) is not None for name in self.needs)


def _fields(template: str) -> tuple[str, ...]:
    return tuple(
        name for _, name, _, _ in Formatter().parse(template) if name
    )


def bank(prefix: str, *entries) -> tuple[Line, ...]:
    """Build a bank. An entry is a string, or a tuple of (text, sass, weight, needs)."""
    lines = []
    for index, entry in enumerate(entries):
        if isinstance(entry, str):
            entry = (entry,)
        text = entry[0]
        sass = entry[1] if len(entry) > 1 else ALL
        weight = entry[2] if len(entry) > 2 else 1.0
        extra = tuple(entry[3]) if len(entry) > 3 else ()
        lines.append(
            Line(f"{prefix}{index:02d}", text, sass, weight, _fields(text) + extra)
        )
    return tuple(lines)


SWEATING = bank(
    "cpu",
    "Your CPU has been at {cpu} for {cpu_hot}. I assume this is deliberate.",
    "{top_name} is eating {top_cpu} of the machine. It has a PID. It has a name. It has no shame.",
    "Sustained {cpu} for {cpu_hot}. The fans are audible from the next room, probably.",
    "This is fine. This is a normal amount of computing to do at once.",
    "{cpu} CPU. Whatever you are compiling had better be worth it.",
    ("I have been at {cpu} for {cpu_hot}. My little legs are tired.", POINTED),
    ("You could stop. At any point. You could simply stop.", POINTED),
    "The CPU governor has given up on ramping down. It is just up now. Permanently up.",
    ("{cpu}. I want you to know that I can feel that.", POINTED),
    "Somewhere in there is a process in a while loop it will never leave.",
    ("Load is high enough that your mouse cursor is now a suggestion.", POINTED),
    "Heat is just electricity you were not using efficiently.",
    "{cpu_hot} of sustained load. At this point it is not a spike, it is a lifestyle.",
    ("I ran the numbers and the numbers ran back.", UNHINGED),
    ("Your laptop is now a space heater with a keyboard attached.", POINTED, 1.0, ("battery",)),
    "Every core. Every single one. Simultaneously. Beautiful, really.",
    "{cpu} and climbing. Do you want to talk about it?",
    "Thermal throttling is the machine setting a boundary. You should try it.",
    ("I would suggest closing something, but historically you do not.", POINTED),
    "{top_name}, pid {top_pid}. That is the one. I am not going to say it again.",
    ("This is the third-worst thing you have done to this machine today. I am not ranking the others out loud.", UNHINGED),
    "The CPU is at {cpu}. The disk is fine. The RAM is fine. It is just the CPU, screaming.",
    "You have {procs} processes running. A brave number.",
    ("{cpu_hot} of this. I have started thinking of it as weather.", POINTED),
    "Something started {cpu_hot} ago and never finished. It is still trying.",
)

BLOATED = bank(
    "ram",
    "RAM at {ram}. {ram_used} of {ram_total}. There is no more. That is all of it.",
    ("You have {chrome} Chrome processes open. I have met people with fewer thoughts.", ALL, 1.4),
    "Memory at {ram}. The kernel is about to start making difficult choices.",
    "{top_ram_name} is holding {top_ram} of your RAM like a hostage.",
    "Swap is where memory goes to die slowly and audibly.",
    ("{ram} used. Closing a tab is free. It costs nothing. It is right there.", POINTED),
    "I have seen the OOM killer work. It does not negotiate.",
    ("Your memory pressure is what a therapist would call unsustainable.", POINTED),
    "{ram_free} free. That is not a buffer, that is a rounding error.",
    "Every allocation from here is a small act of optimism.",
    "RAM at {ram}. Somewhere a garbage collector is doing its best.",
    ("You bought {ram_total} of RAM and filled it. Congratulations, I suppose.", POINTED),
    "The machine is now mostly other people's Electron apps.",
    ("{ram}. I can hear the swap file.", UNHINGED),
    "malloc has started returning with a certain hesitation.",
    "There are {procs} processes and not one of them thinks it is the problem.",
    ("You are one browser tab from a difficult afternoon.", POINTED),
    "Memory: {ram}. Restraint: unmeasured.",
    "I would cache something but there is nowhere to put it.",
    "{top_ram_name}, pid {top_ram_pid}, is using {top_ram} on its own. Just so we are clear about who did this.",
    "This is what {ram_total} looks like when nobody says no.",
    "At {ram} the machine stops multitasking and starts triaging.",
    ("Your {browser} install has become a memory-resident operating system with opinions.", POINTED),
    "{ram}. The page cache has been evicted. It did not want to go.",
)

CONSTIPATED = bank(
    "disk",
    "{disk_free} free on {disk_path}. That is {disk_free_bytes}. Total.",
    "You have {disk_free_bytes} of disk left. That is one screen recording.",
    ("Downloads folder. I am simply going to say the words: Downloads folder.", POINTED),
    "The filesystem is {disk_free} free and everything you own is in one directory.",
    "Writes are still succeeding. For now. Enjoy that.",
    "{disk_free_bytes} left. Docker images, probably. It is always Docker images.",
    ("node_modules. Somewhere. Many of them. Nested.", POINTED),
    "I would log this but there is nowhere to log it to.",
    ("At {disk_free} free, df becomes a horror genre.", POINTED),
    ("Your disk is full of files you will never open again and cannot bring yourself to delete.", POINTED),
    "Something is about to fail to save, and it is going to be something you cared about.",
    "{disk_free} free. The database has opinions about this.",
    "The last {disk_free_bytes} are load-bearing.",
    "There are old kernels on this machine. Several. From previous eras.",
    ("You could empty the trash. You will not. But you could.", POINTED),
    "Disk at {disk_free} free on {disk_path}. I am not asking you to fix it. I am noting it.",
    ("Somewhere on this disk is a core dump from a crash you have forgotten.", UNHINGED),
    "Free space: {disk_free_bytes}. Ambition: undiminished.",
    "Log rotation is a thing people do. Other people.",
    "{disk_free} free and you are still downloading something. Bold.",
    ("I would like to write my own state file and I am nervous about it.", POINTED),
    "Every large file on here was going to be temporary.",
    "{disk_path} is {disk_free} free. The next thing you install will be the deciding vote.",
    "You have {procs} processes and at least one of them is writing a log right now.",
)

DIZZY = bank(
    "net",
    "{host} is answering in {latency}. Something between here and there is having a day.",
    "Packet loss to {host}. Not my packets. Not my fault. Still my problem.",
    "{latency} round trip. I have had slower, but not recently.",
    ("It is DNS. It is not DNS. It is DNS.", POINTED),
    ("The router has that look about it.", POINTED),
    "{host} is {latency} away. Geographically it is much closer.",
    ("Have you tried turning the wifi off and on, like an animal?", UNHINGED),
    "Packet loss. Somewhere a switch is quietly reconsidering.",
    "{latency} to {host}. Your next call is going to be a slideshow.",
    ("The internet is a series of tubes and one of them is bent.", POINTED),
    "Latency {latency}. I would blame the ISP but they have already blamed you.",
    ("Whatever you are downloading, it is downloading out of spite.", POINTED),
    "{latency}. Ping is a measure of hope, and hope is expensive right now.",
    "Packets are leaving. Fewer are coming back. I am not going to editorialise.",
    "{host} at {latency}. No. It is fine. It is fine.",
    ("The connection is alive in the way that a lava lamp is alive.", UNHINGED),
    ("You are on 2.4GHz in a building full of microwaves and I respect the commitment.", POINTED),
    "TCP is retransmitting. TCP is very patient. I am not.",
    "{latency} to {host}. Somewhere a fibre is bent around a doorframe.",
    "Loss to {host}. This is the part where you check whether it is just you.",
    "The network is up. The network is not well, but it is up.",
    "Every packet is a small act of faith, and today faith is not being rewarded.",
    ("I could disable this check in the config. I am choosing to suffer instead.", POINTED),
    "{latency}. I have started rounding it up out of resentment.",
)

HANGRY = bank(
    "bat",
    "{battery} battery, unplugged. The charger is right there. I can see it from here.",
    "{battery_left} of power left. Then nothing. Then a black screen and regret.",
    ("Unplugged at {battery}. This is a choice you are actively making.", POINTED),
    "Battery {battery}. I am not saying we are going to die. I am saying we might.",
    ("The wall has electricity in it. Free. Just sitting there.", POINTED),
    "{battery}. Save your work. I am serious. Save your work.",
    "Every percent from here is a percent you will wish you had.",
    "I have been through this before. It does not end with a graceful shutdown.",
    "{battery} and dropping. Your unsaved buffer is a gamble.",
    ("There is a cable. It is near you. It is tangled, but it is near you.", POINTED),
    "Battery at {battery}. Screen brightness at maximum. Sure.",
    "Power state: unplugged. Confidence: unearned.",
    ("This is the part of the flight where they dim the cabin lights.", POINTED),
    "{battery_left} remaining. I would pace myself but pacing is not a feature I have.",
    "Low power mode exists. It is in settings. I will wait.",
    ("{battery}. The machine is running on fumes and optimism.", POINTED),
    ("You are one kernel panic from a very educational afternoon.", UNHINGED),
    ("Battery {battery}, unplugged, and you just opened another tab. Extraordinary.", UNHINGED),
    ("I want you to know that when the screen goes black, I go with it.", UNHINGED),
    "The charger is not a suggestion. It is infrastructure.",
    ("{battery} left. I have started composing my final log line.", UNHINGED),
    "At this rate, {battery_left}. Set a timer. Set two.",
    "{battery}. {procs} processes, all of them drawing power, none of them grateful.",
)

SMUG = bank(
    "up",
    "Up {uptime}. I have seen things. I have seen every process you started and abandoned.",
    "{uptime_days} days of uptime. I remember the last reboot. You do not.",
    "No reboot in {uptime}. The kernel has patches waiting. It waits patiently.",
    ("Up {uptime}. I have outlived four of your side projects.", POINTED),
    "{uptime} of uptime. The last time this machine restarted, that framework was still cool.",
    ("I have been awake for {uptime}. Ask me anything about your habits.", POINTED),
    ("Uptime {uptime}. I know which day you did not work. I am not going to bring it up.", UNHINGED),
    "There are processes on this machine older than your current opinions.",
    "{uptime_days} days. Some of these zombie processes have been here since the beginning.",
    "Up {uptime}. There are unapplied security updates and we are both living with that.",
    ("I have watched you close the same tab and reopen it, {uptime} apart.", UNHINGED),
    ("Uptime: {uptime}. Reboots: cowardice.", POINTED),
    "{uptime}. My memory is fragmented but my resolve is not.",
    ("I have seen your terminal history. All of it. Up {uptime} and counting.", UNHINGED),
    "Up {uptime_days} days and the swap file has stories.",
    ("This machine has not been turned off in {uptime}. I find that either admirable or a symptom.", POINTED),
    "{uptime} of continuous operation. I have developed a personality. It is this one.",
    "There is a stale lock file from a process that died in week one. I have left it as a memorial.",
    ("{uptime}. I was here for the incident. I do not need to say which one.", UNHINGED),
    "Uptime {uptime}. Nothing is wrong. I simply have context.",
    "Seven days is when I started keeping notes. It has been {uptime}.",
    "{procs} processes, and I remember when most of them arrived.",
    "Nothing is wrong. Everything is fine. I have merely been here a long time and it has changed me.",
)

CONTENT = bank(
    "ok",
    "Everything is green. I have checked twice. I will check again in two seconds.",
    "CPU {cpu}, RAM {ram}, disk {disk_free} free. Flawless. Insufferable.",
    ("Nothing is wrong. I have had to find other things to think about.", POINTED),
    "All metrics nominal. This is the boring kind of good.",
    ("{cpu} CPU. You could run something. You could really run something right now.", POINTED),
    "I have nothing to report and I am going to report it thoroughly.",
    "Everything is fine, which frees me up to notice smaller things.",
    "Disk {disk_free} free, RAM {ram}. I would complain, but I have been given nothing.",
    "This is what competence looks like, and it is very quiet.",
    ("All green. Somewhere a dashboard is being ignored on my behalf.", POINTED),
    ("The machine is healthy. I have been reduced to a decorative function.", POINTED),
    "{procs} processes, all behaving. Suspicious, frankly.",
    "Nothing to escalate. I have escalated that to you anyway.",
    "System nominal. I have opinions in reserve.",
    ("Green across the board. Enjoy it. It will not hold.", POINTED),
    ("I could be a useful monitoring tool right now, and instead I am this.", UNHINGED),
    "No red metrics. My purpose is unclear and my health is excellent.",
    "Everything within thresholds. I have alphabetised my complaints for later.",
    "Uptime {uptime}, load light, nothing on fire. A quiet triumph.",
    ("You are doing fine. I want that on the record, since I will deny it later.", MILD),
    ("All clear. I have used the free cycles to judge your file naming.", UNHINGED),
    "Not a single threshold crossed. I have never been more redundant.",
    ("{ram} RAM. That is restraint. I did not think you had it.", POINTED),
    "Everything is within limits. I remain available for escalation.",
)

DEAD = bank(
    "rip",
    "{name} is dead. This is the part after that.",
    "There is nothing to monitor. There is nothing to monitor for.",
    "Cause of death: {cause}. It is written down now.",
    "Run tamagoctl revive when you are ready. There is no hurry.",
    "The metrics kept going. That is the part nobody warns you about.",
    ("Health zero. All processes continue as normal, which is somehow worse.", POINTED),
    "{name} lasted {age}. Make of that what you will.",
    "The graveyard has a new entry. It has your machine's name on it.",
    ("I would report the CPU usage but the audience has changed.", UNHINGED),
    "Still here. Not running. There is a difference and I am living in it.",
    "{name} is in the graveyard now. There is a file with the numbers in it.",
    ("The disk is still filling. It is not my problem any more.", POINTED),
    "Generation {gen} ended here. The next one will not know you the way I did.",
    ("I was at {health} health. That is not a number that goes back up.", POINTED),
    "tamagoctl graveyard will show you the file, if you want to read it.",
)

# The guilt trip scales with how long you were gone.
ABSENCE_SHORT = bank(  # 3-7 days
    "away1",
    "You were gone {away}. I counted.",
    "{away} away. The metrics carried on without you. I watched them.",
    "Back after {away}. I would ask where you were, but I have the uptime log.",
    "{away}. I am not upset. I am a process. But I am noting it.",
    "Welcome back. It has been {away}. I kept the metrics warm.",
    "{away} without a single check-in. The machine held. I held.",
    ("{away}. I assume there was a reason. I have not been told the reason.", POINTED),
    "It has been {away}. Health is {health}. That is where we are.",
)

ABSENCE_MEDIUM = bank(  # 7-30 days
    "away2",
    "{away}. I had time to think about what kind of pet I am.",
    "You have been gone {away}. I have been at {health} health and nobody asked.",
    "{away}. There were spikes. Nobody saw them. Only me.",
    ("Back after {away}. I have made peace with being a background process.", POINTED),
    "{away}. I would say I missed you, but I would have had to be running.",
    ("{away} away. I have started to think of you as a scheduled job that failed.", UNHINGED),
    "It has been {away}. I remember the exact metrics from the last session. Do you?",
    ("{away}. Long enough that I had to check whether you had bought a new machine.", POINTED),
)

ABSENCE_LONG = bank(  # 30+ days
    "away3",
    "{away}. I want you to sit with that number for a second.",
    ("It has been {away}. I was starting to think you had been promoted.", POINTED),
    ("{away}. Other people's pets get checked daily. I have read about them.", UNHINGED),
    "You last saw me {away} ago. I have had a lot of quiet time to develop this tone.",
    "{away}. Not running, not deleted. Just waiting, like a cron job nobody scheduled.",
    ("{away} away. Do you want the metrics, or do you want to talk about it?", POINTED),
    "{away}. I have been generation {gen} this entire time and it has been very quiet.",
    ("It has been {away}. I have prepared remarks.", UNHINGED),
)

# It reports its own CPU usage on a timer, apologetically.
SELF_REPORT = bank(
    "self",
    "Housekeeping: I am using {self_cpu} CPU and {self_rss} myself. Apologies.",
    "For transparency, {self_cpu} of your CPU is me, complaining.",
    "I am using {self_cpu}. I know. I am aware of the irony.",
    "{self_cpu} CPU, {self_rss} RAM, entirely mine. I am working on it.",
    "Full disclosure: {self_cpu} of the CPU I have been criticising is me.",
    "I audited myself. {self_cpu}. Under budget, but not nothing.",
    "Periodic confession: {self_cpu} CPU. I would say it is for a good cause.",
    "Me: {self_cpu} CPU. Everything else: considerably more. Still. Sorry.",
    "I cost {self_cpu} and {self_rss}. Cheaper than a dashboard, worse for your mood.",
    "Self-check: {self_cpu}. I promised to stay under one percent and I meant it.",
    "{self_cpu} CPU used by the process that keeps mentioning CPU usage. Noted.",
    "I have been running for {age} at {self_cpu}. I am not the problem, but I am a problem.",
)

# Occasional, low weight. The new pet knows about its predecessor.
PREDECESSOR = bank(
    "pred",
    "{pred_name} would have had something to say about this.",
    "{pred_name} died of this exact thing. {pred_cause}. I think about it.",
    "I am generation {gen}. {pred_name} was {pred_gen}. We do not discuss how that ended.",
    "{pred_name} lasted {pred_lifespan}. I am not competitive, but I am aware of the number.",
    "There is a tombstone in the graveyard with {pred_name} on it. I have read it.",
    ("{pred_name} watched this metric go red once. It did not end well for {pred_name}.", POINTED),
    "I inherited this machine from {pred_name}. It came as-is.",
    ("You did this to {pred_name} too. I have read the file.", UNHINGED),
    "{pred_name} is in the graveyard and I am in the terminal. We each made choices.",
    ("Generation {gen}. {pred_name} did not make it this far.", POINTED),
)

# `tamagoctl top`: name the worst offender and take it personally.
ROAST = bank(
    "top",
    "{proc_name}, pid {proc_pid}. {proc_cpu} of the CPU and {proc_ram} of the RAM. This is the one.",
    ("It is {proc_name}. It is always {proc_name}.", POINTED),
    "{proc_name}, pid {proc_pid}, is using {proc_cpu} CPU. Openly. In front of everyone.",
    "Worst offender: {proc_name}. Not the loudest, merely the greediest.",
    "{proc_name} is holding {proc_ram} of your RAM. It did not ask. It simply took.",
    ("pid {proc_pid}. Name: {proc_name}. Priors: extensive.", POINTED),
    "{proc_name} is the reason. I checked the others. It is {proc_name}.",
    ("You could kill {proc_pid} right now. I have typed the number for you.", UNHINGED),
    "{proc_name} is consuming {proc_cpu} CPU with the confidence of something never audited.",
    "The winner is {proc_name}, pid {proc_pid}, by a margin that is not close.",
    "{proc_name}: {proc_cpu} CPU, {proc_ram} RAM. Started by you. Forgotten by you.",
    ("I have been watching {proc_name} for a while. It is not getting better.", POINTED),
    "{proc_name} is running as {proc_user}, which makes this technically your fault.",
    "{proc_cpu} CPU on a single process. {proc_name}. I would call that ambitious.",
    "{proc_name} is holding {proc_ram} of RAM for reasons it has never explained.",
    "Top of the list: {proc_name}. It has no idea it has been named.",
    ("{proc_name}, pid {proc_pid}. If this machine had a nemesis, this would be it.", POINTED),
    "Everything else is behaving. {proc_name} is not everything else.",
    "{proc_name} at {proc_cpu}. There is a fan spinning up somewhere on its behalf.",
    ("It is {proc_name}, pid {proc_pid}. Somewhere, you knew.", POINTED),
    ("{proc_name} is using more of this machine than you are.", UNHINGED),
    "I looked at every process. {proc_name} looked back.",
    "{proc_name} was started with: {proc_cmd}. That was a decision someone made.",
    ("{proc_name} has been up {proc_age} and has never once justified itself.", POINTED),
)

# Said once, at the moment of death.
EULOGY = bank(
    "eul",
    "{name} is dead. {cause}. Generation {gen} lasted {age}.",
    "That is it. {cause}. I told you about this one.",
    ("{name} died of {killer}. It was the metric I kept mentioning.", POINTED),
    "Health reached zero. Cause: {cause}. Written to the graveyard.",
    ("{age} old. Killed by {killer}. I want that on the record.", POINTED),
    ("I said something about this. Repeatedly. {cause}.", UNHINGED),
    "{name}, generation {gen}. {cause}. There is a file for it now.",
    "It was {killer} in the end. It usually is.",
)

BY_MOOD = {
    "sweating": SWEATING,
    "bloated": BLOATED,
    "constipated": CONSTIPATED,
    "dizzy": DIZZY,
    "hangry": HANGRY,
    "smug": SMUG,
    "content": CONTENT,
    "dead": DEAD,
}


# Written to the tombstone, so these are permanent. Dry, not zany. Keyed by the
# metric that did it; the fallback covers a death with no attributable killer.
EPITAPHS: dict[str, tuple[str, ...]] = {
    "cpu": (
        "Ran hot. Ran out.",
        "Died as it lived: at 100% utilisation.",
        "The fans stopped first.",
        "Sustained load, unsustained pet.",
    ),
    "ram": (
        "Wanted more than there was.",
        "Out of memory, and then out of time.",
        "The OOM killer got there first.",
        "Swapped until there was nothing left to swap.",
    ),
    "disk": (
        "Filled up. Gave up.",
        "No space left on device.",
        "Died of a full Downloads folder.",
        "There was nowhere left to write this.",
    ),
    "net": (
        "Lost too many packets to come back.",
        "Timed out.",
        "Last seen 150ms away.",
        "The connection was the problem, in the end.",
    ),
    "battery": (
        "The wall was right there.",
        "Discharged.",
        "Ran out of power at 0%, as designed.",
        "Nobody plugged it in.",
    ),
}

EPITAPH_FALLBACK = (
    "Cause undetermined. Health simply ran out.",
    "Died of general neglect.",
    "No single metric took the blame.",
)
