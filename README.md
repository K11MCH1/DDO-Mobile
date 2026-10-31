# Turnip memory setting

Cached GPU memory for the CPU's reads, from [DDO Mobile](https://github.com/K11MCH1/DDO-Mobile), packaged for
**DroidDeck**.

```
tu_override_uncached_as_cache_coherent=true
```

| File | What it is |
|---|---|
| [`droiddeck/0001-Game-environment-Turnip-maps-CPU-read-GPU-memory-cac.patch`](droiddeck/0001-Game-environment-Turnip-maps-CPU-read-GPU-memory-cac.patch) | The change for DroidDeck. It adds `turnip_cached_memory()` to `droiddeck-game-env`, so game launches get the setting unless the game's environment or profile already sets it, with a test. It applies with `git am` on DroidDeck's `main` at `92c1560f`. |

## Why

On KGSL, Turnip maps the host-visible memory that the CPU reads back **uncached**. A game that reads it on the CPU
crawls: Dungeons & Dragons Online's graphics start-up spent **minutes** there. With the setting, Turnip maps that
memory cached and coherent instead, and the start-up is back to normal.

DDO Mobile has set it for every game session since its first run.

## Notes

| | |
|---|---|
| **Where it was found** | The iQOO 11 (Snapdragon 8 Gen 2, Adreno 740). Not measured on other phones; where Turnip already uses cached memory, there is nothing for it to change. |
| **Scope** | Game launches only (`waitforexitandrun`), like DroidDeck's other per-game defaults |
| **Turning it off** | Set `tu_override_uncached_as_cache_coherent=false` in the game's environment; an explicit value always wins |
| **Other drivers** | It is a Turnip setting read from the environment; drivers that don't know it ignore it |

## Tested

- DroidDeck's new `test_only_games_get_cached_turnip_memory` passes.
- The rest of `GameEnvironmentTest` fails and passes exactly as before the change.
- Not yet run on a phone through DroidDeck itself.
