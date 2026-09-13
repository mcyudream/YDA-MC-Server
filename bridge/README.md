# YuDream Minecraft Bridge — Velocity plugin + Fabric sensor / YuDream Minecraft 桥接 — Velocity 插件 + Fabric 传感器

A proxy-side architecture for reporting one downstream server's player activity to YuDream Admin.

将「某一台下游服务器」的玩家活动上报到 YuDream Admin 的代理侧架构。

| Artifact / 产物 | Platform / 平台 | Target / 目标 |
|---|---|---|
| `yudream-velocity-1.0.0.jar` | Velocity proxy plugin | Velocity 3.5.x, Java 21 |
| `yudream_minecraft_server-fabric-26.2-1.0.0.jar` | Fabric dedicated-server mod | Minecraft 26.2, Java 25 |

The proxy plugin is the only component that talks to YuDream Admin. A backend sensor only observes the
server it is installed on and forwards events to the proxy over a plugin message channel.

只有代理插件会与 YuDream Admin 通信。后端传感器只观察所在服务器，并通过插件消息通道把事件转发给代理。

Two sensors speak the same protocol, so a network can mix them:

- `yudream_minecraft_server-fabric-26.2` for Fabric backends.
- The Bukkit/Spigot/Paper plugin in `../src` with `mode: downstream` — same jar that can also report
  directly to YuDream Admin when it runs standalone, which keeps old 1.8.8+ servers supported.

两种传感器使用同一套协议，同一网络可以混用：

- Fabric 后端用 `yudream_minecraft_server-fabric-26.2`。
- Bukkit/Spigot/Paper 后端用 `../src` 的插件并设置 `mode: downstream`；同一个 jar 在
  `mode: standalone` 时仍是直报 YuDream Admin，因此 1.8.8+ 的老服务端也能继续用。

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
                            |  60s snapshots, journaled retry queue   |
                            |  /yudreammc target|status|sync|queue    |
                            +--------------------+--------------------+
                                                 v
                                  YuDream Admin minecraft-server API
```

## Who decides what / 职责划分

- **Presence comes from the proxy.** `/yudreammc` on the proxy derives join and quit from Velocity's
  own `ServerConnectedEvent` / `DisconnectEvent`, so a missing, restarted or broken sensor can never
  produce a wrong online list.
- **Activity comes from the Fabric mod.** A proxy cannot see chat, movement or interactions, so those
  signals are forwarded from the backend and drive AFK transitions only.
- **The reported downstream server is a setting.** A Velocity network maps onto a single YuDream Admin
  server id; `target.server` picks which backend's player list is uploaded, at runtime with
  `/yudreammc target <server>`.
- **The backend holds no credentials.** No API key, no endpoint, no HTTP client on the backend.

- **在线状态由代理决定。** 代理用 Velocity 自己的 `ServerConnectedEvent` / `DisconnectEvent` 推导进服与退服，
  所以模组缺失、重启或故障都不会产生错误的在线名单。
- **活动信号由 Fabric 模组提供。** 代理看不到聊天、移动与交互，这些信号从后端转发过来，只用于驱动挂机状态。
- **上报哪台下游服务器是配置项。** 整个 Velocity 网络对应一个 YuDream Admin 服务器条目，
  `target.server` 决定上传哪台后端的玩家名单，运行期可用 `/yudreammc target <server>` 切换。
- **后端不持有任何凭据。** 后端没有 API Key、没有上报地址、没有 HTTP 客户端。

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

- `bridge/velocity/build/libs/yudream-velocity-1.0.0.jar`
- `bridge/fabric-26.2/build/libs/yudream_minecraft_server-fabric-26.2-1.0.0.jar`

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

1. Put `yudream-velocity-1.0.0.jar` in `plugins/` on the Velocity proxy.
2. Start once; `plugins/yudream-velocity/config.properties` is created.
3. Fill in `api.base-url`, `api.server-id`, `api.api-key` and `target.server`.

Backend / 后端（`target.server` 指向的那台必须装）：

1. Put `yudream_minecraft_server-fabric-26.2-1.0.0.jar` in `mods/` and install Fabric API.
2. Start once; `config/yudream-bridge.properties` is created. Nothing else is required.

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
| `target.server` | *(empty)* | **Which downstream server is reported.** Empty means nothing is reported. |
| `target.require-sensor` | `true` | Refuse to report until that backend's sensor has said hello. |
| `target.sensor-timeout-seconds` | `90` | Warn when a confirmed sensor goes quiet while players are online there. |
| `target.probe-interval-seconds` | `30` | How often the proxy asks the target's sensors to re-announce themselves. |
| `target.move-activity-min-blocks` | `1.0` | Documented proxy-side default; the sensor's own value wins. |
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
| `startup.sync-online-on-enable` | `true` | After `/yudreammc reload`, re-report players already on the target. |
| `shutdown.report-quit-on-disable` | `false` | Force quit reports when the proxy stops. |
| `shutdown.flush-timeout-ms` | `5000` | Shutdown drain timeout. |
| `events.server-switch` | `false` | **Reserved.** Cross-server switch events; see below. |
| `command.permission` | `yudreammc.admin` | Permission node for players. |
| `command.admins` | *(empty)* | Comma separated player names or UUIDs, for proxies without a permissions plugin. |

## Sensor config / 传感器配置

`config/yudream-bridge.properties`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch for forwarding. |
| `heartbeat-seconds` | `20` | How often the sensor re-announces itself while players are online. |
| `activity.chat` / `activity.move` / `activity.interact` / `activity.command` | `true` | Which local signals count as activity. |
| `activity.move-min-blocks` | `1.0` | Movement threshold in blocks. |
| `activity.min-interval-seconds` | `10` | Shortest gap between two forwarded signals for one player. |
| `log.debug` | `false` | Log each forwarded signal. |

## Commands / 指令

On the proxy / 代理端，`/yudreammc`（别名 `/ymc`）：

- `target` shows the reported downstream server.
- `target list` lists every downstream server with its player count and sensor state.
- `target <server>` switches the reported backend, writes it back to `config.properties`, and
  reconciles Admin: everyone reported on the old target is quit, everyone on the new one is joined.
- `status` prints target, reporting state, endpoint, queue depth, sensor state and queries Admin.
- `reload` re-reads the config, rebuilds the queue and re-schedules tasks.
- `sync` re-reports joins for players already on the target and sends a snapshot.
- `queue` prints pending and journaled report counts.

Console can always run it. A player needs `command.permission` (granted by LuckPerms and friends) or
to be listed in `command.admins`.

控制台始终可执行。玩家需要 `command.permission` 权限节点，或出现在 `command.admins` 名单中。

On a backend / 后端，`/yudreammc status`（权限等级 gamemaster）显示与代理的连接状态，
`/yudreammc reload` 重载传感器配置。

## Reporting model / 上报模型

Endpoints are unchanged from the Bukkit plugin and the Forge/NeoForge mods:

接口与 Bukkit 插件、Forge/NeoForge 模组完全一致：

```http
POST /api/plugins/minecraft-server/servers/{serverId}/players/join
POST /api/plugins/minecraft-server/servers/{serverId}/players/quit
POST /api/plugins/minecraft-server/servers/{serverId}/players/afk/start
POST /api/plugins/minecraft-server/servers/{serverId}/players/afk/end
POST /api/plugins/minecraft-server/servers/{serverId}/players/snapshot
GET  /api/plugins/minecraft-server/servers/{serverId}/players?page=1&size=100
```

- `join` / `quit`: a player entering or leaving the **reported downstream server**. Switching from
  one backend to another while the target is involved produces a `quit` + `join` pair, which is what a
  per-server online list means.
- `afk/start` / `afk/end`: driven by activity forwarded from the backend.
- `snapshot`: sent every `snapshot.interval-seconds`, after a target switch, on `/yudreammc sync`, and
  on shutdown. The startup and shutdown snapshots let Admin reconcile events it missed.
- Delivery is **at-least-once**: a crash between a successful POST and the journal rewrite can replay
  one report, which the existing Admin integration already tolerates for join reports.
- Reports are only sent while a target is selected, the endpoint is configured, and — with
  `target.require-sensor=true` — the target's sensor has been confirmed.

## Reserved: cross-server switch events / 预留：跨服切服事件

`events.server-switch` is implemented but off by default. When enabled, a player moving between two
non-target backends emits `POST .../players/server/switch`. YuDream Admin must accept that path and the
`fromServer` / `toServer` fields before it can be turned on; leaving it off keeps the current wire
contract untouched.

`events.server-switch` 已实现但默认关闭。开启后，玩家在两台非目标后端之间移动会上报 `.../players/server/switch`。
在 YuDream Admin 支持该路径与 `fromServer` / `toServer` 字段之前请保持关闭，关闭状态完全不影响现有契约。

## Compatibility / 兼容性

- Velocity 3.5.x requires Java 21, so the plugin is built to Java 21 bytecode.
- Minecraft 26.2 requires Java 25 and is unobfuscated; Loom 1.17 needs no `mappings` declaration.
- Minecraft 26.2 renamed `ResourceLocation` to `Identifier` and replaced op levels with
  `net.minecraft.server.permissions`. A future 26.1 build would need those two differences handled.
- The Bukkit plugin and the Forge/NeoForge mods in this repository are untouched and keep working
  standalone; the bridge is an additional deployment shape, not a replacement.
