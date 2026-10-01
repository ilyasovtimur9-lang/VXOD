# Tests and bounded benchmark

## Automated tests

The Gradle `test` task uses JUnit 5 and covers the standalone scheduler primitives: tick-time windows, load projection, hysteretic recovery, priority aging and task-cost estimation.

```bash
./gradlew test
./gradlew clean build
```

The unit tests do not replace Forge lifecycle validation. Run the dedicated-server smoke test described in [ARCHITECTURE.md](ARCHITECTURE.md) on Java 17 with the target Forge MDK.

## In-game synthetic scheduler benchmark

Use a staging server, not a busy production server. As an operator with permission level 3:

```text
/joinguard status
/joinguard test stress 5
/joinguard status
/joinguard hotspots
```

The stress command accepts 1–20 synthetic fair-share lanes. It queues up to 100 tiny arithmetic-only tasks per lane across the five scheduler priorities (at most 2,000 tasks for 20 lanes). It uses no `ServerPlayer`, world, chunk, inventory or network payload, and runs under the regular adaptive budgets and task-count cap. Queue depth and measured JoinFlow-only callback costs can be compared before and after it drains.

This is a bounded scheduler exercise, **not** a benchmark of vanilla/Forge login, a real modpack, disk speed or a third-party mod. Use a dedicated staging server and representative, controlled player/modpack workload for those measurements. Do not interpret MSPT correlation as causal attribution.
