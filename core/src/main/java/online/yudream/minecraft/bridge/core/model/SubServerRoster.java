package online.yudream.minecraft.bridge.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个子服在快照里的名册。
 *
 * <p>空名册是有意义的：它表示“这个子服上现在没有人”，YuDream Admin 会据此关闭它认为还留在该
 * 子服上的玩家，而不会误伤其它子服。因此上报时必须把有确认传感器的空子服也列出来。
 *
 * <p>名字为空表示没有子服维度（独立服/旧版形态），Admin 侧会归入 {@code default} 桶。
 */
public final class SubServerRoster {

    private final String name;
    private final List<PlayerEventPayload> players;

    public SubServerRoster(String name, List<PlayerEventPayload> players) {
        this.name = name;
        this.players = players == null
                ? Collections.<PlayerEventPayload>emptyList()
                : Collections.unmodifiableList(new ArrayList<PlayerEventPayload>(players));
    }

    public String name() {
        return name;
    }

    public List<PlayerEventPayload> players() {
        return players;
    }

    public static SubServerRoster empty(String name) {
        return new SubServerRoster(name, Collections.<PlayerEventPayload>emptyList());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SubServerRoster)) {
            return false;
        }
        SubServerRoster other = (SubServerRoster) o;
        return (name == null ? other.name == null : name.equals(other.name))
                && players.equals(other.players);
    }

    @Override
    public int hashCode() {
        int result = name == null ? 0 : name.hashCode();
        result = 31 * result + players.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "SubServerRoster[name=" + name + ", players=" + players + "]";
    }
}
