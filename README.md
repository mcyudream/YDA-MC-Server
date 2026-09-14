# YuDream Minecraft Server Bridge / YuDream Minecraft 服务端桥接

Reports Minecraft player activity to the YuDream Admin `minecraft-server` plugin.

将 Minecraft 玩家活动上报到 YuDream Admin 的 `minecraft-server` 插件。

This repository contains five artifacts that share the same remote API:

本仓库包含五个产物，共用同一套远程 API：

| Artifact / 产物 | Platform / 平台 | Target / 目标 |
|---|---|---|
| `yudream-minecraft-server-bukkit` | Bukkit / Spigot / Paper plugin | Compiled against Spigot 1.12.2 API, Java 8; runs on 1.8.8+. Two modes: standalone or proxy backend sensor |
| `yudream_minecraft_server-forge-1.20.1` | Forge dedicated-server mod | Minecraft 1.20.1, Java 17 |
| `yudream_minecraft_server-neoforge-1.21.1` | NeoForge dedicated-server mod | Minecraft 1.21.1, Java 21 |
| `yudream-velocity` | Velocity proxy plugin | Velocity 3.5.x, Java 21 |
| `yudream_minecraft_server-fabric-26.2` | Fabric dedicated-server mod | Minecraft 26.2, Java 25. Two modes: standalone or proxy backend sensor |

The bridge reports join, quit, AFK start/end, and a full online-player snapshot.

桥接会上报进服、退服、开始挂机、结束挂机，以及完整在线玩家快照。

The Bukkit plugin and the Forge/NeoForge mods each serve a single server on their own, and the Bukkit
plugin and the Fabric mod can each join the proxy architecture as a sensor instead. The Velocity +
Fabric pair is a deployment shape for proxy networks: the proxy plugin uploads, the backend sensors
only forward activity, and every downstream server is reported as its own sub-server.
See [`bridge/README.md`](bridge/README.md).

Bukkit 插件与 Forge/NeoForge 模组各自服务于单台服务器；Bukkit 插件与 Fabric 模组也都能改为传感器加入代理架构。
Velocity + Fabric 是面向代理网络的一种部署形态：由代理插件负责上报，后端传感器只转发玩家活动，
每一台下游服务器都作为独立的子服上报。

## License / 许可证

This project is licensed under the [MIT License](LICENSE).

本项目采用 [MIT 许可证](LICENSE)。

You may use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the software, provided that the copyright notice and permission notice are included in all copies or substantial portions of the software.

你可以自由使用、复制、修改、合并、发布、再授权和/或销售本软件的副本，但必须在所有副本或实质性部分中保留版权声明和许可声明。

The software is provided "as is", without warranty of any kind.

本软件按“原样”提供，不附带任何形式的担保。

See [`LICENSE`](LICENSE) for the full text.

完整条文见 [`LICENSE`](LICENSE)。

Copyright (c) 2026 YuDream

## Endpoints / 接口

The plugin and mods call:

插件和模组会调用：

- `POST /api/plugins/minecraft-server/servers/{serverId}/players/join`
- `POST /api/plugins/minecraft-server/servers/{serverId}/players/quit`
- `POST /api/plugins/minecraft-server/servers/{serverId}/players/afk/start`
- `POST /api/plugins/minecraft-server/servers/{serverId}/players/afk/end`
- `POST /api/plugins/minecraft-server/servers/{serverId}/players/snapshot`
- `GET /api/plugins/minecraft-server/servers/{serverId}/players`

Each POST body is:

每次 POST 的请求体为：

```json
{
  "playerId": "player-uuid",
  "playerName": "Steve",
  "eventAt": 1783512000000
}
```

The bridge sends a complete online-player snapshot at startup, every 60 seconds, and during a normal shutdown. The admin plugin uses it to recover missed join/quit events; an offline server ping remains the fallback for process crashes.

桥接会在启动时、每 60 秒、以及正常关闭时发送完整在线玩家快照。管理端用它补齐漏掉的进服/退服事件；进程崩溃时仍可回退到离线服务器探测。

Authentication uses:

鉴权方式：

```http
X-API-Key: yda_xxx
```

The API key needs `plugin:minecraft-server:report` for event reporting. `/yudreammc status` also needs `plugin:minecraft-server:manage`.

上报事件需要 API Key 具备 `plugin:minecraft-server:report` 权限。`/yudreammc status` 还需要 `plugin:minecraft-server:manage`。

## Bukkit / Spigot / Paper

Supported server API target: Bukkit/Spigot 1.8.8 and newer, including 1.12.2, 1.19, 1.20 and newer Paper/Spigot versions. The plugin is compiled against the 1.12.2 API but only uses Bukkit APIs already present in 1.8, and avoids NMS/version-specific APIs, so the same jar can run across old and modern servers.

支持 Bukkit/Spigot 1.8.8 及更新版本，包括 1.12.2、1.19、1.20 以及更新的 Paper/Spigot。插件按 1.12.2 API 编译，但只使用 1.8 已有的 Bukkit API，不依赖 NMS 或版本专用 API，因此同一个 jar 可以在新旧服务端上运行。

Runtime Java requirement follows the Minecraft server:

运行时 Java 版本跟随 Minecraft 服务端：

- 1.8.x to 1.12.2 usually run on Java 8.
- 1.19.x usually runs on Java 17.
- 1.20.x usually runs on Java 17, and newer Paper builds may require newer Java.

The plugin itself is compiled to Java 8 bytecode, so it can run on Java 8 and newer JVMs. `plugin.yml` declares `api-version: 1.13` so high-version servers load it as a modern Bukkit plugin; older 1.8/1.12 servers ignore that metadata.

插件本身编译为 Java 8 字节码，可在 Java 8 及更新 JVM 上运行。`plugin.yml` 声明 `api-version: 1.13`，高版本服务端会按现代 Bukkit 插件加载；旧的 1.8/1.12 服务端会忽略该元数据。

On Paper 1.19/1.20+, the plugin also registers Paper's modern `AsyncChatEvent` when it exists, while keeping Bukkit's legacy chat event for older servers.

在 Paper 1.19/1.20+ 上，若存在 Paper 的 `AsyncChatEvent` 也会注册，同时保留旧版 Bukkit 聊天事件以兼容更早的服务端。

### Modes / 两种模式

`config.yml` 的 `mode` 决定这个插件干什么，运行期可用 `/yudreammc mode <standalone|downstream>` 切换并写回配置文件。

| Mode | Behaviour |
|---|---|
| `standalone` (default) | The historical behaviour. This server talks to YuDream Admin itself; nothing else is required. |
| `downstream` | This server sits behind a Velocity/BungeeCord proxy. The plugin becomes a sensor: it forwards player activity to the proxy over the `yudream:bridge` plugin message channel and uploads nothing itself. The proxy owns presence, AFK state and every HTTP call, and picks the reported downstream server with its own `target.server`. |

| 模式 | 行为 |
|---|---|
| `standalone`（默认） | 与历史行为完全一致：本机直接上报 YuDream Admin，不需要其它组件。 |
| `downstream` | 本机位于 Velocity/BungeeCord 代理之后。插件变成传感器：通过 `yudream:bridge` 插件消息通道把玩家活动转发给代理，自己不上传任何东西。在线状态、挂机判定与全部 HTTP 调用都由代理负责，上报的是哪台下游服务器由代理侧的 `target.server` 决定。 |

下游模式与 Fabric 传感器完全对称——代理无法自行看到聊天、移动与交互，所以这些信号必须由后端提供；而进服/退服始终以代理自己的连接事件为准。插件消息层与代理实现无关，Velocity 与 BungeeCord/Waterfall 走同一套 `sendPluginMessage`。

`downstream.fallback-to-api` 默认关闭：代理是唯一上报方，后端不持有 API Key。打开后，代理在 `downstream.ack-timeout-seconds` 内没有确认时，本机会自己直报 Admin（需要同时填好 `base-url`/`server-id`/`api-key`）；代理恢复期间两边可能重复上报，需要 Admin 端容忍。

### Build / 构建

```bash
mvn clean package
```

Put `target/yudream-minecraft-server-bukkit-1.1.0.jar` into the server `plugins` folder, start the server once, then edit:

将 `target/yudream-minecraft-server-bukkit-1.1.0.jar` 放到服务端 `plugins` 目录，启动一次后再编辑：

```yaml
base-url: "http://your-admin-host:8080"
server-id: "the-server-id-in-admin"
api-key: "yda_xxx"
```

### Report Logs / 上报日志

Report logs are controlled in `config.yml`:

上报日志在 `config.yml` 中控制：

```yaml
http:
  log-queued: false
  log-attempts: false
  log-success: true
  log-failures: true
  log-payload: false
```

- `log-queued`: logs when an event enters the async queue. / 事件进入异步队列时记录。
- `log-attempts`: logs every HTTP attempt and retry. / 记录每次 HTTP 尝试和重试。
- `log-success`: logs successful reports. / 记录成功上报。
- `log-failures`: logs final failures after retries. / 记录重试后的最终失败。
- `log-payload`: includes `playerId` and `eventAt`; API key is never logged. / 包含 `playerId` 和 `eventAt`；永远不会记录 API Key。

## Forge / NeoForge

Dedicated-server mods live in [`mods/`](mods/README.md). See that README for Gradle build, China mirrors, config file `config/yudream-minecraft-server.properties`, and log switches.

服务端模组在 [`mods/`](mods/README.md)。构建方式、国内镜像、配置文件 `config/yudream-minecraft-server.properties` 以及日志开关见该文档。

Outputs:

产物：

- `mods/forge-1.20.1/build/libs/yudream_minecraft_server-forge-1.20.1-1.1.0.jar`
- `mods/neoforge-1.21.1/build/libs/yudream_minecraft_server-neoforge-1.21.1-1.1.0.jar`

## Commands / 命令

- `/yudreammc mode [standalone|downstream]` switches mode, saves it to `config.yml`, and reconfigures everything. / 切换模式，写回 `config.yml` 并重新配置。
- `/yudreammc reload` reloads config. / 重新加载配置。
- `/yudreammc status` shows the mode and link state; in standalone mode it calls the remote players API. / 显示模式与链路状态；独立模式下调用远程玩家接口。
- `/yudreammc sync` reports join events for all currently online players, or asks the proxy to re-announce them in downstream mode. / 为当前在线玩家补报进服事件；下游模式下改为请求代理重新通报。
- `/yudreammc queue` shows pending report queue size. / 显示待上报队列长度。
