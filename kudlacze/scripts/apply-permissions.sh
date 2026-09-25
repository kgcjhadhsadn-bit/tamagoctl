#!/usr/bin/env bash
# Stosuje permissions/luckperms.txt przez RCON na lobby. LuckPerms zapisuje zmiany w MariaDB
# i rozsyła je do proxy i pozostałych backendów. Komendy są idempotentne.
set -euo pipefail
cd "$(dirname "$0")/.."
exec scripts/lib/rcon-apply.sh "${1:-permissions/luckperms.txt}" "${2:-lobby}"
