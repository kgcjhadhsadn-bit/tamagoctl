#!/usr/bin/env bash
# Buduje paczkę zasobów Kudłaczy (model Kłaka Czarodzieja) do build/resourcepack/.
# ZIP jest deterministyczny (stała kolejność i daty), więc SHA1 zmienia się tylko przy zmianie treści.
#
#   ./scripts/build-resourcepack.sh
#
# Potem: wrzuć ZIP na hosting HTTPS (np. GitHub Releases, własny serwer WWW) i wpisz w .env:
#   RESOURCE_PACK_URL=https://…/kudlacze-resourcepack.zip
#   RESOURCE_PACK_SHA1=<wypisany niżej SHA1>
# i zrestartuj backendy (docker compose up -d lobby survival seasons).
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=build/resourcepack/kudlacze-resourcepack.zip
mkdir -p "$(dirname "$OUT")"
python3 - "$OUT" <<'PY'
import sys, zipfile
from pathlib import Path
out = Path(sys.argv[1])
root = Path("resourcepack")
files = sorted(p for p in root.rglob("*") if p.is_file() and p.suffix != ".py")
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for p in files:
        info = zipfile.ZipInfo(str(p.relative_to(root)), date_time=(2026, 1, 1, 0, 0, 0))
        info.external_attr = 0o644 << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, p.read_bytes())
print(f"Plików w paczce: {len(files)}")
PY
sha1=$(sha1sum "$OUT" | awk '{print $1}')
echo "Paczka: $OUT ($(du -h "$OUT" | cut -f1))"
echo "SHA1:   $sha1"
echo
echo "W .env ustaw:"
echo "  RESOURCE_PACK_URL=https://<twój-hosting>/kudlacze-resourcepack.zip"
echo "  RESOURCE_PACK_SHA1=$sha1"
