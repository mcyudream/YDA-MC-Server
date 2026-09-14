package online.yudream.minecraft.bridge.common.model;

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
public record SubServerRoster(String name, List<PlayerEventPayload> players) {

    public SubServerRoster {
        players = players == null
                ? Collections.<PlayerEventPayload>emptyList()
                : Collections.unmodifiableList(new ArrayList<PlayerEventPayload>(players));
    }

    public static SubServerRoster empty(String name) {
        return new SubServerRoster(name, Collections.<PlayerEventPayload>emptyList());
    }
}
