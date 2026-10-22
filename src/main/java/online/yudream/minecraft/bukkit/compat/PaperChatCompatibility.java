package online.yudream.minecraft.bukkit.compat;

import online.yudream.minecraft.bukkit.YudreamMinecraftPlugin;
import online.yudream.minecraft.bukkit.bridge.BridgeProtocol;
import online.yudream.minecraft.bukkit.config.YudreamConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Modern chat event compatibility for Paper 1.19+ and Folia.
 *
 * <p>The legacy {@link org.bukkit.event.player.AsyncPlayerChatEvent} still fires on Folia, but the
 * modern {@code AsyncChatEvent} is the dependable one on Paper/Folia and it carries the final message
 * as an Adventure component. This handler therefore covers both jobs the bridge needs from chat:
 * resetting the AFK timer and forwarding the line to the group-server bridge.
 *
 * <p>The plugin is compiled against Spigot 1.12.2 and still runs on 1.8.8, so the event, the player
 * accessor and the plain-text serializer are all resolved reflectively. When the event class does not
 * exist the registration simply returns false and the legacy annotated listener stays in charge.
 */
public final class PaperChatCompatibility {

    private static final String PAPER_ASYNC_CHAT_EVENT = "io.papermc.paper.event.player.AsyncChatEvent";
    private static final String ADVENTURE_COMPONENT = "net.kyori.adventure.text.Component";
    private static final String PLAIN_TEXT_SERIALIZER =
            "net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer";

    private static volatile boolean active;
    private static Method playerMethod;
    private static Method messageMethod;
    private static Object plainSerializer;
    private static Method serializeMethod;

    private PaperChatCompatibility() {
    }

    /** Whether the modern event was registered; the legacy chat handlers must stay silent then. */
    public static boolean isActive() {
        return active;
    }

    public static boolean register(final YudreamMinecraftPlugin plugin) {
        final Class<? extends Event> eventClass;
        try {
            eventClass = Class.forName(PAPER_ASYNC_CHAT_EVENT).asSubclass(Event.class);
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Could not inspect Paper chat event compatibility", t);
            return false;
        }

        try {
            playerMethod = eventClass.getMethod("getPlayer");
            messageMethod = eventClass.getMethod("message");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "AsyncChatEvent exists but its accessors are not resolvable", t);
            return false;
        }
        resolvePlainTextSerializer(plugin);

        Listener listener = new Listener() {
        };
        EventExecutor executor = new EventExecutor() {
            @Override
            public void execute(Listener listener, Event event) throws EventException {
                if (event instanceof Cancellable && ((Cancellable) event).isCancelled()) {
                    return;
                }
                YudreamConfig settings = plugin.getSettings();
                if (settings == null) {
                    return;
                }
                final boolean activity = settings.isResetOnChat();
                final boolean report = settings.getChatBridge().isEnabled()
                        && settings.getChatBridge().isReportEvents();
                if (!activity && !report) {
                    return;
                }
                final Player player = player(event);
                if (player == null) {
                    return;
                }
                final String message = report ? plainText(event) : null;
                // Chat fires off the owning thread. On Folia runFor() hops to the player's region
                // thread; on Bukkit/Paper it hops to the main thread.
                plugin.getSchedulerCompat().runFor(player, new Runnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (activity) {
                            plugin.handleActivity(player, BridgeProtocol.SOURCE_CHAT);
                        }
                        if (report && message != null && !message.trim().isEmpty()) {
                            plugin.handleGameChat(player, message);
                        }
                    }
                });
            }
        };
        try {
            plugin.getServer().getPluginManager().registerEvent(eventClass, listener, EventPriority.MONITOR,
                    executor, plugin, true);
            active = true;
            return true;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Could not register AsyncChatEvent compatibility", t);
            return false;
        }
    }

    private static void resolvePlainTextSerializer(YudreamMinecraftPlugin plugin) {
        try {
            Class<?> componentClass = Class.forName(ADVENTURE_COMPONENT);
            Class<?> serializerClass = Class.forName(PLAIN_TEXT_SERIALIZER);
            plainSerializer = serializerClass.getMethod("plainText").invoke(null);
            serializeMethod = serializerClass.getMethod("serialize", componentClass);
        } catch (Throwable t) {
            // Chat forwarding still works for AFK; only the text extraction is unavailable.
            plugin.getLogger().log(Level.FINE, "Adventure plain-text serializer is unavailable", t);
        }
    }

    private static Player player(Event event) {
        try {
            Object player = playerMethod.invoke(event);
            return player instanceof Player ? (Player) player : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String plainText(Event event) {
        try {
            Object component = messageMethod.invoke(event);
            if (component == null) {
                return null;
            }
            if (plainSerializer != null && serializeMethod != null) {
                return (String) serializeMethod.invoke(plainSerializer, component);
            }
            return String.valueOf(component);
        } catch (Throwable t) {
            return null;
        }
    }
}
