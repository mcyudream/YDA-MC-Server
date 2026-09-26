package online.yudream.minecraft.bukkit.event;

import online.yudream.minecraft.bukkit.YudreamMinecraftPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * 群服互联出站：捕获聊天与死亡事件并交给插件上报。
 *
 * <p>聊天只依赖全版本都有的 {@link AsyncPlayerChatEvent}（Paper 上它仍然触发），
 * 不再挂 Paper 的 AsyncChatEvent，否则新服务端上每条消息会被转发两次。
 *
 * <p>成就事件由 {@code AdvancementCompatibility} 按服务端能力以反射注册，不放在这里：
 * 该事件类在 1.8 服务端上不存在，直接注册会让整个监听器类加载失败。
 */
public final class GameEventListener implements Listener {

    private final YudreamMinecraftPlugin plugin;

    public GameEventListener(YudreamMinecraftPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getSettings().getChatBridge().isReportEvents()) {
            return;
        }
        final Player player = event.getPlayer();
        final String message = event.getMessage();
        // 聊天事件在异步线程触发；统一回主线程再入队，保持与活动信号一致的生命周期
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    plugin.handleGameChat(player, message);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getSettings().getChatBridge().isReportEvents()) {
            return;
        }
        String message = event.getDeathMessage();
        if (message == null || message.isEmpty()) {
            return;
        }
        plugin.handleGameDeath(event.getEntity(), message);
    }
}
