#!/bin/bash
# Builds a portable Windows folder (mLabeler.exe + Java runtime + jars) from any OS and zips it.
# Needs: Go (for the launcher), network for the Windows JRE. Usage: tools/windows-portable.sh [out.zip]
# PORTABLE=1 makes the fully portable build: a "portable" file next to mLabeler.exe keeps the settings, the toolkit,
# its Python and models and every other file inside the program folder.
# LEGACY=1 makes the build for old Windows (7, 8.1): a "legacy" file, no toolkit; build the launcher with Go 1.20
# (newer Go doesn't run on Windows 7).
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
# native libraries ready next to the jars: nothing has to be unpacked into the user's home folder at start
# (that fails when the home path is odd, redirected or not writable)
rm -rf "$DIR/natives"; mkdir -p "$DIR/natives"
unzip -q -j -o "$DIR"/app/skiko-awt-runtime-windows-x64-*.jar skiko-windows-x64.dll icudtl.dat -d "$DIR/natives"
unzip -q -j -o "$DIR"/app/lwjgl-[0-9]*-natives-windows.jar 'windows/x64/org/lwjgl/lwjgl.dll' -d "$DIR/natives"
unzip -q -j -o "$DIR"/app/lwjgl-nfd-*-natives-windows.jar 'windows/x64/org/lwjgl/nfd/lwjgl_nfd.dll' -d "$DIR/natives"
(cd tools/launcher && GOOS=windows GOARCH=amd64 go build -ldflags "-H windowsgui -s -w" -o "../../$DIR/mLabeler.exe" .)
rm -f "$DIR/portable"
if [ "$PORTABLE" = "1" ]; then
  printf 'This file makes mLabeler fully portable: the settings, the toolkit, its Python and models are kept in this\r\nfolder (data, toolkit) and nothing is written anywhere else. Delete it to use the usual places instead.\r\n' > "$DIR/portable"
fi
rm -f "$DIR/legacy"
if [ "$LEGACY" = "1" ]; then
  printf 'This is the build of mLabeler for old Windows (7, 8.1): mVocalToolkit can not be installed here, autolabel\r\nuses the toolkit of another computer in the network. On Windows 10 and 11 use the usual build.\r\n' > "$DIR/legacy"
fi
rm -rf build/mLabeler; mkdir -p build; cp -r "$DIR" build/mLabeler
rm -f "$OUT"; (cd build && zip -qr "$(basename "$OUT")" mLabeler)
echo "$OUT"
