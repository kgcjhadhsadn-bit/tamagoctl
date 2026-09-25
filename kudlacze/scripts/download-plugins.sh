#!/usr/bin/env bash
# Pobiera Paper, Velocity i wszystkie pluginy z plugins.lock.yml, sprawdza sumy SHA256
# i rozkłada je do build/jars oraz build/plugins/{proxy,common,lobby,survival,seasons}.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/lib/lockfile.py download "$@"
