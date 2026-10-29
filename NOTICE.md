# Notices

DDO Mobile is licensed under GPL-3.0. The Wayland compositor in `app/src/main/cpp/compositor/` contains code and
binaries from the projects below; their license files and copyright headers are kept where they exist.

## DroidDeck (GPL-3.0) — https://github.com/Droid-Deck/DroidDeck

Taken from DroidDeck's `app/src/main/cpp/waylandcomp/` and modified (reduced to a single fullscreen client):

| DDO Mobile (`compositor/`) | DroidDeck source |
| --- | --- |
| `src/compositor.c`, `src/compositor.h` | `src/compositor.c`, `src/compositor_internal.h` |
| `src/vk_present.c`, `src/vk_present.h` | `src/vk_present.c`, `src/vk_present.h` |
| `src/vk_loader.c`, `src/vk_loader.h` | `src/vk_loader.c`, `src/vk_loader.h` |
| `src/wl_dmabuf.c` | `src/wl_dmabuf.c` |
| `src/wl_single_pixel.c` | `src/wl_single_pixel.c` |
| `src/blend_pass.c`, `src/blend_pass.h`, `src/blend.vert`, `src/blend.frag`, `src/blend_vert.h`, `src/blend_frag.h` | same names in `src/` |
| `generated/*`, `protocols/*` (xdg-shell, linux-dmabuf-v1, viewporter, presentation-time, pointer-constraints-unstable-v1, relative-pointer-unstable-v1, single-pixel-buffer-v1) | `generated/`, `protocols/` (protocol files are MIT, from wayland-protocols) |
| `prebuilt/include/*`, `prebuilt/lib/libwayland-server.so` | `prebuilt/` (libwayland 1.26.0, MIT) |
| `prebuilt/lib/libffi.so` | `app/src/main/jniLibs/arm64-v8a/libffi.so` (libffi, MIT) |
| `keymap.xkb` | `app/src/main/assets/wayland/keymap.xkb` (compiled from xkeyboard-config, MIT) |

## libadrenotools (BSD-2-Clause) — https://github.com/bylaws/libadrenotools

`compositor/adrenotools/` is DroidDeck's copy at `app/src/main/cpp/adrenotools/` (library, hooks and
`lib/linkernsbypass`), unmodified; the `tools/` directory and meson files were not taken.

## Other parts from DroidDeck (GPL-3.0)

| DDO Mobile | DroidDeck source |
| --- | --- |
| `runtime/shims/preload/*.c` (compiled into the runtime as `libddoshim.so`) | `tools/linuxfs/preload/` |
| `runtime/shims/fastpath/*` | `tools/proot/fastpath/` |
| `runtime/shims/ssbs/ssbs_adapter.c` | `tools/linuxfs/ssbs/` |
| `runtime/shims/quest/kgsl_compat.h` | `tools/quest/kgsl_compat.h` |
| proot (`libproot.so`, `libproot-loader.so`, downloaded at build time, checksum pinned) | `pack-proot-5a2b7284e6357f88` on Droid-Deck/DroidDeck-Components (PRoot, GPL-2.0-or-later, with DroidDeck's patches) |
| `app/src/main/jniLibs/arm64-v8a/libpulse*.so`, `libsndfile.so`, `libltdl.so`, `libffi.so`, `assets/pulseaudio.tzst` | DroidDeck's Android PulseAudio 13 build and modules (PulseAudio LGPL-2.1-or-later, libsndfile LGPL-2.1-or-later, libltdl LGPL-2.1-or-later with exception, libffi MIT) |

The session design (proot binds, the KGSL render-node stand-in, the Proton launch environment) follows DroidDeck's
`LinuxRuntime.java`, `droiddeck-session` and `droiddeck-proton-run`.

## FEX (MIT) — https://github.com/FEX-Emu/FEX

`runtime/fex/build.sh` builds FEX's ARM64EC translator (`libarm64ecfex.dll`) from upstream FEX at
`FEX-2610-30-g741e146` with DroidDeck-Proton's FEX patch series (`patches/fex` at `DroidDeck-Proton-11-11.9`, applied
unmodified) and `runtime/fex/patches/` on top; rpmalloc (public domain / MIT) is FEX's allocator submodule. FEX does
not accept AI-generated contributions, so those patches are for DDO Mobile only and must not be sent upstream as they
are.

## OneLauncher (GPL-3.0) — https://github.com/JuneStepp/OneLauncher

The GLS sign-in, world status, login-queue and client-argument flow in `ddo/DdoGls.kt` follows OneLauncher's
`network/` and `start_game.py`.

## Downloaded at runtime

DroidDeck-Proton (https://github.com/Droid-Deck/DroidDeck-Proton), DD-Turnip (https://github.com/Droid-Deck/Drivers-CI)
and the Arch Linux ARM packages in the runtime keep their own licences.
