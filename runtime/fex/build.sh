#!/bin/bash
# Builds DDO Mobile's FEX: libarm64ecfex.dll, the x86-64 translator Proton loads for ARM64EC processes.
#   runtime/fex/build.sh [WORKDIR]
# It is DroidDeck-Proton's FEX - upstream FEX at $fex_commit with the patch series DroidDeck ships at $droiddeck_tag -
# plus patches/: under Wine, FEX zeroed memory with a decommit and recommit, let its allocator decommit idle spans,
# flushed the instruction cache with a system call per compiled or linked block and asked the OS about every block's
# code range, all of which go through Wine's global virtual memory lock. The result lands in out/, the runtime build
# puts it in the image and ddo-run swaps it into a Proton whose own FEX is this same build.
#
# Runs on Windows (Git Bash) or Linux. Needs git, curl, python3, cmake and ninja; llvm-mingw comes from $LLVM_MINGW
# or is downloaded into WORKDIR (default: fex-build next to the repository).
#
# FEX does not accept AI-generated contributions: these patches are for DDO Mobile only, not for upstream.
set -euo pipefail
fex_commit=741e146fdf5a89517e1a67e3c899907cfba6131a
fex_describe=FEX-2610-30-g741e146
droiddeck_tag=DroidDeck-Proton-11-11.9
llvm_mingw=20261020

here=$(cd "$(dirname "$0")" && pwd)
work=$(realpath -m "${1:-$here/../../../fex-build}")
windows=false
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) windows=true ;; esac
native() { if $windows; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
# On Windows "python3" is often only the Microsoft Store stub.
python=python3
"$python" -c '' 2>/dev/null || python=python
step() { echo "== $*"; }
mkdir -p "$work" "$here/out"

step "toolchain"
if [ -z "${LLVM_MINGW:-}" ]; then
  if $windows; then asset=llvm-mingw-$llvm_mingw-ucrt-x86_64.zip
  else asset=llvm-mingw-$llvm_mingw-ucrt-ubuntu-22.04-$(uname -m).tar.xz; fi
  LLVM_MINGW=$work/${asset%%.tar.xz}
  LLVM_MINGW=${LLVM_MINGW%%.zip}
  if [ ! -d "$LLVM_MINGW" ]; then
    mkdir -p "$work/dl"
    curl -fsSL -o "$work/dl/$asset" "https://github.com/mstorsjo/llvm-mingw/releases/download/$llvm_mingw/$asset"
    case "$asset" in *.zip) unzip -q "$work/dl/$asset" -d "$work" ;; *) tar -C "$work" -xf "$work/dl/$asset" ;; esac
  fi
fi
export PATH="$LLVM_MINGW/bin:$PATH"
if $windows && ! cmd.exe //C "python3 --version" >/dev/null 2>&1; then
  # The build's generators run "python3" through cmd.exe, where that name is often the Microsoft Store stub.
  mkdir -p "$work/bin"
  printf '@"%s" %%*\r\n' "$(cygpath -w "$(command -v "$python")")" > "$work/bin/python3.cmd"
  export PATH="$work/bin:$PATH"
fi

step "source"
src=$work/FEX
if [ ! -d "$src/.git" ]; then
  git clone -q --filter=blob:none --no-checkout https://github.com/FEX-Emu/FEX.git "$src"
fi
# The patches are LF; a global core.autocrlf would otherwise leave a CRLF tree they no longer apply to.
git -C "$src" config core.autocrlf false
git -C "$src" am --abort >/dev/null 2>&1 || true
rm -rf "$src/.git/rebase-apply"
git -C "$src" -c advice.detachedHead=false checkout -q -f "$fex_commit"
git -C "$src" rm -q -r --cached . >/dev/null
git -C "$src" reset -q --hard "$fex_commit"
git -C "$src" submodule update -q --init --force --depth 1 --jobs 6
git -C "$src/External/rpmalloc" checkout -q -- .
[ "$(git -C "$src" describe --abbrev=7)" = "$fex_describe" ]

step "DroidDeck's FEX patches ($droiddeck_tag)"
rm -rf "$work/droiddeck-patches"
mkdir -p "$work/droiddeck-patches"
curl -fsSL "https://api.github.com/repos/Droid-Deck/DroidDeck-Proton/contents/patches/fex?ref=$droiddeck_tag" |
  "$python" -c 'import json, sys; print("\n".join(sorted(e["name"] for e in json.load(sys.stdin) if e["name"].endswith(".patch"))))' |
  tr -d '\r' | while read -r name; do
    curl -fsSL -o "$work/droiddeck-patches/$name" \
      "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Proton/$droiddeck_tag/patches/fex/$name"
  done
git -C "$src" -c user.name=ddo-mobile -c user.email=ddo-mobile@localhost -c core.autocrlf=false \
  am -q --3way "$work"/droiddeck-patches/*.patch

step "DDO Mobile's patches"
git -C "$src" apply "$here/patches/fex.patch"
git -C "$src/External/rpmalloc" apply "$here/patches/rpmalloc.patch"

step "build"
build=$work/build-arm64ec
rm -rf "$build"
cmake -S "$(native "$src")" -B "$(native "$build")" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$(native "$(command -v ninja)")" \
  -DCMAKE_TOOLCHAIN_FILE="$(native "$src/Data/CMake/toolchain_mingw.cmake")" -DMINGW_TRIPLE=arm64ec-w64-mingw32 \
  -DENABLE_FEXCORE_PROFILER=True -DCMAKE_BUILD_TYPE=Release -DENABLE_LTO=False -DBUILD_TESTING=False \
  -DTUNE_CPU=none -DRANGES_NATIVE=OFF -DOVERRIDE_VERSION="$fex_describe" -DOVERRIDE_HASH="${fex_commit:0:7}" >/dev/null
ninja -C "$(native "$build")" arm64ecfex
grep -q "$fex_describe" "$build/Bin/libarm64ecfex.dll"
cp "$build/Bin/libarm64ecfex.dll" "$here/out/libarm64ecfex.dll"
sha256sum "$here/out/libarm64ecfex.dll"
