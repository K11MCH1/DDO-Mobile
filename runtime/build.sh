#!/bin/bash
# Builds the DDO Mobile runtime: an Arch Linux ARM userspace with only what the game session needs.
#   build.sh OUTDIR VERSION
# Runs as (fake) root on an aarch64 Arch Linux ARM system: the phone itself through runtime/tools/phone-run.sh,
# or any aarch64 machine. Packages come from the official Arch Linux ARM mirrors; the shims are compiled here.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$(realpath -m "${1:?output directory}")
version=${2:?version}
root=$out/rootfs
conf=$out/pacman.conf

step() { echo "== $(date -u +%H:%M:%S) $*"; }

mkdir -p "$out/cache" "$root/var/lib/pacman"
# DroidDeck-style runtimes have no 'alpm' user, and proot cannot drop privileges for the download sandbox.
sed -e 's/^DownloadUser/#DownloadUser/' -e 's/^CheckSpace/#CheckSpace/' /etc/pacman.conf > "$conf"
grep -q '^DisableSandbox' "$conf" || sed -i 's/^\[options\]/[options]\nDisableSandbox/' "$conf"
# Packages are verified against the Arch Linux ARM signing keys, in a keyring of the build's own.
gpgdir=$out/gnupg
sed -i "s#^\[options\]#[options]\nGPGDir = $gpgdir#" "$conf"
if [ ! -s "$gpgdir/pubring.gpg" ] && [ ! -s "$gpgdir/pubring.kbx" ]; then
  pacman-key --config "$conf" --gpgdir "$gpgdir" --init
  pacman-key --config "$conf" --gpgdir "$gpgdir" --populate archlinuxarm
fi

step "build tools"
command -v gcc >/dev/null || pacman --config "$conf" -Sy --noconfirm --needed gcc
step "packages"
mapfile -t pkgs < <(grep -vE '^\s*(#|$)' "$here/packages.txt")
pacman --config "$conf" --root "$root" --cachedir "$out/cache" -Sy --noconfirm --needed "${pkgs[@]}" || {
  # Install hooks need chroot, which a proot build host may refuse; the packages themselves are in place.
  echo "pacman reported errors; continuing if the packages are installed"
  pacman --config "$conf" --root "$root" -Q "${pkgs[@]}" >/dev/null
}

step "shims"
lib=$root/usr/local/lib
mkdir -p "$lib"
gcc -shared -fPIC -O2 -Wall -pthread -o "$lib/libddoshim.so" "$here"/shims/preload/*.c -ldl
gcc -shared -fPIC -O2 -Wall -pthread -o "$lib/libddofastpath.so" "$here/shims/fastpath/fastpath.c" -ldl
gcc -shared -fPIC -O2 -Wall -nostdlib -ffreestanding -fno-stack-protector -fno-tree-loop-distribute-patterns \
  -Wl,-z,defs -o "$lib/libddoaudit.so" "$here/shims/fastpath/audit.c"
gcc -shared -fPIC -O2 -Wall -pthread -o "$lib/libddossbs.so" "$here/shims/ssbs/ssbs_adapter.c" -ldl
strip --strip-unneeded "$lib"/libddo*.so

step "overlay"
cp -a "$here/overlay/." "$root/"
chmod 755 "$root"/usr/local/bin/*
printf '%s\n' "$version" > "$root/etc/ddo-runtime-version"

step "prune"
rm -rf "$root"/usr/share/{doc,man,info,gtk-doc,help,devhelp,bash-completion,zsh} "$root"/usr/include \
  "$root"/var/cache/pacman/pkg/* "$root"/var/lib/pacman/sync/*
find "$root/usr/share/locale" -mindepth 1 -maxdepth 1 ! -name 'en_US' ! -name 'locale.alias' -exec rm -rf {} + 2>/dev/null || true
find "$root/usr/lib" -name '*.a' -delete
find "$root/usr/lib/python3"* -name '__pycache__' -prune -exec rm -rf {} + 2>/dev/null || true
rm -rf "$root"/usr/lib/python3*/{test,idlelib,tkinter,turtledemo,ensurepip} 2>/dev/null || true
ldconfig -r "$root" 2>/dev/null || true

step "pack"
archive=$out/ddo-runtime-$version.tar.zst
tar --numeric-owner -C "$root" -cf - . | zstd -T0 -19 -q -o "$archive" -f
sha=$(sha256sum "$archive" | cut -d' ' -f1)
size=$(stat -c %s "$archive")
unpacked=$(du -sb "$root" | cut -f1)
printf '{"version":"%s","sha256":"%s","size":%s,"unpacked":%s,"packages":%s}\n' "$version" "$sha" "$size" "$unpacked" \
  "$(pacman --config "$conf" --root "$root" -Q | wc -l)" > "$out/ddo-runtime-$version.json"
cat "$out/ddo-runtime-$version.json"
