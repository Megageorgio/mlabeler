#!/bin/bash
# Makes rsrc_windows_amd64.syso (icon, version information, manifest) for mLabeler.exe with LLVM's resource
# compiler. Usage: tools/launcher/make-resources.sh 0.1.1-beta1. Without llvm-rc the committed file stays.
set -e
cd "$(dirname "$0")"
V=${1:-0.0.0}
RC=$(command -v llvm-rc || ls /usr/bin/llvm-rc-* 2>/dev/null | sort -V | tail -1 || true)
CVT=$(command -v llvm-cvtres || ls /usr/bin/llvm-cvtres-* 2>/dev/null | sort -V | tail -1 || true)
if [ -z "$RC" ] || [ -z "$CVT" ]; then echo "llvm-rc not found: keeping the committed resources"; exit 0; fi
NUM=$(echo "$V" | sed -E 's/^([0-9]+)\.([0-9]+)(\.([0-9]+))?.*/\1,\2,\4/; s/,$/,0/')
NUM="$NUM,0"
TMP=$(mktemp -d)
cp ../../art/icon.ico "$TMP/icon.ico"
cp launcher.manifest "$TMP/launcher.manifest"
sed -e "s/@NUM@/$NUM/g" -e "s/@VERSION@/$V/g" launcher.rc > "$TMP/launcher.rc"
(cd "$TMP" && "$RC" /nologo /fo launcher.res launcher.rc && "$CVT" /machine:x64 /out:launcher.obj launcher.res)
cp "$TMP/launcher.obj" rsrc_windows_amd64.syso
rm -rf "$TMP"
echo "resources for $V"
