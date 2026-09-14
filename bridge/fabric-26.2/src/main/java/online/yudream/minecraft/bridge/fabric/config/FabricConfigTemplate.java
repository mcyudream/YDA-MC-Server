package online.yudream.minecraft.bridge.fabric.config;

import java.util.List;

/** The commented bridge config written on first start. */
public final class FabricConfigTemplate {

    private static final List<String> LINES = List.of(
            "# YuDream Minecraft bridge - Fabric server.",
            "#",
            "# mode decides who uploads player activity to YuDream Admin:",
            "#   downstream - this server sits behind a Velocity proxy. The mod becomes a sensor: it",
            "#                watches local players and forwards what it sees to the proxy over the",
            "#                yudream:bridge channel. It uploads nothing itself and needs no API key.",
            "#   standalone - there is no proxy. The mod reports to Admin directly, so base-url,",
            "#                server-id and api-key below must all be filled in.",
            "#",
            "# downstream is the default because that is what this mod has always done; a backend behind",
            "# a proxy must not upload in parallel with the proxy or its players get counted twice.",
            "mode=downstream",
            "",
            "enabled=true",
            "",
            "# ------------------------------------------------------------------ standalone only",
            "# Everything below is read in both modes so the file can be switched by editing mode alone,",
            "# but only standalone mode ever uses it.",
            "api.base-url=http://127.0.0.1:8080",
            "# The YuDream Admin server entry this server reports as. Copy it from the server's page in",
            "# Admin; without it the report is rejected because Admin cannot attribute it to a server.",
            "api.server-id=",
            "# The plugin API key. Leave empty in downstream mode: the proxy holds its own credentials.",
            "api.api-key=",
            "# Whether the flat snapshot repeats the server name. Standalone reports have no sub-server",
            "# dimension, so this is normally false and the data lands in Admin's default bucket.",
            "api.include-server-name-in-snapshot=false",
            "",
            "# How often the full roster is re-reported, so Admin can reconcile joins and quits that were",
            "# missed while this server was unreachable.",
            "snapshot.interval-seconds=60",
            "",
            "# AFK detection. A player who sends no activity for afk.timeout-seconds is reported AFK.",
            "afk.enabled=true",
            "afk.timeout-seconds=300",
            "afk.check-interval-seconds=30",
            "",
            "# Re-announce everyone already online when the server starts, instead of waiting for the",
            "# next join or snapshot.",
            "startup.sync-online-on-enable=true",
            "# Report a quit for everyone online when the server stops. Off by default: plugins are not",
            "# guaranteed a clean shutdown, and a stale online flag is repaired by the next snapshot.",
            "shutdown.report-quit-on-disable=false",
            "shutdown.flush-timeout-ms=5000",
            "",
            "http.connect-timeout-ms=5000",
            "http.read-timeout-ms=8000",
            "http.retry-attempts=3",
            "http.retry-delay-ms=1500",
            "http.queue-capacity=1000",
            "# Persist undelivered reports to disk so a restart does not lose them.",
            "http.persist-queue=true",
            "http.queue-file=report-queue.jsonl",
            "http.log-queued=false",
            "http.log-attempts=false",
            "http.log-success=true",
            "http.log-failures=true",
            "http.log-payload=false",
            "",
            "# ------------------------------------------------------------------ downstream only",
            "# How often the sensor re-announces itself to the proxy while players are online. The proxy",
            "# needs this to survive a restart while players were already connected to this backend.",
            "heartbeat-seconds=20",
            "",
            "# Which local signals count as player activity. Activity only drives AFK start/end, so",
            "# turning one off makes players look idle sooner.",
            "activity.chat=true",
            "activity.move=true",
            "activity.interact=true",
            "activity.command=true",
            "",
            "# How far a player must move before it counts as activity, in blocks.",
            "activity.move-min-blocks=1.0",
            "",
            "# Shortest gap between two forwarded activity signals for the same player. The proxy only",
            "# uses these to reset an AFK timer measured in minutes, so a small value is packet spam.",
            "activity.min-interval-seconds=10",
            "",
            "# Logs each forwarded activity signal.",
            "log.debug=false"
    );

    private FabricConfigTemplate() {
    }

    public static String render() {
        StringBuilder out = new StringBuilder();
        for (String line : LINES) {
            out.append(line).append(System.lineSeparator());
        }
        return out.toString();
    }
}
