package online.yudream.minecraft.bridge.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import online.yudream.minecraft.bridge.velocity.YudreamVelocityPlugin;
import online.yudream.minecraft.bridge.velocity.config.VelocitySettings;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /yudreammc} on the proxy.
 *
 * <p>Every downstream server is reported as its own sub-server, so {@code target} is no longer a
 * reporting switch: it records which backend is the <b>default / login entry</b>. {@code status}
 * prints the per-sub-server reporting picture, which is where "why is this backend not showing up"
 * is answered.
 */
public final class YudreamCommand implements SimpleCommand {

    private static final List<String> SUBCOMMANDS = List.of("help", "reload", "status", "sync", "queue", "target", "topology");

    private final YudreamVelocityPlugin plugin;

    public YudreamCommand(YudreamVelocityPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        CommandSource source = invocation.source();
        if (source instanceof ConsoleCommandSource) {
            return true;
        }
        if (!(source instanceof Player player)) {
            return false;
        }
        VelocitySettings settings = plugin.settings();
        if (player.hasPermission(settings.commandPermission())) {
            return true;
        }
        for (String admin : settings.admins()) {
            if (admin.equalsIgnoreCase(player.getUsername()) || admin.equalsIgnoreCase(player.getUniqueId().toString())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase();

        switch (subcommand) {
            case "reload":
                plugin.reload();
                source.sendMessage(prefix().append(Component.text("Config reloaded.", NamedTextColor.GREEN)));
                return;
            case "status":
                status(source);
                return;
            case "sync":
                plugin.syncOnlinePlayers();
                source.sendMessage(prefix().append(Component.text("Queued join reports and a per-sub-server snapshot for every reportable sub-server.", NamedTextColor.GREEN)));
                return;
            case "queue":
                source.sendMessage(prefix().append(Component.text(
                        "Pending=" + plugin.queueSize()
                                + ", in flight or journaled=" + plugin.outstandingReports()
                                + ", reported online=" + plugin.reportedPlayerCount()
                                + ", afk-tracked=" + plugin.afkTrackedPlayers(), NamedTextColor.GRAY)));
                return;
            case "target":
                target(source, args);
                return;
            case "topology":
                topology(source);
                return;
            default:
                help(source, invocation.alias());
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        List<String> results = new ArrayList<String>();
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase();
            for (String subcommand : SUBCOMMANDS) {
                if (subcommand.startsWith(partial)) {
                    results.add(subcommand);
                }
            }
            return results;
        }
        if ("target".equalsIgnoreCase(args[0])) {
            String partial = args[1].toLowerCase();
            results.add("list");
            for (RegisteredServer registered : plugin.proxy().getAllServers()) {
                String name = registered.getServerInfo().getName();
                if (name.toLowerCase().startsWith(partial)) {
                    results.add(name);
                }
            }
        }
        return results;
    }

    private void target(CommandSource source, String[] args) {
        if (args.length < 2) {
            String current = plugin.settings().targetServer();
            source.sendMessage(prefix().append(Component.text(
                    "Default (login-entry) downstream server: " + (current.isEmpty() ? "<unset>" : current), NamedTextColor.GRAY)));
            source.sendMessage(prefix().append(Component.text(
                    "This is a marker only. Every downstream server is reported, each as its own sub-server.",
                    NamedTextColor.DARK_GRAY)));
            source.sendMessage(prefix().append(Component.text(
                    "Use /yudreammc target <server> to change it, or /yudreammc target list to see the options.", NamedTextColor.DARK_GRAY)));
            return;
        }
        if ("list".equalsIgnoreCase(args[1])) {
            List<String> servers = plugin.describeServers();
            if (servers.isEmpty()) {
                source.sendMessage(prefix().append(Component.text("Velocity knows no downstream servers.", NamedTextColor.RED)));
                return;
            }
            for (String line : servers) {
                source.sendMessage(prefix().append(Component.text("  " + line, NamedTextColor.GRAY)));
            }
            return;
        }
        String requested = args[1];
        if (plugin.proxy().getServer(requested).isEmpty()) {
            source.sendMessage(prefix().append(Component.text(
                    "No downstream server named '" + requested + "'. Try /yudreammc target list.", NamedTextColor.RED)));
            return;
        }
        String previous = plugin.setTarget(requested);
        String change = previous.isEmpty() ? "" : " (was '" + previous + "')";
        source.sendMessage(prefix().append(Component.text(
                "Default (login-entry) downstream server is now '" + requested + "'" + change + ". Saved to config.",
                NamedTextColor.GREEN)));
        source.sendMessage(prefix().append(Component.text(
                "Reporting is unchanged: every downstream server keeps being reported separately.",
                NamedTextColor.DARK_GRAY)));
    }

    private void status(CommandSource source) {
        VelocitySettings settings = plugin.settings();
        String target = settings.targetServer();
        source.sendMessage(prefix().append(Component.text("Velocity bridge " + YudreamVelocityPlugin.VERSION, NamedTextColor.AQUA)));
        source.sendMessage(line("Default downstream", target.isEmpty() ? "<unset> (marker only)" : target + " (marker only)"));
        source.sendMessage(line("Reporting", plugin.canReport() ? "active" : "blocked - " + blockedReason()));
        source.sendMessage(line("Reported players", Integer.toString(plugin.reportedPlayerCount())));
        source.sendMessage(line("Endpoint", settings.bridge().isConfigured()
                ? settings.bridge().getBaseUrl() + " / server-id=" + settings.bridge().getServerId()
                : "<not configured>"));
        source.sendMessage(line("Queue", plugin.queueSize() + " pending, " + plugin.outstandingReports() + " journaled"));
        source.sendMessage(line("Snapshots", "every " + settings.snapshotIntervalSeconds() + "s, one roster per sub-server"));
        source.sendMessage(line("Sensor gate", settings.requireSensor()
                ? "per sub-server (target.require-sensor=true)"
                : "off (target.require-sensor=false)"));
        List<String> servers = plugin.describeServers();
        if (servers.isEmpty()) {
            source.sendMessage(line("Sub-servers", "Velocity knows no downstream servers"));
        } else {
            source.sendMessage(prefix().append(Component.text("Sub-servers:", NamedTextColor.DARK_GRAY)));
            for (String description : servers) {
                source.sendMessage(prefix().append(Component.text("  " + description, NamedTextColor.GRAY)));
            }
        }
        source.sendMessage(line("Sensors", plugin.sensors().describe().isEmpty()
                ? "none have said hello yet"
                : String.join("; ", plugin.sensors().describe())));
        source.sendMessage(prefix().append(Component.text("Querying YuDream Admin...", NamedTextColor.DARK_GRAY)));
        plugin.requestRemoteStatus(message ->
                source.sendMessage(prefix().append(Component.text(message, NamedTextColor.GRAY))));
    }

    private String blockedReason() {
        VelocitySettings settings = plugin.settings();
        if (!settings.bridge().isEnabled()) {
            return "enabled=false";
        }
        if (!settings.bridge().isConfigured()) {
            return "api.base-url / api.server-id / api.api-key are incomplete";
        }
        if (settings.requireSensor()) {
            return sensorHint();
        }
        return "unknown";
    }

    private String sensorHint() {
        return "no downstream sensor has said hello yet, and target.require-sensor=true."
                + " Install yudream_minecraft_server-fabric on the backends, or set target.require-sensor=false.";
    }

    /**
     * Shows the downstream-server list Admin will receive and pushes it right away.
     *
     * <p>The output is also the answer to "why does Admin show no sub-servers": it prints the
     * addresses the report advertises, which are what Admin matches against.
     */
    private void topology(CommandSource source) {
        VelocitySettings settings = plugin.settings();
        List<String> addresses = plugin.advertisedAddresses();
        if (settings.bridge().isConfigured()) {
            source.sendMessage(line("Binding", "api.server-id=" + settings.bridge().getServerId()));
        } else if (settings.bridge().hasCredentials()) {
            source.sendMessage(line("Binding", "by address (no api.server-id set)"));
        } else {
            source.sendMessage(prefix().append(Component.text(
                    "YuDream Admin is not configured (api.base-url / api.api-key).", NamedTextColor.RED)));
            return;
        }
        source.sendMessage(line("Advertised addresses", addresses.isEmpty() ? "<none>" : String.join(", ", addresses)));
        source.sendMessage(line("Reporting", settings.topologyEnabled()
                ? "every " + settings.topologyIntervalSeconds() + "s"
                : "disabled (topology.enabled=false)"));
        List<String> lines = plugin.describeServers();
        if (lines.isEmpty()) {
            source.sendMessage(prefix().append(Component.text("Velocity knows no downstream servers.", NamedTextColor.RED)));
            return;
        }
        for (String description : lines) {
            source.sendMessage(prefix().append(Component.text("  " + description, NamedTextColor.GRAY)));
        }
        if (addresses.isEmpty()) {
            source.sendMessage(prefix().append(Component.text(
                    "No address to match on. Set topology.addresses to the address players connect to.",
                    NamedTextColor.YELLOW)));
        }
        plugin.reportTopology();
        source.sendMessage(prefix().append(Component.text("Topology report queued.", NamedTextColor.GREEN)));
    }

    private void help(CommandSource source, String alias) {
        source.sendMessage(prefix().append(Component.text("/" + alias + " target [server|list]", NamedTextColor.GRAY)));
        source.sendMessage(prefix().append(Component.text("/" + alias + " status", NamedTextColor.GRAY)));
        source.sendMessage(prefix().append(Component.text("/" + alias + " topology", NamedTextColor.GRAY)));
        source.sendMessage(prefix().append(Component.text("/" + alias + " reload", NamedTextColor.GRAY)));
        source.sendMessage(prefix().append(Component.text("/" + alias + " sync", NamedTextColor.GRAY)));
        source.sendMessage(prefix().append(Component.text("/" + alias + " queue", NamedTextColor.GRAY)));
    }

    private static Component line(String label, String value) {
        return prefix()
                .append(Component.text(label + ": ", NamedTextColor.DARK_GRAY))
                .append(Component.text(value, NamedTextColor.GRAY));
    }

    private static Component prefix() {
        return Component.text("[YuDream] ", NamedTextColor.AQUA);
    }
}
