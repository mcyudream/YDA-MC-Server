package online.yudream.minecraft.bukkit.compat;

import online.yudream.minecraft.bukkit.YudreamMinecraftPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * 成就事件兼容：成就相关 API 在 1.12.2 编译 API 里不完整（{@code Advancement.getDisplay()} 缺失），
 * 且事件类在 1.8 服务端上不存在，因此整条链路走反射并按需注册。
 *
 * <p>与 PaperChatCompatibility 相同的套路：事件类存在才注册，运行期逐个解析方法，
 * 缺哪个就静默放弃成就转发，聊天与死亡转发不受影响。
 */
public final class AdvancementCompatibility {

    private static final String ADVANCEMENT_DONE_EVENT = "org.bukkit.event.player.PlayerAdvancementDoneEvent";

    private AdvancementCompatibility() {
    }

    public static boolean register(final YudreamMinecraftPlugin plugin) {
        final Class<? extends Event> eventClass;
        try {
            eventClass = Class.forName(ADVANCEMENT_DONE_EVENT).asSubclass(Event.class);
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Could not inspect advancement event compatibility", t);
            return false;
        }

        Listener listener = new Listener() {
        };
        EventExecutor executor = new EventExecutor() {
            @Override
            public void execute(Listener listener, Event event) throws EventException {
                if (!plugin.getSettings().getChatBridge().isReportEvents()) {
                    return;
                }
                final Player player = player(event);
                final String title = displayTitle(event);
                if (player == null || title == null || title.isEmpty()) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (player.isOnline()) {
                            plugin.handleGameAdvancement(player, title);
                        }
                    }
                });
            }
        };
        try {
            plugin.getServer().getPluginManager().registerEvent(eventClass, listener, EventPriority.MONITOR, executor, plugin, true);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Could not register advancement event compatibility", t);
            return false;
        }
    }

    private static Player player(Event event) {
        try {
            Object player = event.getClass().getMethod("getPlayer").invoke(event);
            return player instanceof Player ? (Player) player : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 反射读取 {@code getAdvancement().getDisplay()} 的标题；仅当该成就会向聊天公告时返回标题，
     * 其余（隐藏/后台进度、读取失败）一律返回 {@code null}。
     */
    private static String displayTitle(Event event) {
        try {
            Object advancement = event.getClass().getMethod("getAdvancement").invoke(event);
            if (advancement == null) {
                return null;
            }
            Method getDisplay = advancement.getClass().getMethod("getDisplay");
            Object display = getDisplay.invoke(advancement);
            if (display == null) {
                return null;
            }
            Object announce = display.getClass().getMethod("doesAnnounceToChat").invoke(display);
            if (!(announce instanceof Boolean) || !((Boolean) announce)) {
                return null;
            }
            Object title = display.getClass().getMethod("getTitle").invoke(display);
            return title instanceof String ? (String) title : null;
        } catch (Exception e) {
            return null;
        }
    }
}
