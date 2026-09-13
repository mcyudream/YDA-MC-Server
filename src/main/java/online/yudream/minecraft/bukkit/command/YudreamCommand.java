package online.yudream.minecraft.bukkit.command;

import online.yudream.minecraft.bukkit.YudreamMinecraftPlugin;
import online.yudream.minecraft.bukkit.api.HttpResult;
import online.yudream.minecraft.bukkit.bridge.BridgeMode;
import online.yudream.minecraft.bukkit.bridge.DownstreamSensor;
import online.yudream.minecraft.bukkit.config.YudreamConfig;
import online.yudream.minecraft.bukkit.util.PlayerSummaryParser;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class YudreamCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "status", "sync", "queue", "mode", "help");

    private final YudreamMinecraftPlugin plugin;

    public YudreamCommand(YudreamMinecraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase();
        if ("reload".equals(subcommand)) {
            plugin.reloadBridge();
            sender.sendMessage(prefix() + "Config reloaded.");
            return true;
        }
        if ("status".equals(subcommand)) {
            status(sender);
            return true;
        }
        if ("sync".equals(subcommand)) {
            plugin.syncOnlinePlayers();
            sender.sendMessage(prefix() + (plugin.isDownstreamMode()
                    ? "Asked the proxy to re-announce the players on this backend."
                    : "Queued join reports for current online players."));
            return true;
        }
        if ("queue".equals(subcommand)) {
            sender.sendMessage(prefix() + "Pending reports: " + plugin.getReportQueue().size());
            return true;
        }
        if ("mode".equals(subcommand)) {
            mode(sender, label, args);
            return true;
        }
        help(sender, label);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return matching(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && "mode".equalsIgnoreCase(args[0])) {
            return matching(Arrays.asList("standalone", "downstream"), args[1]);
        }
        return new ArrayList<String>();
    }

    private static List<String> matching(List<String> candidates, String partial) {
        String prefix = partial == null ? "" : partial.toLowerCase();
        List<String> results = new ArrayList<String>();
        for (String candidate : candidates) {
            if (candidate.startsWith(prefix)) {
                results.add(candidate);
            }
        }
        return results;
    }

    private void mode(CommandSender sender, String label, String[] args) {
        YudreamConfig settings = plugin.getSettings();
        if (args.length < 2) {
            sender.sendMessage(prefix() + "Mode: " + ChatColor.WHITE + settings.getDownstream().getMode().getId());
            sender.sendMessage(prefix() + ChatColor.GRAY + "/" + label + " mode standalone|downstream");
            return;
        }
        String raw = args[1].toLowerCase();
        BridgeMode requested;
        if ("standalone".equals(raw)) {
            requested = BridgeMode.STANDALONE;
        } else if ("downstream".equals(raw)) {
            requested = BridgeMode.DOWNSTREAM;
        } else {
            sender.sendMessage(prefix() + ChatColor.RED + "Unknown mode '" + args[1] + "'. Use standalone or downstream.");
            return;
        }
        if (!plugin.setMode(requested)) {
            sender.sendMessage(prefix() + ChatColor.RED + "Could not write the mode to config.yml; see the console log.");
            return;
        }
        sender.sendMessage(prefix() + "Mode set to " + ChatColor.WHITE + requested.getId() + ChatColor.RESET + " and saved to config.yml.");
        if (requested.isDownstream()) {
            sender.sendMessage(prefix() + ChatColor.GRAY
                    + "This server now forwards player activity to the proxy and does not contact YuDream Admin directly"
                    + (plugin.getSettings().getDownstream().isFallbackToApi() ? " unless the proxy stays silent." : "."));
        } else {
            sender.sendMessage(prefix() + ChatColor.GRAY + "This server now reports to YuDream Admin itself.");
        }
    }

    private void status(final CommandSender sender) {
        YudreamConfig settings = plugin.getSettings();
        sender.sendMessage(prefix() + "Mode: " + ChatColor.WHITE + settings.getDownstream().getMode().getId()
                + ChatColor.RESET + " (" + (settings.isEnabled() ? "enabled" : "disabled") + ")");

        if (settings.isDownstream()) {
            DownstreamSensor sensor = plugin.getSensor();
            sender.sendMessage(prefix() + "Proxy link: " + ChatColor.GRAY
                    + (sensor == null ? "sensor not started" : sensor.describeLink()));
            sender.sendMessage(prefix() + "Local reporting: " + ChatColor.GRAY + localReportingState(settings));
            sender.sendMessage(prefix() + "Queue: " + plugin.getReportQueue().size());
            return;
        }

        if (!plugin.canReport()) {
            sender.sendMessage(prefix() + ChatColor.RED + "Not configured or disabled.");
            return;
        }
        sender.sendMessage(prefix() + "Checking remote players...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                String computedMessage;
                try {
                    HttpResult result = plugin.getApiClient().players(1, 100);
                    if (result.isSuccess()) {
                        PlayerSummaryParser.Summary summary = PlayerSummaryParser.parse(result.getBody());
                        computedMessage = prefix() + "Remote players: total=" + summary.getTotal()
                                + ", online=" + summary.getOnline()
                                + ", afk=" + summary.getAfk()
                                + ", http=" + result.getStatusCode();
                    } else {
                        computedMessage = prefix() + ChatColor.RED + "Remote status failed: HTTP "
                                + result.getStatusCode() + " " + trim(result.getBody());
                    }
                } catch (Exception e) {
                    computedMessage = prefix() + ChatColor.RED + "Remote status failed: " + e.getMessage();
                }
                final String message = computedMessage;
                plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        sender.sendMessage(message);
                    }
                });
            }
        });
    }

    private String localReportingState(YudreamConfig settings) {
        if (!settings.getDownstream().isFallbackToApi()) {
            return "disabled (the proxy is the only uploader)";
        }
        return plugin.canReport() ? "active, proxy unreachable" : "armed, proxy reachable";
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(prefix() + "/" + label + " mode [standalone|downstream]");
        sender.sendMessage(prefix() + "/" + label + " reload");
        sender.sendMessage(prefix() + "/" + label + " status");
        sender.sendMessage(prefix() + "/" + label + " sync");
        sender.sendMessage(prefix() + "/" + label + " queue");
    }

    private static String prefix() {
        return ChatColor.AQUA + "[YuDream] " + ChatColor.RESET;
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 160 ? body : body.substring(0, 160) + "...";
    }
}
