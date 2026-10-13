# FluffyMachines 26.2.16 — Barrel display performance candidate

## Scope

This is a candidate for a follow-up to the published **26.2.15** backpack-integrity release. It isolates **cosmetic** barrel `ItemDisplay` state from the storage transaction hot path, without changing the actual `Barrel.tick()` call or any transaction quantities.

Every synchronized machine tick still runs:
1. Empty/legacy barrel registration checks.
2. Input-slot acceptance, capacity checks, metadata matching and optional void-excess behavior.
3. The original output-refill passes and updates of stored counters.

Only `BarrelDisplayManager.update()`'s cosmetic inspection is rate-limited. Previously it inspected the barrel's persisted reserve and both output slots, then cloned and hashed a displayed stack on every ticker invocation even when the visible icon could not change. The proposed cache uses a **per-block, in-memory deadline**, re-reading the display no more often than once every **20 world game ticks** during stable operation. Empty and temporarily ambiguous visual states are also cached, without changing saved item IDs, reserve quantities or world records.

A world game-time rollback causes immediate reinspection. An empty/no-entity entry is safe for the existing tracked-display removal and cache pruning paths. The original item-display entity is persistent; the existing 600-tick cache pruning can repair externally missing entities. There is intentionally no new persistent format, plugin setting, database write or background task.

## Regression evidence

- JUnit: before-deadline skips, exact-deadline and overdue refresh, stable schedules even when ticker cadence is not a multiple of 20, independent barrel deadlines and rewound game-time refresh.
- Existing 26.2.15 Java 21 bytecode, Paper 1.21.11, Paper 26.2, legacy Doctor and backpack integrity tests remain required.
- 26.2.16 candidate also compiles against the **Paper 26.3 stable API**.
- A green compilation/test suite is not a measured server TPS improvement or a full GUI/entity runtime proof.

## Staging gate (mandatory before merging/publishing)

1. Use a copy of a world containing active/empty Fluffy Barrels, and back up the original plugin data.
2. Measure `/sf tick top` with the **same machine counts, uptime, loaded chunks, player count and network layout** on 26.2.15 and the candidate.
3. Test empty -> first item -> partly filled -> drained -> new type, with ordinary and metadata/PDC-bearing items.
4. Test both output slots, full and partial stacks, fast Networks withdrawals/deposits, including when the display update is deferred.
5. Check external removal of an `ItemDisplay`, chunk unload/reload, block break/re-place, logout/login and restart: no orphan displays or lost real storage.
6. Verify that the icon updates within its refresh deadline on ordinary stable server ticks, without blocking storage processing.
7. Repeat on Paper/Purpur 1.21.11 and 26.3 as appropriate; do not claim native Folia region safety without dedicated Folia execution evidence.

## Release procedure

The pull request remains a **draft**. Do not merge or create v26.2.16 while tests and visual staging remain outstanding. When approved, merge this candidate, verify the separately published `SF_FluffyMachines26.2.16.jar` version and digest, and only then change the pinned addon in a new canonical Slimefun Legacy bundle revision. The already-published **Slimefun Legacy 4.1.73 / r134** bundle and **FluffyMachines 26.2.15** must not be overwritten.
