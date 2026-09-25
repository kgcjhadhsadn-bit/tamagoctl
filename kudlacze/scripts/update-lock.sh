#!/usr/bin/env bash
# Sprawdza w API (PaperMC Fill, Modrinth, Hangar, GitHub, GeyserMC) najnowsze wersje
# zgodne z wersją MC z plugins.lock.yml. Z --apply zapisuje nowe wersje, URL-e i SHA256.
#   ./scripts/update-lock.sh                 # podgląd
#   ./scripts/update-lock.sh --apply         # zapis
#   ./scripts/update-lock.sh --apply --only paper velocity
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/lib/lockfile.py update "$@"
