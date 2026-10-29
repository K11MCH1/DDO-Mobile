#!/bin/bash
# Builds the runtime on an adb-connected phone (it is the aarch64 machine) and pulls the result to runtime/out.
#   build-on-phone.sh VERSION [HOST_PACKAGE]
# HOST_PACKAGE is a debuggable app on the phone whose files hold an Arch Linux ARM userspace and proot
# (files/linuxfs, files/addons/proot/*): it is only the build machine, nothing of it goes into the result.
set -euo pipefail
version=${1:?version}
pkg=${2:-com.droiddeck.launcher.ddo}
here=$(cd "$(dirname "$0")/.." && pwd)
out=$here/out
export MSYS_NO_PATHCONV=1
native() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
mkdir -p "$out"
tar -C "$here" --exclude=./out --exclude=./tools --exclude=./out-build.log -cf "$out/src.tar" .
adb push "$(native "$out/src.tar")" /data/local/tmp/ddo-runtime-src.tar >/dev/null
adb shell chmod 644 /data/local/tmp/ddo-runtime-src.tar
cat > "$out/run.sh" <<SCRIPT
set -euo pipefail
rm -rf /root/ddo-build/src /root/ddo-build/out/rootfs
mkdir -p /root/ddo-build/src /root/ddo-build/out
tar -C /root/ddo-build/src -xf /tmp/ddo-runtime-src.tar
bash /root/ddo-build/src/build.sh /root/ddo-build/out "$version"
SCRIPT
adb shell "run-as $pkg sh -c 'cp /data/local/tmp/ddo-runtime-src.tar files/linuxfs/tmp/'"
bash "$here/tools/phone-run.sh" "$out/run.sh" linuxfs "$pkg"
for f in "ddo-runtime-$version.json" "ddo-runtime-$version.tar.zst"; do
  adb exec-out "run-as $pkg cat files/linuxfs/root/ddo-build/out/$f" > "$out/$f"
done
cat "$out/ddo-runtime-$version.json"
