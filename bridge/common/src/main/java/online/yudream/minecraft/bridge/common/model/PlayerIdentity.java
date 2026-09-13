package online.yudream.minecraft.bridge.common.model;

import java.util.Objects;
import java.util.UUID;

/** A player as seen by the proxy or by a backend sensor: UUID plus last known name. */
public record PlayerIdentity(UUID uuid, String name) {

    public PlayerIdentity {
        Objects.requireNonNull(uuid, "uuid");
        name = name == null ? "" : name;
    }

    public String uuidString() {
        return uuid.toString();
    }
}
