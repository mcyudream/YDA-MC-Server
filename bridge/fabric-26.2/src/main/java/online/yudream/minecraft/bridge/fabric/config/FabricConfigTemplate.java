package online.yudream.minecraft.bridge.fabric.config;

import java.util.List;

/** The commented sensor config written on first start. */
public final class FabricConfigTemplate {

    private static final List<String> LINES = List.of(
            "# YuDream Minecraft bridge - backend sensor.",
            "#",
            "# This mod does not talk to YuDream Admin and holds no API key. It watches player activity",
            "# on this server and forwards it to the Velocity proxy over the yudream:bridge plugin",
            "# message channel. Configure the endpoint, the API key and which downstream server gets",
            "# reported in the proxy plugin's own config.properties.",
            "",
            "enabled=true",
            "",
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
