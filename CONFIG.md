# Configuration

Forge generates the registered specs on first launch. The repository also contains default examples in `config/qyurojoinflow-server.toml` and `config/qyurojoinflow-client.toml`.

## Server: `config/qyurojoinflow-server.toml`

| Section / option | Default | Meaning |
|---|---:|---|
| `general.enabled` | `true` | Enables JoinFlow-owned task execution. Disabling cancels queued optional sessions; monitoring remains passive. |
| `general.debug` | `false` | Enables rate-limited scheduler diagnostics. |
| `adaptive.enabled` | `true` | Enables adaptive state-based budgets. |
| `adaptive.auto_tuning` | `true` | If false, uses the configured normal/fixed budgets instead of reducing them by load. |
| `adaptive.eco_mspt` | `12` | Upper bound for ECO. |
| `adaptive.normal_mspt` | `20` | NORMAL threshold. |
| `adaptive.protected_mspt` | `35` | PROTECTED threshold. |
| `adaptive.critical_mspt` | `60` | CRITICAL threshold. |
| `adaptive.emergency_mspt` | `100` | EMERGENCY threshold. |
| `budget.join_min_ms` | `0.25` | Lower floor for the managed high/critical task allowance. |
| `budget.join_normal_ms` | `4` | Normal high/critical task budget per tick. |
| `budget.join_protected_ms` | `2` | High/critical task budget in PROTECTED. |
| `budget.join_critical_ms` | `0.75` | High/critical task budget in CRITICAL. |
| `budget.join_emergency_ms` | `0.25` | High/critical allowance in EMERGENCY. |
| `budget.post_join_normal_ms` | `2` | NORMAL-priority post-join budget per tick. |
| `budget.post_join_protected_ms` | `1` | NORMAL-priority post-join budget in PROTECTED. |
| `budget.post_join_critical_ms` | `0.5` | NORMAL-priority post-join budget in CRITICAL. |
| `budget.background_normal_ms` | `0.5` | LOW/BACKGROUND allowance in ECO/NORMAL. |
| `budget.background_protected_ms` | `0.25` | LOW/BACKGROUND allowance in PROTECTED. |
| `queue.max_concurrent_joins` | `1` | Used by predictive pressure and fair-budget scaling; it does not gate Forge's actual login handshake. |
| `queue.max_queue_size` | `100` | Maximum active JoinFlow post-login sessions. Overflow never kicks a player. |
| `queue.max_tasks_per_player` | `500` | Per-UUID queued-task limit. |
| `queue.max_total_tasks` | `5000` | Global queued-task limit. |
| `queue.max_pending_bytes_per_player` | `262144` | Maximum queued JoinFlow network bytes for one player. |
| `queue.allow_parallel_safe_tasks` | `true` | Allows multiple short independent task callbacks to be scheduled in a tick; it does **not** run Minecraft objects on worker threads. |
| `queue.aging_interval_ticks` | `200` | Waiting time between LOW/BACKGROUND promotion steps. Aging never promotes to CRITICAL. |
| `queue.max_join_session_seconds` | `120` | Logs and cancels safe optional work for a stuck post-login session; no automatic kick. |
| `queue.max_tasks_per_tick` | `32` | Maximum JoinFlow task callbacks considered per tick across priority groups. |
| `spike_guard.enabled` | `true` | Enables spike-based protection. |
| `spike_guard.spike_threshold_ms` | `75` | Tick sample that counts as a spike. |
| `spike_guard.spikes_before_critical` | `2` | Consecutive spike count that raises the held state to CRITICAL. |
| `spike_guard.recovery_ticks` | `20` | Stable ticks between gradual budget recovery steps and spike hold duration. |
| `network.enabled` | `true` | Enables only JoinFlow's optional channel and own outbound queue. |
| `network.batching` | `true` | Enables bounded split static-data batches. |
| `network.delta_sync` | `true` | Enables static-data hash/version cache checks. |
| `network.max_packet_bytes` | `24000` | Maximum JoinFlow packet estimate; batch payloads are split with room for headers. |
| `network.max_telemetry_packets_per_second` | `4` | Server-side per-session limit for client advisory messages. Excess is ignored, not kicked. |
| `network.max_outbound_packets_per_tick` | `8` | Maximum JoinFlow packets scheduled in a tick before load-state reduction. |
| `network.max_outbound_queue_entries` | `1024` | Global cap for queued JoinFlow messages. |
| `network.max_static_sync_bytes` | `1048576` | Total registered immutable static bytes accepted by the public API; the registry also has a fixed 4,096-entry cap. |
| `client.enabled` | `true` | Allows the optional companion handshake. Missing/incompatible clients remain a server-only fallback. |
| `client.send_progress` | `true` | Sends coalesced session progress only when a client negotiated support. |
| `client.send_server_state` | `true` | Sends state changes, not duplicate state every tick. |
| `client.heartbeat_interval_ms` | `1000` | Requested heartbeat interval; clamped to 500–10000 ms. |
| `profiling.mode` | `BASIC` | `OFF`, `BASIC` or `FULL`. FULL retains more per-task/queue detail. |
| `profiling.history_size` | `100` | Capped recent join history. |
| `profiling.slow_task_ms` | `5` | Threshold for aggregated slow-task warnings. |
| `profiling.very_slow_task_ms` | `20` | Major warning threshold. |
| `profiling.critical_task_ms` | `50` | Critical warning threshold. |
| `profiling.write_reports` | `false` | Enables optional full JSON reports. |
| `profiling.write_full_report_every` | `10` | Writes one report every N recorded post-login sessions when reports are enabled. |
| `compatibility.origins/apoli/qyurowl/qyurolevl` | `true` | Toggles one-time optional-mod detection/logging, not automatic patching. |
| `failsafe.enabled` | `true` | Enables fail-soft behavior for JoinFlow-owned tasks. |
| `failsafe.disable_failed_optional_adapter` | `true` | Removes a failing registered provider for future sessions. |

The budget is an allowance for JoinFlow-owned callbacks, not a hard preemptive CPU limit. One indivisible callback can exceed it. JoinFlow records that duration and cannot safely interrupt Java code.

## Client: `config/qyurojoinflow-client.toml`

| Section / option | Default | Meaning |
|---|---:|---|
| `general.enabled` | `true` | Enables the optional companion. If false, the client sends no JoinFlow handshake/telemetry. |
| `general.debug` | `false` | Client-side diagnostics. |
| `ui.enabled` | `true` | Enables JoinFlow's lightweight HUD. |
| `ui.show_progress` | `true` | Shows the progress bar and percentage. |
| `ui.show_load_state` | `true` | Shows the server-reported load state. |
| `ui.open_screen` | `false` | Opt-in non-pausing screen; default is the less intrusive HUD. |
| `sync.client_tick_budget_ms` | `2` | Per-client-tick budget for reassembly/application work. A single chunk or handler remains indivisible. |
| `sync.max_cached_entries` | `1000` | Maximum in-memory/persisted cache entries. |
| `sync.max_cached_bytes` | `4194304` | Maximum cache and in-flight assembly bytes. |
| `telemetry.enabled` | `true` | Sends only JoinFlow queue depth, JoinFlow batch processing time, readiness and cache readiness. |
| `telemetry.interval_ms` | `1000` | Minimum client heartbeat/status interval, clamped to 500–10000 ms. |

## Reload and files

Forge owns the config watcher and fires its config reload events; `/joinguard reload` reapplies the current registered values to JoinFlow's controllers. No large config file is read every tick. Budget thresholds and queue limits are read live. Forge/channel registration and client-side configuration cannot make an already negotiated connection safely change protocol; reconnect/restart may be required for those changes.

The server writes `config/qyurojoinflow/history/history.json` asynchronously. Optional reports are written under `config/qyurojoinflow/reports/`. Client cache payloads are written under `config/qyurojoinflow/cache/` only when an integration explicitly marks static, non-secret bytes as cacheable. No player name, IP address or client file inventory is written by JoinFlow.
