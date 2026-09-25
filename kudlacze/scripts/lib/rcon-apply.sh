#!/usr/bin/env bash
# Wysyła plik komend (jedna na linię, # = komentarz) przez RCON do konsoli wskazanego serwera.
#   scripts/lib/rcon-apply.sh <plik> <usługa>
set -euo pipefail
cd "$(dirname "$0")/../.."
FILE=${1:?podaj plik komend}
SVC=${2:?podaj usługę (lobby/survival/seasons)}

if ! docker compose ps --status running -q "$SVC" | grep -q .; then
  echo "[BŁĄD] Usługa '$SVC' nie działa — uruchom najpierw: docker compose up -d" >&2
  exit 1
fi

# czekamy aż RCON będzie gotowy (serwer mógł dopiero wstać)
for _ in $(seq 1 60); do
  docker compose exec -T "$SVC" rcon-cli "list" </dev/null >/dev/null 2>&1 && break
  sleep 2
done

total=0; failed=0
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%%$'\r'}"
  [[ -z "${line// }" || "$line" =~ ^[[:space:]]*# ]] && continue
  total=$((total + 1))
  out=$(docker compose exec -T "$SVC" rcon-cli "$line" </dev/null 2>&1 || true)
  # LuckPerms wykonuje komendy asynchronicznie, więc RCON zwykle zwraca pustą odpowiedź;
  # wykrywamy błędy połączenia i komunikaty składni. Stan można sprawdzić: /lp group <g> info
  if grep -qiE 'failed to connect|unknown|error|invalid|could not|nieznan' <<<"$out"; then
    failed=$((failed + 1))
    echo "[!] $line"
    sed 's/^/    /' <<<"$out"
  fi
done < "$FILE"
echo "Wysłano $total komend, problemów: $failed."
[[ $failed -eq 0 ]]
