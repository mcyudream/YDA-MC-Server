# YuDream Minecraft Bridge — Velocity plugin + Fabric mod / YuDream Minecraft 桥接 — Velocity 插件 + Fabric 模组

A proxy-side architecture for reporting every downstream server's player activity to YuDream Admin,
plus a Fabric mod that reports on its own when there is no proxy.

将代理网中**每一台下游服务器**的玩家活动上报到 YuDream Admin 的代理侧架构；Fabric 模组在没有代理时也能自行上报。

| Artifact / 产物 | Platform / 平台 | Target / 目标 |
|---|---|---|
| `yudream-velocity-1.1.0.jar` | Velocity proxy plugin | Velocity 3.5.x, Java 21 |
| `yudream_minecraft_server-fabric-26.2-1.1.0.jar` | Fabric dedicated-server mod | Minecraft 26.2, Java 25. Two modes: `downstream` or `standalone` |

In the default `downstream` mode the proxy plugin is the only component that talks to YuDream Admin: a
backend observes the server it is installed on and forwards events to the proxy over a plugin message
channel. In `standalone` mode there is no proxy, so the same mod reports to Admin itself.

默认的 `downstream` 模式下，只有代理插件会与 YuDream Admin 通信：后端只观察所在服务器，并通过插件消息通道把事件转发给代理。
`standalone` 模式下没有代理，同一个模组直接自行上报 Admin。

Three runtimes can act as a sensor, and they all speak the same protocol, so a network can mix them:

- `yudream_minecraft_server-fabric-26.2` for Fabric backends, with `mode=downstream`.
- The Bukkit/Spigot/Paper plugin in `../src` with `mode: downstream`.
- The standalone halves of both, which report directly to Admin instead: the Fabric mod with
  `mode=standalone`, the Bukkit plugin with `mode: standalone`. A single server of either kind needs no
  proxy at all.

三种运行时都能当传感器，且使用同一套协议，同一网络可以混用：

- Fabric 后端用 `yudream_minecraft_server-fabric-26.2`，设置 `mode=downstream`。
- Bukkit/Spigot/Paper 后端用 `../src` 的插件，设置 `mode: downstream`。
- 两者的独立模式都可以直报 Admin：Fabric 模组设 `mode=standalone`，Bukkit 插件设 `mode: standalone`；
  单台服务器因此完全不需要代理。

```
 backend A -- Fabric sensor --+
 backend B -- Fabric sensor --+-- yudream:bridge plugin message channel (JSON)
 backend C -- Fabric sensor --+                 |
                                                v
                            +-----------------------------------------+
                            |  Velocity proxy - yudream-velocity      |
                            |  presence (authoritative)               |
                            |  sensor hello / probe handshake          |
                            |  activity -> AFK state machine          |
                            |  one roster per sub-server, journaled    |
                            |  /yudreammc target|status|sync|queue     |
                            +--------------------+--------------------+
                                                 v
                                  YuDream Admin minecraft-server API

 single server (no proxy)
 backend D -- Fabric/Bukkit standalone ---- HTTPS ---->  the same API
```

## Who decides what / 职责划分

- **Presence comes from the proxy.** `/yudreammc` on the proxy derives join and quit from Velocity's
  own `ServerConnectedEvent` / `DisconnectEvent`, so a missing, restarted or broken sensor can never
  produce a wrong online list. On a single server the runtime's own join/quit events are authoritative.
- **Activity comes from the backend.** A proxy cannot see chat, movement or interactions, so those
  signals are forwarded from the backend and drive AFK transitions only. In standalone mode the same
  signals are read locally.
- **Every downstream server is reported, as its own sub-server.** A Velocity network maps onto a single
  YuDream Admin server id, and each backend's players are reported under that backend's name, so a
  player who spends time on two backends accumulates time in both. `target.server` is only the marker
  for which backend new players land on; it no longer decides what is uploaded, and changing it with
  `/yudreammc target <server>` must not move or drop anyone's time.
- **A downstream backend holds no credentials.** No API key, no endpoint, no HTTP client. Only the
  proxy, or a standalone server, uploads.

- **在线状态由代理决定。** 代理用 Velocity 自己的 `ServerConnectedEvent` / `DisconnectEvent` 推导进服与退服，
  所以模组缺失、重启或故障都不会产生错误的在线名单。单机部署则由运行时自己的进服/退服事件负责。
- **活动信号由后端提供。** 代理看不到聊天、移动与交互，这些信号从后端转发过来，只用于驱动挂机状态；
  独立模式下同样的信号在本地直接读取。
- **每一台下游服务器都作为独立子服上报。** 整个 Velocity 网络对应一个 YuDream Admin 服务器条目，
  每台后端的玩家名单按该后端的名字分别上报，因此玩家在 A 服与 B 服的时长会分别累计。
  `target.server` 只是「新玩家落在哪台」的标记，不再决定上传内容；
  用 `/yudreammc target <server>` 改动它不得转移或丢弃任何人的时长。
- **下游后端不持有任何凭据。** 后端没有 API Key、没有上报地址、没有 HTTP 客户端；
  只有代理或独立模式的服务端才上传。

## Build / 构建

Use JDK 25 for the whole Gradle build. Minecraft 26.2 needs Java 25; the proxy plugin targets Java 21
because Velocity 3.5.x does.

整个 Gradle 构建请使用 JDK 25。Minecraft 26.2 需要 Java 25；代理插件目标为 Java 21，因为 Velocity 3.5.x 要求 Java 21。

```powershell
cd bridge
$env:JAVA_HOME = "C:\path\to\jdk-25"
gradle :common:test :velocity:build :fabric262:build
```

If Gradle is not installed, or `services.gradle.org` is unstable in China, use the bundled helper. It
downloads Gradle 9.5.1 from Tencent's mirror and picks up a JDK from `JAVA_HOME` or the usual install
locations:

如果本机没有 Gradle，或国内访问 `services.gradle.org` 不稳定，可用随附脚本。它会从腾讯镜像下载
Gradle 9.5.1，并从 `JAVA_HOME` 或常见安装位置自动寻找 JDK：

```powershell
cd bridge
.\scripts\gradle-cn.ps1 :common:test :velocity:build :fabric262:build
```

Outputs / 产物：

- `bridge/velocity/build/libs/yudream-velocity-1.1.0.jar`
- `bridge/fabric-26.2/build/libs/yudream_minecraft_server-fabric-26.2-1.1.0.jar`

Repository mirrors can be overridden without editing the build files, matching `mods/settings.gradle`:

无需修改构建脚本即可切换镜像源（与 `mods/settings.gradle` 一致）：

```powershell
$env:MAVEN_PUBLIC_MIRROR_URL = "https://maven.aliyun.com/repository/public"
$env:MAVEN_CENTRAL_MIRROR_URL = "https://maven.aliyun.com/repository/central"
$env:GRADLE_PLUGIN_MIRROR_URL = "https://maven.aliyun.com/repository/gradle-plugin"
$env:PAPERMC_REPO_URL = "https://repo.papermc.io/repository/maven-public/"
$env:FABRIC_MAVEN_URL = "https://maven.fabricmc.net/"
```

`common` holds the shared, platform-free core: real JSON parsing, the HTTP client, the journaled retry
queue, the AFK state machine and the bridge protocol. It is compiled into both artifacts through a
`srcDir` include, so each jar is self-contained with no shading step.

`common` 是无平台依赖的共享核心：真正的 JSON 解析、HTTP 客户端、带日志的持久化重试队列、挂机状态机以及桥接协议。
它通过 `srcDir` 直接编译进两个产物，因此每个 jar 都是自包含的，不需要 shading。

## Deploy / 部署

Proxy / 代理：

1. Put `yudream-velocity-1.1.0.jar` in `plugins/` on the Velocity proxy.
2. Start once; `plugins/yudream-velocity/config.properties` is created.
3. Fill in `api.base-url`, `api.server-id` and `api.api-key`; set `target.server` to the backend new
   players land on. That marker is optional — every backend is reported either way.

Backend / 后端（每台需要子服明细的后端都装，不只是 `target.server` 指向的那台）：

1. Put `yudream_minecraft_server-fabric-26.2-1.1.0.jar` in `mods/` and install Fabric API.
2. Start once; `config/yudream-bridge.properties` is created. Leave `mode=downstream`; nothing else is
   required, because the proxy owns the credentials.

Single server, no proxy / 单机、没有代理：

1. Put the same mod in `mods/` and set `mode=standalone`.
2. Fill in `api.base-url`, `api.server-id` and `api.api-key`; the mod reports to Admin itself.

Both sides must also be reachable by plugin messaging, which needs no extra configuration on Velocity.

两侧都需要插件消息可达，Velocity 侧无需额外配置。

## Proxy config / 代理配置

`plugins/yudream-velocity/config.properties`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `api.base-url` | `http://127.0.0.1:8080` | YuDream Admin base URL, no trailing slash. |
| `api.server-id` | *(empty)* | Server id in the Admin minecraft-server plugin. One id covers the whole network. |
| `api.api-key` | *(empty)* | Needs `plugin:minecraft-server:report`. Never logged. |
| `api.include-server-name-in-snapshot` | `false` | Adds a top-level `serverName` to snapshot bodies. Leave off unless Admin ignores unknown JSON fields. |
| `target.server` | *(empty)* | The default / login-entry marker: which backend new players land on. **Does not decide what is uploaded** — every backend is reported as its own sub-server. Empty means no backend is marked. |
| `target.require-sensor` | `true` | Skip a backend whose sensor has not said hello. Applied per sub-server: every other backend keeps reporting. |
| `target.sensor-timeout-seconds` | `90` | Warn when a confirmed sensor goes quiet while players are online there. |
| `target.probe-interval-seconds` | `30` | How often the proxy asks connected backends' sensors to re-announce themselves. |
| `target.move-activity-min-blocks` | `1.0` | Documented proxy-side default; the sensor's own value wins. |
| `topology.enabled` | `true` | Report the downstream server list, which is what Admin's one-click proxy resolve reads. |
| `topology.interval-seconds` | `60` | Topology report interval. |
| `topology.addresses` | *(empty)* | Extra `host:port` the proxy advertises, for Admin's address-matched resolve. |
| `snapshot.interval-seconds` | `60` | Full online-player snapshot interval. |
| `http.connect-timeout-ms` / `http.read-timeout-ms` | `5000` / `8000` | HTTP timeouts. |
| `http.retry-attempts` / `http.retry-delay-ms` | `3` / `1500` | Retry policy per report. |
| `http.queue-capacity` | `1000` | Bounded queue; overflow is dropped with a warning. |
| `http.persist-queue` | `true` | Journal undelivered reports across restarts. |
| `http.queue-file` | `report-queue.jsonl` | Journal file, relative to the plugin data directory. |
| `http.log-queued` / `log-attempts` / `log-success` / `log-failures` | `false` / `false` / `true` / `true` | Report log switches. |
| `http.log-payload` | `false` | Include `playerId` and `eventAt`. The API key is never logged. |
| `afk.enabled` | `true` | AFK transition reporting. |
| `afk.timeout-seconds` | `300` | Idle time before `afk/start`. |
| `afk.check-interval-seconds` | `30` | AFK evaluation interval. |
| `startup.sync-online-on-enable` | `true` | After `/yudreammc reload`, re-report every player already online, per sub-server. |
| `shutdown.report-quit-on-disable` | `false` | Force quit reports when the proxy stops. |
| `shutdown.flush-timeout-ms` | `5000` | Shutdown drain timeout. |
| `events.server-switch` | `false` | **Reserved.** Cross-server switch events; see below. |
| `command.permission` | `yudreammc.admin` | Permission node for players. |
| `command.admins` | *(empty)* | Comma separated player names or UUIDs, for proxies without a permissions plugin. |

## Fabric mod config / Fabric 模组配置

`config/yudream-bridge.properties`

| Key | Default | Meaning |
|---|---|---|
| `mode` | `downstream` | `downstream` = sensor behind a proxy, uploads nothing and needs no credentials. `standalone` = no proxy, so the mod reports to Admin itself. Switch at runtime with `/yudreammc mode <standalone\|downstream>`. |
| `enabled` | `true` | Master switch. |
| `heartbeat-seconds` | `20` | How often the sensor re-announces itself while players are online. Downstream only. |
| `activity.chat` / `activity.move` / `activity.interact` / `activity.command` | `true` | Which local signals count as activity. Both modes. |
| `activity.move-min-blocks` | `1.0` | Movement threshold in blocks. |
| `activity.min-interval-seconds` | `10` | Shortest gap between two signals for one player. |
| `log.debug` | `false` | Log each forwarded activity signal. |

Standalone mode also reads the Admin endpoint, the HTTP policy, the AFK thresholds and the shutdown
behaviour. They are the same keys the proxy plugin uses, so the table under
[Proxy config](#proxy-config--代理配置) applies unchanged: `api.base-url`, `api.server-id`,
`api.api-key`, `snapshot.interval-seconds`, `afk.*`, `startup.*`, `shutdown.*` and `http.*`. A server
with no sub-server dimension reports under Admin's `default` bucket, so leave
`api.include-server-name-in-snapshot` off.

独立模式还会读取 Admin 地址、HTTP 策略、挂机阈值与关闭行为，键名与代理插件完全一致，
参见上方[代理配置](#proxy-config--代理配置)表：`api.base-url`、`api.server-id`、`api.api-key`、
`snapshot.interval-seconds`、`afk.*`、`startup.*`、`shutdown.*`、`http.*`。
没有子服维度的单机会归入 Admin 的 `default` 桶，因此 `api.include-server-name-in-snapshot` 保持关闭。

## Commands / 指令

On the proxy / 代理端，`/yudreammc`（别名 `/ymc`）：

- `target` shows the default / login-entry backend. Reporting does not depend on it.
- `target list` lists every downstream server with its player count and sensor state.
- `target <server>` marks which backend new players land on, and writes it back to
  `config.properties`. It is a marker only: it no longer quits, joins or re-reconciles anyone, so
  changing it cannot move or drop a player's accumulated time.
- `status` prints target, reporting state, endpoint, queue depth, sensor state and queries Admin.
- `reload` re-reads the config, rebuilds the queue and re-schedules tasks.
- `sync` re-reports joins for every online player, per sub-server, and sends a grouped snapshot.
- `queue` prints pending and journaled report counts.
- `topology` reports the downstream server list on demand.

Console can always run it. A player needs `command.permission` (granted by LuckPerms and friends) or
to be listed in `command.admins`.

控制台始终可执行。玩家需要 `command.permission` 权限节点，或出现在 `command.admins` 名单中。

On a backend / 后端，`/yudreammc status`（权限等级 gamemaster）显示当前模式与链路状态，
`/yudreammc mode <standalone|downstream>` 切换模式并写回配置文件，`/yudreammc reload` 重载配置。

## Reporting model / 上报模型

Endpoints are unchanged from the Bukkit plugin and the Forge/NeoForge mods:

接口与 Bukkit 插件、Forge/NeoForge 模组完全一致：

```http
POST /api/plugins/minecraft-server/servers/{serverId}/players/join
POST /api/plugins/minecraft-server/servers/{serverId}/players/quit
POST /api/plugins/minecraft-server/servers/{serverId}/players/afk/start
POST /api/plugins/minecraft-server/servers/{serverId}/players/afk/end
POST /api/plugins/minecraft-server/servers/{serverId}/players/snapshot
POST /api/plugins/minecraft-server/servers/{serverId}/topology        (bound topology)
POST /api/plugins/minecraft-server/report/topology                    (address-matched, no server id)
GET  /api/plugins/minecraft-server/servers/{serverId}/players?page=1&size=100
```

- Every player event carries a `server` field naming the backend it happened on. Admin keeps one
  time bucket per sub-server, so a player who spends an hour on `fabric` and half an hour on `paper`
  has both, and the record's total is their sum.
- `join` / `quit`: a player entering or leaving **one** backend, and a switch is exactly a `quit` on
  the old backend plus a `join` on the new one. That is what makes per-sub-server time accumulate
  instead of a switch reading as a departure.
- `afk/start` / `afk/end`: driven by activity forwarded from the backend, or read locally in
  standalone mode. A quit on a backend closes that backend's running timer and leaves the player's
  other backends alone.
- `snapshot` is sent as one roster per sub-server:
  `{"observedAt":..,"servers":[{"name":"fabric","players":[..]},{"name":"paper","players":[]}]}`.
  A listed sub-server with an empty roster is a real signal — it tells Admin nobody is left there,
  which is how a quit missed during a crash gets repaired. Sub-servers the sensor gate excludes are
  omitted entirely, so a snapshot never claims to know about them. Standalone mode sends the older
  flat body, with no sub-server dimension.
- Events without a `server` field, and flat snapshots, keep their original meaning: they are filed
  under Admin's `default` bucket. Records written before sub-server support read back as a single
  `default` bucket seeded from their totals, so no migration is needed.
- Delivery is **at-least-once**: a crash between a successful POST and the journal rewrite can replay
  one report, which the existing Admin integration already tolerates for join reports.
- Reports are only sent while the endpoint is configured, and — with `target.require-sensor=true` —
  for sub-servers whose sensor has been confirmed. That gate is per sub-server: one silent backend
  never suppresses the others.

## Reserved: cross-server switch events / 预留：跨服切服事件

`events.server-switch` is implemented but off by default. When enabled, a player moving between two
backends also emits `POST .../players/server/switch`. It is an extra informational event: the
`quit` + `join` pair that carries the actual time is sent either way. YuDream Admin must accept that
path and the `fromServer` / `toServer` fields before it can be turned on.

`events.server-switch` 已实现但默认关闭。开启后，玩家在两台后端之间移动会额外上报 `.../players/server/switch`。
它只是补充信息，真正承载时长的 `quit` + `join` 无论如何都会发送。
在 YuDream Admin 支持该路径与 `fromServer` / `toServer` 字段之前请保持关闭。

## Compatibility / 兼容性

- Velocity 3.5.x requires Java 21, so the plugin is built to Java 21 bytecode.
- Minecraft 26.2 requires Java 25 and is unobfuscated; Loom 1.17 needs no `mappings` declaration.
- Minecraft 26.2 renamed `ResourceLocation` to `Identifier` and replaced op levels with
  `net.minecraft.server.permissions`. A future 26.1 build would need those two differences handled.
- The Bukkit plugin and the Forge/NeoForge mods in this repository are untouched and keep working
  standalone; the bridge is an additional deployment shape, not a replacement.
- The Fabric mod defaults to `mode=downstream` because that is what it has always done. An existing
  proxy deployment therefore needs no config change, and a backend can never start uploading in
  parallel with the proxy that already reports it.
