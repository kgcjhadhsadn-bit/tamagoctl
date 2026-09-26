#!/usr/bin/env bash
# =====================================================================
#  Kudłacze — kopia zapasowa (cron hosta, codziennie 04:00 — godzinę przed restartem 05:00)
#
#   ./scripts/backup.sh               # pełna kopia: światy, dane pluginów, baza MariaDB
#   BACKUP_DIR=/mnt/backup ./scripts/backup.sh
#
#  Na czas kopiowania zapis świata jest wstrzymany (save-off / save-all flush / save-on przez RCON),
#  więc pliki regionów są spójne. Baza: mariadb-dump --single-transaction (bez blokowania gry).
#  Rotacja: kopie starsze niż BACKUP_KEEP_DAYS (domyślnie 14) są usuwane.
#
#  crontab -e  (użytkownik z dostępem do dockera):
#    0 4 * * * cd /opt/kudlacze && ./scripts/backup.sh >> backups/backup.log 2>&1
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

BACKUP_DIR=${BACKUP_DIR:-backups}
KEEP_DAYS=${BACKUP_KEEP_DAYS:-14}
SERVERS=(lobby survival seasons)
STAMP=$(date +%Y-%m-%d_%H%M)
DEST="$BACKUP_DIR/$STAMP"
mkdir -p "$DEST"

log() { echo "[$(date '+%F %T')] $*"; }
rcon() { docker compose exec -T "$1" rcon-cli "$2" </dev/null >/dev/null 2>&1 || log "UWAGA: $1 nie odpowiada na RCON ($2)"; }

resume() {
  for s in "${SERVERS[@]}"; do rcon "$s" "save-on"; done
}
trap resume EXIT

log "Kopia $STAMP → $DEST"
for s in "${SERVERS[@]}"; do
  rcon "$s" "save-off"
  rcon "$s" "save-all flush"
done
sleep 5

for s in "${SERVERS[@]}"; do
  if [[ -d "data/$s" ]]; then
    # świat (26.x: wszystkie wymiary i gracze w jednym folderze) + dane pluginów (bez jarów i bibliotek)
    items=()
    for w in world world_nether world_the_end; do [[ -d "data/$s/$w" ]] && items+=("$w"); done
    [[ -d "data/$s/plugins" ]] && items+=(plugins)
    tar --exclude='plugins/*.jar' --exclude='plugins/*/libs' --exclude='plugins/*/lib' \
        --exclude='plugins/spark/tmp*' -C "data/$s" -czf "$DEST/$s.tar.gz" "${items[@]}"
    log "  $s: $(du -h "$DEST/$s.tar.gz" | cut -f1)"
  fi
done
resume
trap - EXIT

docker compose exec -T mariadb sh -c \
  'mariadb-dump -uroot -p"$MARIADB_ROOT_PASSWORD" --single-transaction --routines --triggers --databases "$MARIADB_DATABASE"' \
  </dev/null | gzip -9 > "$DEST/mariadb.sql.gz"
log "  mariadb: $(du -h "$DEST/mariadb.sql.gz" | cut -f1)"

# konfiguracja proxy (bez sekretów — te są w .env/secrets poza kopią)
tar -C data -czf "$DEST/velocity.tar.gz" --exclude='velocity/plugins/*.jar' --exclude='velocity/libraries' velocity 2>/dev/null || true

( cd "$DEST" && sha256sum ./*.gz > SHA256SUMS )
log "Gotowe: $(du -sh "$DEST" | cut -f1)"

# rotacja
find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -name '20??-??-??_????' -mtime +"$KEEP_DAYS" -print -exec rm -rf {} + \
  | sed 's/^/[rotacja] usunięto /'
