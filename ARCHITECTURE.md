# Architecture and safety boundaries

## Runtime overview

```text
Forge server tick START
  └─ System.nanoTime() sample for the previous start-to-start tick
       ├─ rolling MSPT / EMA / recent max / estimated TPS
       ├─ load-state classification + spike guard
       ├─ conservative queue-cost projection
       └─ hysteretic recovery + current task budgets

Forge PlayerLoggedInEvent (after normal Forge/Minecraft join path)
  └─ UUID-only JoinSession
       ├─ opt-in POST_JOIN provider tasks
       ├─ optional Hello only if the remote has the JoinFlow channel
       └─ no channel / incompatible client → continue server-only

Forge server tick END
  ├─ CRITICAL/HIGH task opportunity budget
  ├─ NORMAL post-join budget
  ├─ LOW/BACKGROUND budget (paused/reduced by load state)
  ├─ coalesced progress/state changes
  └─ bounded, fair own-channel send queue
```

The measured tick window is a fixed primitive ring (100 samples, with a 40-tick recent window). It is sampled once per tick start. Heap usage is sampled every 100 ticks. There is no entity/chunk scan, packet sniffing, file access, history serialization or mod scan in the tick hot path.

## Load controller and recovery

`GlobalLoadController` classifies the recent average/EMA against configurable thresholds. `SpikeGuard` separately counts large instantaneous samples and holds protective states. `JoinLoadEstimator` adds a small capped projection from active JoinFlow sessions, queued estimated task cost and measured JoinFlow task cost; the projection is a conservative scheduler signal, not CPU-percent arithmetic or a promise of future MSPT.

A state increase is immediate. After CRITICAL/EMERGENCY or repeated protection, `RecoveryController` reports `RECOVERY` and raises budgets in 20-tick steps by default. A new overload resets recovery. A budget is only the scheduler's allowed wall-time for starting JoinFlow callbacks; Java callbacks cannot be preempted after they start.

`ExistingPlayerProtection` reduces optional JoinFlow work as load and concurrent JoinFlow sessions increase. It does not change existing player packets, vanilla gameplay, server view distance, chunks, entities or other mods.

## Queue, priorities and fairness

- `JoinQueue` tracks short-lived active JoinFlow sessions by UUID. It is not a vanilla connection queue and never stores `ServerPlayer` or `Level` references.
- `PostJoinQueue` has a global task limit and per-player limit. Each priority bucket is round-robin across UUID lanes. Waiting LOW/BACKGROUND work ages one tier at a time, but never reaches CRITICAL.
- Dependency IDs must have completed successfully for the session before dependent work runs. A missing registered dependency is rejected at session creation. A failed prerequisite leaves dependent optional work deferred until session timeout/logout.
- The scheduler runs callbacks serially on the server thread. `allow_parallel_safe_tasks` means multiple short callbacks may be considered in a tick; JoinFlow does **not** run Minecraft objects on worker threads. The bounded I/O worker receives only immutable history/report DTOs and file paths.
- Logout cancels queued work cooperatively, clears network queues and removes the UUID session. Running Java callbacks are not forcibly interrupted.
- Test commands enqueue bounded synthetic arithmetic tasks using non-player lanes; they do not create fake `ServerPlayer` objects or mutate a world.

## Supported Forge lifecycle

The implementation uses Forge 47.4.20's standard `PlayerEvent.PlayerLoggedInEvent`, `PlayerLoggedOutEvent`, `TickEvent.ServerTickEvent`, `RegisterCommandsEvent`, `ServerStartingEvent` and `ServerStoppingEvent` lifecycle hooks.

The `PlayerLoggedInEvent` point is after Forge/Minecraft has performed the connection and player-placement path. Therefore:

- JoinFlow profiles its own post-login session and task providers.
- It does **not** pretend that a vanilla/Forge join has been paused or spread over ticks.
- It never delays authentication challenges, registry registration, required capability state, inventory transactions, damage or unknown network payloads.
- It does not control vanilla chunk loading; JoinFlow exposes no hook that changes vanilla chunk behavior.

This boundary is deliberate: arbitrary third-party code cannot be made incremental safely from outside that mod. Integrations should register only work they own and have verified to be safe to defer.

## Task API and thread model

`JoinFlowAPI.registerTaskProvider` accepts only `JoinPhase.POST_JOIN` and requires explicit `safeToDefer=true`. The task callback is shared across sessions and invoked on the logical server thread. `JoinTaskContext.player()` is available only during that callback; providers must not retain the `ServerPlayer` or capture world state. `registerTask` queues one task for an active UUID session via `MinecraftServer.execute`.

Task duration uses `System.nanoTime()`. The bounded learning model stores EMA/average, logarithmic-histogram median/P95 approximation, max and recent cost for up to 128 task types. Warnings are aggregated and rate limited. `FULL` profile mode keeps a bounded ring of task duration and queue-wait records; `BASIC` keeps aggregate task statistics; `OFF` retains load monitoring but skips task learning.

For I/O, one daemon executor with one thread and a bounded queue writes a capped local history file and optional reports. It never sees a player, level, entity, chunk or Minecraft server world object. No HTTP service is required.

## Network and sync

`JoinFlowNetwork` owns `qyuro_joinflow:main`, protocol version 1. The channel accepts missing/other remote versions at channel negotiation so the optional extension does not force a client mod; each in-band message carries protocol/message versions. The versioned Hello is the compatibility gate. A remote without a channel receives no JoinFlow custom packet. A peer that does not acknowledge a supported Hello remains on the server-only path.

Only JoinFlow's own messages enter `JoinFlowNetworkScheduler`. That queue is bounded, priority-aware, per-player round-robin and coalesces replaceable state/progress updates. It never patches Netty or other mods' `SimpleChannel`s. Client telemetry is advisory, sender-bound and rate-limited; it cannot create sessions, set server priority or affect server load decisions.

Static sync is opt-in through `JoinFlowAPI.registerStaticData`. Keys, sizes, versions and SHA-256 hashes are bounded and validated. The server sends a cache query; the client response can suppress only this optional static transfer. Cacheable entries must be non-secret. Payloads are split below the configured packet limit with room for headers, sequence/total, per-part CRC32 and a full-content SHA-256. The client reassembles/processes at a configurable client tick budget and acknowledges the exact key/version/hash. Server queue caps prevent partial/oversized batches from being accepted as complete.

## Client companion

Client-only event subscribers and UI live under `com.qyuro.joinflow.client` and are registered with `Dist.CLIENT`. Common/server classes do not load `Minecraft.getInstance()` during dedicated-server setup. The default HUD is a small static panel; the non-pausing full screen is opt-in. Client telemetry contains only readiness, JoinFlow queue depth, JoinFlow batch processing time and cache readiness. It contains no IP, computer file list, process list, FPS or unrelated application data.

## Compatibility and attribution

Origins, Apoli, Qyurowl and QyuroLevl are detected once through their runtime mod IDs. Current adapters are intentionally observational only: JoinFlow does not reference their classes, reflect into their registries, repair registry errors, change auth semantics or disable them. An integration can add a safe task provider through the public API. `/joinguard hotspots` labels measurements as JoinFlow-owned; a slow session is not evidence that one detected mod caused it.

## Persistence, timeout and shutdown

- History is capped by `profiling.history_size` (100 by default), excludes player names/UUIDs, and is replaced atomically by one background write after a session finishes.
- Full per-join JSON reports are off by default.
- A session that exceeds `max_join_session_seconds` logs and cancels only its own safe queued tasks. No load-based disconnect is attempted.
- Shutdown stops accepting work, clears pending UUID/session references, queues one small history snapshot and waits at most 500 ms for the bounded file worker before leaving optional pending writes behind.

## Verification checklist

The project includes Gradle `runClient`, `runServer` and GameTest run configurations from the Forge 1.20.1-47.4.20 MDK. At minimum, validate on a Java 17 environment:

1. `./gradlew clean build`
2. `./gradlew runServer` to exercise dedicated-server class loading and command registration.
3. `./gradlew runClient` and join the development server with and without the client companion.
4. Run `/joinguard test simulate_join_load`, `/joinguard test stress 5` and `/joinguard test packet_batch`.
5. Test client disconnect during a managed task, server shutdown with history queued, config reload, emergency/recovery and a static batch near the configured queue limit.

A successful Gradle build does not substitute for a real multi-mod dedicated-server workload test. Third-party integrations must be tested independently before registering their tasks.
