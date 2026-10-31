# Timer fixes

`QueryPerformanceCounter` from the CPU timer for ARM64EC Proton, from [DDO Mobile](https://github.com/K11MCH1/DDO-Mobile),
packaged for **DroidDeck**.

| File | What it is |
|---|---|
| [`droiddeck/0001-Proton-QueryPerformanceCounter-reads-the-CPU-timer-o.patch`](droiddeck/0001-Proton-QueryPerformanceCounter-reads-the-CPU-timer-o.patch) | The change for DroidDeck. It adds `droiddeck-qpc-timer` to the runtime overlay and runs it from the Proton wrapper right after the component swaps, with tests. It applies with `git am` on DroidDeck's `main` at `92c1560f`. |
| [`droiddeck-qpc-timer`](droiddeck-qpc-timer) | The script on its own, for reading |

## Why

Windows on ARM answers `QueryPerformanceCounter` from the CPU's generic timer without leaving user space. Wine's
ARM64EC `ntdll` makes a **system call for every one**: through FEX's syscall callback and Wine's dispatcher into
`clock_gettime`. DroidDeck-Proton's own user-mode TSC patch excludes ARM64EC, so phones still pay for every read.

Games that read the clock constantly feel it. Dungeons & Dragons Online calls it hundreds of thousands of times a
second while it loads:

| | Share of the game thread during a world load |
|---|---|
| System call per read | **5.1%**, plus the dispatch around it |
| CPU timer | **0.06%** |

## How it works

| Function in the Windows side of `ntdll.dll` | Becomes |
|---|---|
| `RtlQueryPerformanceCounter` | `mrs x8, cntvct_el0`: the generic timer's count |
| `RtlQueryPerformanceFrequency` | `mrs x8, cntfrq_el0`: its frequency (19.2 MHz on Snapdragon) |
| `NtQueryPerformanceCounter` (the system call stub) | Both from the timer, `STATUS_SUCCESS` |

- Only an `ntdll.dll` whose SHA-256 and original bytes the script knows is changed: DroidDeck-Proton 11-11.9's.
  Any other build is left as it is, with a line in the log saying so.
- The Proton's own file is kept beside it as `ntdll.dll.orig`. To undo, move it back.
- Wine's Linux side keeps its own 100 ns counter, which its timeouts are built on. Its Windows-side users all take
  the frequency along with the counter.
- It runs for game launches only, and never fails one.

## Tested

- DDO Mobile has applied the same bytes to the same `ntdll.dll` on every launch since 29 October 2026.
- From the stock 11-11.9 `ntdll.dll`, the script produces a file byte-identical to DDO Mobile's (SHA-256
  `73438fdc2c8024bd…`), and a second run leaves it alone.
- DroidDeck's new `QpcTimerTest` passes: both generated wrappers run the timer after the component swaps, and only a
  known `ntdll.dll` changes, with its original kept.
- Not yet run on a phone through DroidDeck itself.

## A source fix

The same idea in Wine's source, reading `cntvct_el0` and `cntfrq_el0` under `__aarch64__` / `__arm64ec__` next to
the existing TSC path, would cover every build rather than one `ntdll.dll` known by its hash.
