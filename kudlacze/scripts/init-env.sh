#!/usr/bin/env bash
# Tworzy plik .env na podstawie .env.example i wypełnia puste hasła/sekrety
# losowymi wartościami. Istniejącego .env NIE nadpisuje (uzupełnia tylko puste pola).
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
  cp .env.example .env
  chmod 600 .env
  echo "[init-env] Utworzono .env z .env.example"
fi

rand() ( # $1 = długość; podpowłoka bez pipefail (head zamyka potok wcześniej)
  set +o pipefail
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"
)

fill() { # $1 = klucz, $2 = długość
  local key=$1 len=$2
  if grep -qE "^${key}=$" .env; then
    local value
    value=$(rand "$len")
    sed -i "s|^${key}=$|${key}=${value}|" .env
    echo "[init-env] Wygenerowano ${key}"
  fi
}

fill MARIADB_ROOT_PASSWORD 32
fill DB_PASSWORD 32
fill VELOCITY_SECRET 48
fill RCON_PASSWORD 32
fill KC_ITEM_SECRET 64

# Wspólny klucz Floodgate (AES-128, 16 bajtów) dla Velocity i wszystkich backendów
mkdir -p secrets
if [[ ! -s secrets/floodgate-key.pem ]]; then
  head -c 16 /dev/urandom > secrets/floodgate-key.pem
  echo "[init-env] Wygenerowano secrets/floodgate-key.pem"
fi
# kontenery działają jako uid 1000 — plik musi być czytelny (katalog secrets/ jest poza gitem)
chmod 644 secrets/floodgate-key.pem

echo "[init-env] Gotowe. Uzupełnij ręcznie SERVER_DOMAIN, DISCORD_* i RESOURCE_PACK_* w .env."
