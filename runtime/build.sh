#!/bin/bash
# Builds the DDO Mobile runtime: an Arch Linux ARM userspace with only what the game session needs.
#   build.sh OUTDIR VERSION
# Runs as (fake) root on an aarch64 Arch Linux ARM system: the phone itself through runtime/tools/phone-run.sh,
# or any aarch64 machine. Packages come from the official Arch Linux ARM mirrors and are checked against its
# signing keys; nothing of them runs during the build (no install scripts, hooks or chroot), since proot on
# Android cannot be relied on for those. The shims are compiled here against the runtime's own glibc.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$(realpath -m "${1:?output directory}")
version=${2:?version}
root=$out/rootfs
db=$out/db
cache=$out/cache
conf=$out/pacman.conf
gpgdir=$out/gnupg

step() { echo "== $(date -u +%H:%M:%S) $*"; }

mkdir -p "$cache" "$db/var/lib/pacman" "$root"
# DroidDeck-style userspaces have no 'alpm' user, and proot cannot drop privileges for the download sandbox.
sed -e 's/^DownloadUser/#DownloadUser/' -e 's/^CheckSpace/#CheckSpace/' /etc/pacman.conf > "$conf"
grep -q '^DisableSandbox' "$conf" || sed -i 's/^\[options\]/[options]\nDisableSandbox/' "$conf"
sed -i "s#^\[options\]#[options]\nGPGDir = $gpgdir#" "$conf"
if [ ! -s "$gpgdir/pubring.gpg" ] && [ ! -s "$gpgdir/pubring.kbx" ]; then
  pacman-key --config "$conf" --gpgdir "$gpgdir" --init
  pacman-key --config "$conf" --gpgdir "$gpgdir" --populate archlinuxarm
fi
# A lingering gpg-agent keeps proot (and so the whole build) from exiting.
trap 'gpgconf --homedir "$gpgdir" --kill gpg-agent 2>/dev/null || true' EXIT

# Resolves the packages for a fresh root, downloads them and checks every signature; prints the archive paths.
fetch() {
  pacman --config "$conf" --root "$db" --cachedir "$cache" -Sw --noconfirm "$@" >&2
  local f
  while IFS= read -r f; do
    gpg --homedir "$gpgdir" --batch --quiet --verify "$cache/$f.sig" "$cache/$f" 2>/dev/null \
      || { echo "signature check failed: $f" >&2; return 1; }
    printf '%s\n' "$cache/$f"
  done < <(pacman --config "$conf" --root "$db" --cachedir "$cache" -Sp --print-format '%f' "$@")
}

step "packages"
pacman --config "$conf" --root "$db" -Sy >/dev/null
mapfile -t pkgs < <(grep -vE '^\s*(#|$)' "$here/packages.txt")
mapfile -t archives < <(fetch "${pkgs[@]}")
echo "${#archives[@]} packages verified"
python3 "$here/extract-packages.py" "$root" "${archives[@]}"

step "shims"
# A scratch compiler (gcc, binutils and what they need) runs from here with the runtime as its sysroot.
tools=$out/tools
rm -rf "$tools"
mapfile -t compiler < <(fetch gcc binutils)
python3 "$here/extract-packages.py" "$tools" "${compiler[@]}"
cc() {
  LD_LIBRARY_PATH="$tools/usr/lib" "$tools/usr/bin/gcc" --sysroot="$root" -B"$tools/usr/bin" "$@"
}
lib=$root/usr/local/lib
mkdir -p "$lib"
cc -shared -fPIC -O2 -Wall -pthread -o "$lib/libddoshim.so" "$here"/shims/preload/*.c -ldl
cc -shared -fPIC -O2 -Wall -pthread -o "$lib/libddofastpath.so" "$here/shims/fastpath/fastpath.c" -ldl
cc -shared -fPIC -O2 -Wall -nostdlib -ffreestanding -fno-stack-protector -fno-tree-loop-distribute-patterns \
  -Wl,-z,defs -o "$lib/libddoaudit.so" "$here/shims/fastpath/audit.c"
cc -shared -fPIC -O2 -Wall -pthread -o "$lib/libddossbs.so" "$here/shims/ssbs/ssbs_adapter.c" -ldl
LD_LIBRARY_PATH="$tools/usr/lib" "$tools/usr/bin/strip" --strip-unneeded "$lib"/libddo*.so
rm -rf "$tools"
ls -la "$lib"/libddo*.so

step "overlay"
cp -a "$here/overlay/." "$root/"
chmod 755 "$root"/usr/local/bin/*
printf '%s\n' "$version" > "$root/etc/ddo-runtime-version"
printf '%s\n' "${pkgs[@]}" > "$root/etc/ddo-runtime-packages"

step "prune"
rm -rf "$root"/usr/share/{doc,man,info,gtk-doc,help,devhelp,bash-completion,zsh} "$root"/usr/include "$root"/var/cache/pacman
find "$root/usr/share/locale" -mindepth 1 -maxdepth 1 ! -name 'en_US' ! -name 'locale.alias' -exec rm -rf {} + 2>/dev/null || true
find "$root/usr/lib" -name '*.a' -delete
find "$root/usr/lib" -path '*/python3*' -name '__pycache__' -prune -exec rm -rf {} + 2>/dev/null || true
rm -rf "$root"/usr/lib/python3*/{test,idlelib,tkinter,turtledemo,ensurepip} 2>/dev/null || true

step "pack"
archive=$out/ddo-runtime-$version.tar.zst
tar --numeric-owner -C "$root" -cf - . | zstd -T0 -19 -q -o "$archive" -f
sha=$(sha256sum "$archive" | cut -d' ' -f1)
size=$(stat -c %s "$archive")
unpacked=$(du -sb "$root" | cut -f1)
printf '{"version":"%s","sha256":"%s","size":%s,"unpacked":%s,"packages":%s}\n' "$version" "$sha" "$size" "$unpacked" \
  "${#archives[@]}" > "$out/ddo-runtime-$version.json"
cat "$out/ddo-runtime-$version.json"
