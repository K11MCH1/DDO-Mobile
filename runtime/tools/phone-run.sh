#!/bin/bash
# Runs a shell script inside an Arch Linux ARM userspace on the phone over adb, as root under proot.
#   phone-run.sh SCRIPT [ROOTFS_DIR_IN_APP_FILES] [PACKAGE]
# The phone acts as the aarch64 build machine; the default host is a debuggable app whose files hold an
# Arch Linux ARM userspace (files/linuxfs) and proot (files/addons/proot/*).
set -euo pipefail
script=${1:?script}
rootfs=${2:-linuxfs}
pkg=${3:-com.droiddeck.launcher.ddo}
name=$(basename "$script")
export MSYS_NO_PATHCONV=1
# adb on Windows needs native paths for local files.
native() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
adb push "$(native "$script")" "/data/local/tmp/$name" >/dev/null
adb shell "chmod 644 /data/local/tmp/$name"
adb shell "run-as $pkg sh -c 'cd files && mkdir -p ptmp $rootfs/tmp && cp /data/local/tmp/$name $rootfs/tmp/$name && P=\$(ls -d \$PWD/addons/proot/*/) && PROOT_LOADER=\${P}libproot-loader.so PROOT_TMP_DIR=\$PWD/ptmp \${P}libproot.so -0 -r \$PWD/$rootfs -b /dev -b /proc -b /sys -b \$PWD:/host -w /root /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/bin HOME=/root LANG=C.UTF-8 TERM=dumb /bin/bash /tmp/$name'"
