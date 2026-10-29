#!/bin/bash
# Runs a shell script inside the DDO Mobile app's installed runtime over adb (debug builds only, through run-as).
#   app-run.sh SCRIPT [PACKAGE]
set -euo pipefail
script=${1:?script}
pkg=${2:-com.ddomobile.launcher}
name=$(basename "$script")
export MSYS_NO_PATHCONV=1
native() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
libdir=$(adb shell "dumpsys package $pkg" | sed -n 's/.*legacyNativeLibraryDir=\(.*\)/\1/p' | head -1 | tr -d '\r')/arm64
adb push "$(native "$script")" "/data/local/tmp/$name" >/dev/null
adb shell "chmod 644 /data/local/tmp/$name"
adb shell "run-as $pkg sh -c 'cd files && mkdir -p tmp runtime/rootfs/tmp && cp /data/local/tmp/$name runtime/rootfs/tmp/$name && PROOT_LOADER=$libdir/libproot-loader.so PROOT_TMP_DIR=\$PWD/tmp $libdir/libproot.so --kill-on-exit -i \$(id -u):\$(id -u) -r \$PWD/runtime/rootfs -b /dev -b /proc -b /sys -b \$PWD/run:/run/ddo -w /root /usr/bin/env -i PATH=/usr/local/bin:/usr/bin HOME=/root LANG=C.UTF-8 /bin/bash /tmp/$name'"
