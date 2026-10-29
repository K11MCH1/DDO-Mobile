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
