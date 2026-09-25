#!/bin/sh
# =====================================================================
#  Kudłacze — reset świata PRZED startem serwera (uruchamiany z entrypointu kontenera).
#
#  Minecraft 26.x wczytuje level.dat i ustawienia generatora (seed) głównego świata, zanim
#  pluginy dostaną onLoad(). Dlatego folder świata musi zniknąć tutaj, a plugin core
#  (WorldReset) czyści potem tylko dane pluginów: regiony WorldGuard, zadania Chunky, domy
#  i pieniądze EssentialsX.
#
#  Znacznik zapisuje plugin core (/edycja potwierdz, reset sezonu):
#    /data/plugins/KudlaczeCore/reset-swiata.properties
# =====================================================================
set -eu

DATA=${DATA_DIR:-/data}
MARKER="$DATA/plugins/KudlaczeCore/reset-swiata.properties"

[ -f "$MARKER" ] || exit 0
grep -q '^swiat-zresetowany=true' "$MARKER" && exit 0

prop() { sed -n "s/^$1=//p" "$MARKER" | tail -n 1 | tr -d '\r'; }

typ=$(prop typ)
numer=$(prop numer)
archiwizuj=$(prop archiwizuj)
extra=$(prop dodatkowe-swiaty | tr ',' ' ')
level=$(sed -n 's/^level-name=//p' "$DATA/server.properties" 2>/dev/null | tail -n 1 | tr -d '\r')
[ -n "$level" ] || level=world

dest="$DATA/archiwum/${typ:-reset}-${numer:-0}-$(date +%Y%m%d-%H%M)"
for w in "$level" "${level}_nether" "${level}_the_end" $extra; do
  [ -d "$DATA/$w" ] || continue
  rm -f "$DATA/$w/session.lock"
  if [ "$archiwizuj" = "true" ]; then
    mkdir -p "$dest"
    mv "$DATA/$w" "$dest/$w"
    echo "[kudlacze] Reset świata ($typ #$numer): zarchiwizowano $w -> $dest"
  else
    rm -rf "${DATA:?}/$w"
    echo "[kudlacze] Reset świata ($typ #$numer): usunięto $w"
  fi
done
[ -d "$DATA/archiwum" ] && chown -R 1000:1000 "$DATA/archiwum" 2>/dev/null || true

echo "swiat-zresetowany=true" >> "$MARKER"
