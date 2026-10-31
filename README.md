# FEX patches

The FEX fixes from [DDO Mobile](https://github.com/K11MCH1/DDO-Mobile), packaged for **DroidDeck-Proton**'s FEX.
They take Dungeons & Dragons Online from **50 seconds to 4 seconds** between ENTER and standing in the world. They
help any game that loads a lot under ARM64EC.

| File | Goes to | What it is |
|---|---|---|
| [`patches/fex/0013-ARM64EC-keep-translated-code-on-empty-reads-and-off-.patch`](patches/fex/0013-ARM64EC-keep-translated-code-on-empty-reads-and-off-.patch) | DroidDeck-Proton's `patches/fex/`, after `0012` | The FEX changes |
| [`patches/fex/rpmalloc/0001-CMake-under-MinGW-commit-spans-once-and-never-decomm.patch`](patches/fex/rpmalloc/0001-CMake-under-MinGW-commit-spans-once-and-never-decomm.patch) | FEX's `External/rpmalloc` submodule | rpmalloc commits once and never decommits under MinGW |

## What they fix

Under Wine, some FEX paths that are cheap on Linux take Wine's global virtual memory lock, and one of them throws
away far more translated code than it should.

| Problem | Fix | Measured with DDO |
|---|---|---|
| After a successful `NtReadFile`, FEX invalidates translated code in the buffer. A zero-length read with a `NULL` buffer, which is how programs poll a pipe or a socket, reached `InvalidateAlignedInterval` with address 0, and that means the whole process. | Return early for `NULL` or empty buffers and for reads into memory outside FEX's executable intervals | ENTER to in the world **50 s → 4.1 s**; full code-cache clears from up to **7 per 5 s → none** |
| Each growth of the code buffer (16, 32, 64 MB) discarded all translated code | Start at the 128 MB maximum | One code buffer instead of three |
| `VirtualDontNeed` zeroed memory with `VirtualQuery` + decommit + recommit | `MEM_RESET`, which Wine turns into one `madvise` | World load **117 s → 54.5 s**, with the three changes below |
| Every compiled or linked block flushed the instruction cache with `FlushInstructionCache`, a system call plus FEX's own invalidation callback | `dc cvau` / `ic ivau`, as on Linux | |
| DroidDeck's patch 0003 asks the OS (`VirtualQuery`) on every executable range lookup | Only for writable executable ranges, where a protector can hide inaccessible pages, and on misses | Waits on Wine's `virtual_mutex` during a load **4127 → 129** samples |
| rpmalloc decommitted idle spans and recommitted them | Under MinGW, commit once and never decommit (Wine has no commit charge) | |

0013 also carries opt-in counters: with `DDO_FEX_DIAG=1` and `FEX_SILENTLOG=0` it logs invalidations by source, cache
loads, compiles and full code-cache clears every 5 seconds. They cost nothing when off.

## Base and status

- Built on FEX `FEX-2610-30-g741e146` with DroidDeck-Proton's `patches/fex` 0001–0012 from the
  `DroidDeck-Proton-11-11.9` tag. The same 12 patches are on DroidDeck-Proton's `main` as of 31 October 2026.
- 0013 applies with `git am` on top of 0012.
- These are exactly the changes in the FEX build that DDO Mobile v0.1.0 ships in its runtime.

## Applying

```bash
# DroidDeck-Proton: next to the existing series
cp patches/fex/0013-*.patch /path/to/DroidDeck-Proton/patches/fex/

# FEX tree, after the series is applied: the submodule patch
git -C External/rpmalloc am /path/to/patches/fex/rpmalloc/0001-*.patch
```

DroidDeck-Proton's build applies `patches/fex/*.patch` to FEX itself. The rpmalloc patch belongs to a submodule, so
it needs a step of its own in that build.

## Not for FEX upstream

FEX does not accept AI-generated contributions. These patches are for downstream builds such as DroidDeck-Proton and
must not be submitted to FEX itself.
