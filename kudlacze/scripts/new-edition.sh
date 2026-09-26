#!/usr/bin/env bash
# =====================================================================
#  Kudłacze — nowa edycja survivalu z poziomu hosta (to samo co /edycja nowa + /edycja potwierdz w grze).
#
#   ./scripts/new-edition.sh
#
#  Przed resetem robi kopię zapasową (scripts/backup.sh). Plugin core:
#   - zapisuje nowy numer edycji w bazie, wygasza rangi gameplay (VIP…TYTAN, DRWAL),
#   - czyści logi bloków CoreProtect, wyrzuca graczy do lobby i wyłącza survival;
#  po restarcie kontenera scripts/container/pre-start.sh archiwizuje stary świat
#  (data/survival/archiwum/edycja-N-…), a serwer generuje nowy świat z losowym seedem.
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

strip() { sed 's/\x1b\[[0-9;]*m//g; s/§x\(§[0-9a-fA-F]\)\{6\}//g; s/§.//g'; }

echo "Aktualna edycja:"
docker compose exec -T survival rcon-cli "edycja" </dev/null | strip
read -r -p "Na pewno zakończyć edycję i zacząć nową? Wpisz TAK: " answer
[[ "$answer" == "TAK" ]] || { echo "Przerwano."; exit 1; }

./scripts/backup.sh

out=$(docker compose exec -T survival rcon-cli "edycja nowa" </dev/null | strip)
code=$(grep -o '/edycja potwierdz [0-9]\{6\}' <<<"$out" | awk '{print $3}')
[[ -n "$code" ]] || { echo "Nie udało się pobrać kodu potwierdzenia:"; echo "$out"; exit 1; }
docker compose exec -T survival rcon-cli "edycja potwierdz $code" </dev/null | strip

echo "Czekam na restart survivalu…"
sleep 20
for _ in $(seq 1 90); do
  [[ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q survival)" 2>/dev/null)" == healthy ]] && break
  sleep 5
done
docker compose logs --since 5m survival 2>&1 | grep -E '\[kudlacze\]|RESET ŚWIATA|Reset zakończony' || true
docker compose exec -T survival rcon-cli "seed" </dev/null | strip
echo "Nowa edycja gotowa. Pregeneracja świata trwa w tle (/chunky progress)."
