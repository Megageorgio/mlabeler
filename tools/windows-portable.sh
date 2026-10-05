#!/bin/bash
# Builds a portable Windows folder (mLabeler.exe + Java runtime + jars) from any OS and zips it.
# Needs: Go (for the launcher), network for the Windows JRE. Usage: tools/windows-portable.sh [out.zip]
set -e
cd "$(dirname "$0")/.."
OUT=${1:-build/mLabeler-windows-x64.zip}
GRADLE=${GRADLE:-./gradlew}
$GRADLE :app:windowsPortableLibs -Pdesktop.target=windows -q
DIR=app/build/windows-portable
mkdir -p build/tmp-jre
if [ ! -d build/tmp-jre/jre ]; then
  curl -sSL -o build/tmp-jre/jre.zip "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse"
  (cd build/tmp-jre && unzip -q jre.zip && mv jdk-*-jre jre)
fi
rm -rf "$DIR/runtime"; cp -r build/tmp-jre/jre "$DIR/runtime"
(cd tools/launcher && GOOS=windows GOARCH=amd64 go build -ldflags "-H windowsgui -s -w" -o "../../$DIR/mLabeler.exe" .)
rm -rf build/mLabeler; mkdir -p build; cp -r "$DIR" build/mLabeler
rm -f "$OUT"; (cd build && zip -qr "$(basename "$OUT")" mLabeler)
echo "$OUT"
