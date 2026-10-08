# Native backpack transfer regression probe

This probe runs the supplied FluffyMachines and Slimefun JARs on a real, disposable
Paper server. It invokes the registered production Loader/Unloader tickers and
holds Slimefun's actual profile callback executor while a fixture changes. It
does not contain a second transfer implementation.

## Inputs and execution

Python 3.11 or newer and a complete JDK are required. The current pinned native
lanes use JDK 25; the helper itself compiles with `--release 21`. The preparation
script also requires `curl`.

Prepare the pinned public core and official Paper builds:

```sh
python3 scripts/runtime/prepare_backpack_probe_inputs.py --out /tmp/fluffy-inputs
```

The script verifies each official Paper metadata response against
`backpack_probe_inputs.json`, then verifies size, SHA-256 and archive CRC. The
output `inputs.json` provides `core.path` and `paper[version].path`. The lanes are
Paper 1.21.11 build 132 (STABLE), 26.2 build 132 (STABLE), and 26.3 build 159 (BETA).
Changing those pins is an explicit maintenance operation; there is no fallback
to a different build.

Run a candidate JAR, using the corresponding paths from `inputs.json`:

```sh
python3 scripts/runtime/run_backpack_transfer_probe.py \
  --paper /tmp/fluffy-inputs/paper-1.21.11-132.jar \
  --core /tmp/fluffy-inputs/Slimefun-Legacy4.1.71.jar \
  --addon target/SF_FluffyMachines26.2.15.jar \
  --java "$JAVA_HOME/bin/java" \
  --work-dir /tmp/fluffy-fixed-1.21.11 \
  --expect fixed
```

Repeat with the other pinned Paper JARs and a new work directory for each lane.
Use `--expect baseline` with the pre-fix 26.2.14 JAR from the published Legacy
4.1.71 addon bundle to demonstrate the negative controls. The runner records the
supplied binary hashes; the expectation flag never selects or rebuilds a JAR.

`--runtime-template PREVIOUS_WORK_DIR` can reuse only Paper's `cache`, `libraries`
and `versions` directories after confirming the server JAR digest matches. Each
run creates fresh worlds, plugin data, inventories and evidence. Existing work
directories are refused. A complete JDK can be selected with `--java` and,
optionally, `--javac`. `--timeout` bounds each server-start and probe phase.

The runner binds the server to a loopback address on an available port, creates
an offline flat test world, accepts the server EULA for that disposable process,
and disables plugin updates. Block storage is explicitly SQLite with
`LOAD_WITH_CHUNK`. Existing credentials-free environment HTTP proxies and the
existing system Java truststore are honored where available; certificate
verification remains enabled.

## Cases and preserved behavior

There are 22 transfer cases and four separate-process restart controls. A fixed
run must report the exact case set, with every assertion passing, and both
registered machine tickers declaring synchronized execution.

| Cases | Required behavior |
| --- | --- |
| Ordinary Loader and Unloader, with current PDC and legacy lore identities | One selected stack moves, unrelated stacks remain, the first eligible/occupied/empty slot rules remain, metadata survives, and exactly 16 energy is consumed. |
| Bound/unbound Loader input routing; full Loader backpack; unbound/empty Unloader routing | The physical backpack is routed to the existing destination without losing its identity or charging transfer energy. |
| Delayed Loader/Unloader backpack A-to-B swap | The cancelled callback leaves both backpacks unchanged and acknowledges no save. A subsequent valid tick uses B and the retained energy allowance. |
| Loader input changes to a shulker box or another backpack | The prohibited replacement remains in the machine. Replacing it with ordinary input permits a later valid tick. |
| Two queued Unloader operations with one 16-energy allowance | One stack moves; the other remains in the backpack. |
| Empty/nonempty Unloader backpack whose outputs fill before callback | The physical backpack and its contents remain, output items remain, and no transfer energy is spent. |
| Backpack invalidated after lookup | The old instance remains unchanged. The next valid tick loads and uses the authoritative instance. |
| Core block removal | The core locks the captured menu, removes block data, and the late callback leaves its source intact. |
| Loader/Unloader chunk eviction | Actual world unload and controller-cache eviction must both be observed before the callback is released. Backpack contents, authoritative menu contents and the backpack item survive. |
| Loader/Unloader replacement menu after eviction | A distinct authoritative menu at the same coordinates is loaded before releasing the stale callback; the detached menu cannot cause a transfer. |
| Four restart controls | A fresh server process reads the ordinary PDC/legacy fixtures through the real core controllers and compares backpack inventory, machine inventory, machine ID and remaining energy. |

Fixtures use named native offline players, real Bukkit inventories, actual core
profile/block persistence and native item serialization. Payloads include display
names, lore, string PDC and a 64-bit PDC value above JavaScript's exact-integer
range. No connected player is needed. Ambient Loader/Unloader ticking is paused
so each explicitly invoked production tick is accounted for. Successful saves
are awaited; cancellation checks also compare the core's acknowledged snapshot
identity. A callback-gate timeout fails the scenario rather than establishing an
ordering claim.

The baseline expectation requires seven named regressions to fail while the
ordinary/routing/removal controls and all restart controls pass. Cache-lifecycle
results are reported independently. Only the specific old Unloader locked-menu
task exception is permitted in a baseline log. Missing cases, helper/setup
exceptions, unsuccessful restarts and unrelated plugin failures cannot count as
successful negative controls.

## Evidence and limits

`WORK_DIR/evidence/manifest.json` records supplied binaries, Java version, helper
source/JAR hashes, case outcomes, runtime log gates and final acceptance. The
transfer and restart reports are also copied to `transfers.json` and
`restart.json`; logs and compile output stay beside them. An interrupted or
infrastructure-failed run leaves an incomplete/failing manifest rather than a
passing result. Generated Paper remap JARs are CRC-checked before issuing the
probe command, and startup/plugin failures stop the process cleanly when the
server has reached readiness. Bounded thread diagnostics are attempted for
stalled startup or fixture execution.

The fixed log gate rejects scheduled-task exceptions, linkage/enable failures,
database-write failures, energy storage errors and ERROR/SEVERE messages
attributed to the tested plugins/core. Other host, authentication or remote
version-check diagnostics are retained separately and remain visible in the
raw logs. A passing probe is not a claim that every log line is warning-free.

These checks cover the actual synchronous Paper transfer paths and normal
shutdown/restart persistence for the exact supplied artifacts. Core removal is
used directly; the probe does not impersonate a connected player's break event.
It does not establish Folia compatibility, other database backend behavior,
connected-player interaction coverage, crash atomicity, or a transaction across
the backpack and machine stores. The acknowledged snapshot check proves the
tested cancellation did not acknowledge a save; it is not a general audit of
every possible persistence failure.
