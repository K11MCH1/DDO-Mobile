# DDO Mobile

Dungeons & Dragons Online on Android ARM64 phones: the unmodified Windows x64 client under an ARM64 Proton
(Wine with FEX for x86 code), composited by gamescope, rendered with DXVK on Mesa Turnip.

- **App** (`app/`): the launcher (DDO sign-in, world queue, settings), runtime / Proton / driver management,
  the game screen with DDO touch controls, and a single-client Wayland compositor that gamescope draws into.
- **Runtime** (`runtime/`): our Arch Linux ARM userspace, built by `runtime/build.sh` on any aarch64 Arch system;
  `runtime/tools/phone-run.sh` uses a phone as that system over adb.
- Proton (DroidDeck-Proton by default) and Turnip (DD-Turnip by default) are downloaded by the app.

The game files come from your own DDO installation; copy it to the phone (default `/storage/emulated/0/DDO`).

GPL-3.0. Parts come from [DroidDeck](https://github.com/Droid-Deck/DroidDeck) (GPL-3.0); see NOTICE.md.
