"""ASCII sprites, one per mood.

Deliberately 7-bit ASCII: Windows consoles still default to code pages that
mangle anything cleverer, and a pet that renders as mojibake is not endearing.
"""

from __future__ import annotations

from tamagoctl.mood import Mood

CONTENT = r"""
     .-------.
    /  ^   ^  \
   |     w     |
    \  \___/  /
     '-------'
"""

SWEATING = r"""
  ,  .-------.  '
 '  /  @   @  \
   |    ~~~    | ,
  , \  _____  /  '
     '-------'
"""

BLOATED = r"""
   .-----------.
  /  -       -  \
 |       o       |
  \  ___________/
   '-----------'
"""

CONSTIPATED = r"""
     .-------.
    /  >   <  \
   |   -----   |
    \    _    /
     '-------'
"""

DIZZY = r"""
   *  .-------.  *
    /  X   O  \
   |     o     |
    \  \___/  /
   *  '-------'  *
"""

HANGRY = r"""
     .-------.
    /  -   -  \
   |   \_/\_/  |
    \  _____  /
     '-------'
"""

SMUG = r"""
     .-------.
    /  -   ^  \
   |     _     |
    \  \___/  /
     '-------'   ~
"""

DEAD = r"""
     .-------.
    /  x   x  \
   |     _     |
    \  _____  /
     '-------'
"""

SPRITES: dict[Mood, str] = {
    Mood.CONTENT: CONTENT,
    Mood.SWEATING: SWEATING,
    Mood.BLOATED: BLOATED,
    Mood.CONSTIPATED: CONSTIPATED,
    Mood.DIZZY: DIZZY,
    Mood.HANGRY: HANGRY,
    Mood.SMUG: SMUG,
    Mood.DEAD: DEAD,
}

# rich style per mood, used by the TUI. Named colours only, for 16-colour terminals.
COLORS: dict[Mood, str] = {
    Mood.CONTENT: "green",
    Mood.SWEATING: "yellow",
    Mood.BLOATED: "magenta",
    Mood.CONSTIPATED: "yellow",
    Mood.DIZZY: "cyan",
    Mood.HANGRY: "red",
    Mood.SMUG: "blue",
    Mood.DEAD: "bright_black",
}


def sprite(mood: Mood) -> str:
    return SPRITES.get(mood, CONTENT).strip("\n")


def color(mood: Mood) -> str:
    return COLORS.get(mood, "white")


def headstone(name: str, generation: int) -> str:
    """Used by the graveyard listing and the death screen."""
    label = f"{name} (gen {generation})"
    width = max(len(label) + 4, 18)
    return "\n".join(
        [
            " " + "_" * width,
            "/" + " " * width + "\\",
            "|" + "R.I.P.".center(width) + "|",
            "|" + " " * width + "|",
            "|" + label.center(width) + "|",
            "|" + "_" * width + "|",
        ]
    )
