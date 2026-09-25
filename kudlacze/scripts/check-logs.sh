#!/usr/bin/env bash
# Przegląd logów sieci: szuka ERROR / WARN / wyjątków w logach kontenerów od ostatniego startu.
# Ostrzeżenia znane i nieszkodliwe są opisane w scripts/log-allowlist.txt (regex na linię).
#
#   ./scripts/check-logs.sh            # wszystkie usługi
#   ./scripts/check-logs.sh survival   # jedna usługa
#   ./scripts/check-logs.sh --all      # pokaż też linie z allowlisty
set -euo pipefail
cd "$(dirname "$0")/.."

SHOW_ALL=false
SERVICES=()
for a in "$@"; do
  case "$a" in
    --all) SHOW_ALL=true ;;
    *) SERVICES+=("$a") ;;
  esac
done
[[ ${#SERVICES[@]} -eq 0 ]] && SERVICES=(mariadb velocity lobby survival seasons)

ALLOW=scripts/log-allowlist.txt
problems=0
for svc in "${SERVICES[@]}"; do
  cid=$(docker compose ps -q "$svc" 2>/dev/null || true)
  if [[ -z "$cid" ]]; then
    echo "== $svc: kontener nie działa"
    problems=$((problems + 1))
    continue
  fi
  started=$(docker inspect -f '{{.State.StartedAt}}' "$cid")
  health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}brak healthchecka{{end}}' "$cid")
  lines=$(docker logs --since "$started" "$cid" 2>&1 \
    | grep -E '(ERROR|SEVERE|WARN|Exception|Caused by:|\[init\].*(ERROR|WARN))' || true)
  if [[ "$SHOW_ALL" != true && -s "$ALLOW" && -n "$lines" ]]; then
    lines=$(grep -v -E -f <(grep -v -E '^\s*(#|$)' "$ALLOW") <<<"$lines" || true)
  fi
  count=$(grep -c . <<<"$lines" || true)
  echo "== $svc (stan: $health) — podejrzanych linii: $count"
  if [[ -n "$lines" ]]; then
    sed 's/^/   /' <<<"$lines" | head -n 60
    problems=$((problems + count))
  fi
done
echo
if [[ $problems -eq 0 ]]; then
  echo "Logi czyste."
else
  echo "Do przejrzenia: $problems linii."
  exit 1
fi
