# YuDream Minecraft Server Mods

This folder contains dedicated-server mods for the same YuDream Admin endpoints used by the Bukkit plugin.

Modules:

- `common`: loader-independent reporting, config, HTTP, retry queue and AFK state.
- `forge-1.20.1`: Forge mod for Minecraft `1.20.1`.
- `neoforge-1.21.1`: NeoForge mod for Minecraft `1.21.1`.

The mods report:

- `POST /api/plugins/minecraft-server/servers/{serverId}/players/join`
- `POST /api/plugins/minecraft-server/servers/{serverId}/players/quit`
- `POST /api/plugins/minecraft-server/servers/{serverId}/players/afk/start`
- `POST /api/plugins/minecraft-server/servers/{serverId}/players/afk/end`
- `POST /api/plugins/minecraft-server/servers/{serverId}/players/snapshot`
- `GET /api/plugins/minecraft-server/servers/{serverId}/players`

The bridge sends a complete online-player snapshot at startup, every 60 seconds, and during a normal shutdown so missed player events can be reconciled.

Authentication uses `X-API-Key`.

## Build

Use JDK 21 for the whole Gradle build. Forge 1.20.1 targets Java 17 bytecode, and NeoForge 1.21.1 targets Java 21.

```powershell
cd mods
gradle :forge-1.20.1:build :neoforge-1.21.1:build
```

If Gradle is not installed or access to `services.gradle.org` is unstable in China, use the bundled helper. It downloads Gradle from Tencent's mirror by default and uses the mirror repositories configured in `settings.gradle`:

```powershell
cd mods
.\scripts\gradle-cn.ps1 :forge-1.20.1:build :neoforge-1.21.1:build
```

To switch the Gradle distribution mirror:

```powershell
$env:GRADLE_DIST_MIRROR = "https://mirrors.huaweicloud.com/gradle"
.\scripts\gradle-cn.ps1 :forge-1.20.1:build
```

Repository URLs can also be overridden without editing Gradle files:

```powershell
$env:MAVEN_PUBLIC_MIRROR_URL = "https://maven.aliyun.com/repository/public"
$env:MAVEN_CENTRAL_MIRROR_URL = "https://maven.aliyun.com/repository/central"
$env:FORGE_MAVEN_URL = "https://maven.minecraftforge.net"
$env:NEOFORGE_MAVEN_URL = "https://maven.neoforged.net/releases"
$env:NEOFORGE_MOJANG_META_URL = "https://maven.neoforged.net/mojang-meta"
```

If Gradle repeatedly fails with `Connection reset` while resolving NeoForge artifacts, prefetch the hosted dependencies into Maven local first:

```powershell
cd mods
.\scripts\prefetch-neoforge-1.21.1.ps1
.\scripts\gradle-cn.ps1 :neoforge-1.21.1:build
```

Outputs:

- `mods/forge-1.20.1/build/libs/yudream_minecraft_server-forge-1.20.1-1.0.0.jar`
- `mods/neoforge-1.21.1/build/libs/yudream_minecraft_server-neoforge-1.21.1-1.0.0.jar`

## Config

On first server start, the mod creates:

```text
config/yudream-minecraft-server.properties
```

Set:

```properties
base-url=http://127.0.0.1:8080
server-id=
api-key=
```

The API key needs `plugin:minecraft-server:report`. The `/yudreammc status` command also needs `plugin:minecraft-server:manage`.

Useful log switches:

```properties
http.log-queued=false
http.log-attempts=false
http.log-success=true
http.log-failures=true
http.log-payload=false
```

### Group-server bridge / 群服互联

The mods also join the group-server bridge: they report chat, death and advancement events, and poll
the Admin queue to broadcast group-chat messages into the game. What gets forwarded and to which QQ
group is configured per server in YuDream Admin (edit-server page → 群服互联); these local switches
only decide whether this server participates:

模组同样接入群服互联：上报聊天、死亡与成就事件，并轮询 Admin 队列把群消息广播进游戏。
转发哪些消息、发到哪个群在 YuDream Admin 的编辑服务器页逐服务器配置；以下本地开关只决定本机是否参与：

```properties
chat-bridge.enabled=true
chat-bridge.report-events=true
chat-bridge.poll-inbound=true
chat-bridge.poll-interval-seconds=3
chat-bridge.inbound-format=\u00a78[\u00a7a群\u00a78] \u00a7f{sender} \u00a77» \u00a7f{content}
```

Advancement forwarding works on Forge and NeoForge. On Fabric it is unavailable because Fabric API
has no advancement event; chat and death are covered.

成就转发在 Forge / NeoForge 上可用；Fabric API 没有成就事件，Fabric 传感器不支持成就转发，聊天与死亡照常。

## Commands

- `/yudreammc reload`
- `/yudreammc status`
- `/yudreammc sync`
- `/yudreammc queue`
