# Qyuro JoinFlow

**Qyuro JoinFlow 1.0.0** is a Java 17 / Minecraft 1.20.1 / Forge 47.4.20 mod that measures server tick pressure and budgets explicitly registered, safe-to-defer post-login tasks. It is intended for large Forge modpacks where optional join-related work should yield to the server and to players already in the world.

> JoinFlow does not make a connection magically faster. It smooths work that integrations deliberately hand to JoinFlow. The player may wait longer for that optional work; the normal Forge login, authentication, registry synchronization, vanilla player placement and chunk system remain authoritative.

## What the mod does

- Samples tick-start-to-tick-start MSPT in a fixed 100-sample ring and derives a 40-tick average, recent maximum, EMA and estimated TPS.
- Classifies load as `ECO`, `NORMAL`, `PROTECTED`, `CRITICAL`, `EMERGENCY` or `RECOVERY`; spike protection and gradual recovery are configurable.
- Gives JoinFlow-owned tasks priority-band per-tick allowances and a bounded, aging, round-robin queue. A single indivisible task can still exceed its estimate; JoinFlow measures and reports that overrun rather than pretending it can split arbitrary Java code.
- Provides an opt-in API for safe `POST_JOIN` task providers and for bounded static client data.
- Uses its own optional `qyuro_joinflow:main` channel for versioned Hello/status messages and opt-in batch sync. The client extension is not required for normal server operation.
- Offers a lightweight client HUD, an optional non-pausing screen, a client tick-budgeted batch queue and an opt-in cache for explicitly cacheable static data.
- Records small local history asynchronously; full JSON reports are disabled by default.
- Detects Origins, Apoli, Qyurowl and QyuroLevl once at server start for diagnostics only. Detection is **not** attribution and does not patch or disable those mods.

## Important limitation: where Forge exposes a safe hook

The Forge `PlayerLoggedInEvent` runs after the normal Forge/Minecraft player-join path. JoinFlow uses it to open a short-lived **post-login** session. It does not queue or pause vanilla authentication, the Forge handshake, registry sync, capability setup, vanilla player packets, first-time world placement or vanilla chunk loading. There is no safe general-purpose Forge event that allows a mod to split all of those operations across ticks.

A third-party mod can only be budgeted if it explicitly registers a safe task through the JoinFlow API. Without that integration JoinFlow can observe server MSPT and its own work, but cannot claim that an external mod caused a spike or safely rewrite that mod's networking.

## Requirements and install

- Minecraft **1.20.1**
- Minecraft Forge **47.x** (the bundled MDK/build is pinned to **47.4.20**)
- Java **17**
- Install `build/libs/qyuro_joinflow-1.0.0.jar` on a dedicated Forge server. Clients may install the same jar for the companion UI/cache; clients without it can still join because the channel is optional and client features are advisory.
- The only required mod dependencies are Minecraft 1.20.1 and Forge 47.x. No Fabric, NeoForge, Origins, Apoli, Qyurowl or QyuroLevl classes are linked as hard dependencies.

Forge creates `config/qyurojoinflow-server.toml` and, on clients, `config/qyurojoinflow-client.toml`. Example defaults are included in this repository's `config/` directory. Runtime history and reports use `config/qyurojoinflow/`; client cache uses `config/qyurojoinflow/cache/`.

## Build and run

```bash
java -version                 # must report Java 17
./gradlew test                 # JUnit 5 scheduler-primitive tests
./gradlew clean build          # includes tests and reobfuscated jar
```

Windows:

```bat
gradlew.bat clean build
```

The reobfuscated mod jar is written to `build/libs/`. For the ForgeGradle development dedicated server:

```bash
./gradlew runServer
```

For a deployed server, install Forge 1.20.1-47.x, copy the jar into `mods/`, accept Forge's EULA, then launch with the Forge-generated `run.sh`/`run.bat` (for example `./run.sh nogui`). Do not use a vanilla server jar.

## Commands

All `/joinguard` commands require permission level 2; reload, history reset, debug and synthetic tests require level 3.

- `/joinguard status` — load, MSPT/TPS estimate, current budgets, active JoinFlow sessions, heap sample and own-channel totals.
- `/joinguard status <player>` — active JoinFlow post-login session only; it is not a full Forge login duration.
- `/joinguard joins` — active managed sessions.
- `/joinguard queue` — managed tasks and JoinFlow packet queue. This is not the vanilla login queue.
- `/joinguard hotspots` — `MEASURED` JoinFlow task timings; external phases are not blamed by correlation.
- `/joinguard report`, `/joinguard history`, `/joinguard history reset`.
- `/joinguard adapters` — optional-mod detection and current observational mode.
- `/joinguard reload` — reapplies values already loaded by Forge's config system. Forge's watcher handles TOML reloads; settings that affect channel registration require reconnect/restart.
- `/joinguard debug` — reports debug/profile settings.
- `/joinguard test simulate_join_load`, `/joinguard test stress <1-20>`, `/joinguard test packet_batch` — bounded synthetic scheduler tests. They do not touch players/worlds or send test payloads to players.

## Integration API sketch

Register only small, bounded work whose result is safe to defer. The task runs on the **logical server thread**; it may use `context.player()` during that call. Do not capture or retain a `ServerPlayer`, `Level`, `Entity`, chunk, inventory or capability in a provider closure. Do not perform file, database, HTTP or blocking work there.

```java
JoinFlowAPI.registerTaskProvider(
    "exampleaddon", "stats_sync", JoinPhase.POST_JOIN,
    TaskPriority.LOW, 1_000L, Set.of(), true,
    context -> {
        if (context.isCancelled()) return;
        context.player().ifPresent(player -> {
            // A short, bounded operation owned by this integration.
        });
    }
);
```

Use `JoinFlowAPI.registerTask(server, playerId, ...)` for a one-off active-session task. `safeToDefer` must be `true`; authentication, inventory transactions, damage, registry mutation and unknown operations must stay on their normal Forge path.

Static sync is separately opt-in:

```java
JoinFlowAPI.registerStaticData("exampleaddon", "exampleaddon:static_table", 1, bytes, true);
JoinFlowAPI.requestStaticSync(server, playerId, "exampleaddon:static_table");
```

Set `cacheable=true` only for small, immutable, non-secret bytes safe to persist on the client. The client handler API runs on the client thread under its own measured budget; it cannot safely be forcibly stopped if one handler is indivisible.

## Documentation

- [CONFIG.md](CONFIG.md) — server/client options and defaults.
- [ARCHITECTURE.md](ARCHITECTURE.md) — event boundaries, queues, protocol, thread and safety model.
- [BENCHMARK.md](BENCHMARK.md) — automated test scope and bounded synthetic scheduler exercise.

## Guarantees and non-guarantees

JoinFlow never kicks a player because the server is busy, never calls `Thread.sleep()` or `System.gc()`, never changes view distance automatically, never intercepts another mod's Netty handlers, and never moves Minecraft world objects to a worker thread. It reduces and smooths **controllable JoinFlow-owned work**; it does not promise zero lag, zero freezes or zero TPS loss.
