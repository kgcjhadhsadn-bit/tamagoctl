#!/usr/bin/env bash
# Buduje plugin core (Gradle, Java 25), uruchamia testy i kopiuje jar do build/plugins/common.
#   ./scripts/build-core.sh            # build + testy
#   ./scripts/build-core.sh -x test    # bez testów
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
cd plugins-src/core
if [[ -z "${JAVA_HOME:-}" ]] && ! java -version 2>&1 | grep -qE 'version "2[5-9]'; then
  echo "[UWAGA] Wymagana Java 25 (JAVA_HOME). Gradle spróbuje użyć toolchaina." >&2
fi
./gradlew --console=plain build "$@"
jar=$(ls -1 build/libs/KudlaczeCore-*.jar | grep -vE -- '-(sources|javadoc)\.jar$' | tail -1)
mkdir -p "$ROOT/build/plugins/common"
cp "$jar" "$ROOT/build/plugins/common/KudlaczeCore.jar"
chmod 644 "$ROOT/build/plugins/common/KudlaczeCore.jar"
echo "[OK] $(basename "$jar") -> build/plugins/common/KudlaczeCore.jar"
