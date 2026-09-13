package online.yudream.minecraft.bukkit.event;

import online.yudream.minecraft.bukkit.YudreamMinecraftPlugin;
import online.yudream.minecraft.bukkit.bridge.BridgeProtocol;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;

/**
 * Detects player activity and hands it to the plugin.
 *
 * <p>The listener is deliberately mode-agnostic: it only reports that something happened. Whether that
 * becomes a local AFK timer reset or a forwarded bridge message is the plugin's decision.
 */
public final class PlayerActivityListener implements Listener {

    private final YudreamMinecraftPlugin plugin;

    public PlayerActivityListener(YudreamMinecraftPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (plugin.getSettings().isResetOnMove() && movedBlock(event.getFrom(), event.getTo())) {
            plugin.handleActivity(event.getPlayer(), BridgeProtocol.SOURCE_MOVE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(final AsyncPlayerChatEvent event) {
        if (!plugin.getSettings().isResetOnChat()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                plugin.handleActivity(event.getPlayer(), BridgeProtocol.SOURCE_CHAT);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (plugin.getSettings().isResetOnCommand()) {
            plugin.handleActivity(event.getPlayer(), BridgeProtocol.SOURCE_COMMAND);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (plugin.getSettings().isResetOnInteract()) {
            plugin.handleActivity(event.getPlayer(), BridgeProtocol.SOURCE_INTERACT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        plugin.handleActivity(event.getPlayer(), BridgeProtocol.SOURCE_INTERACT);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) {
        plugin.handleActivity(event.getPlayer(), BridgeProtocol.SOURCE_INTERACT);
    }

    private static boolean movedBlock(Location from, Location to) {
        return to != null
                && (from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ());
    }
}
