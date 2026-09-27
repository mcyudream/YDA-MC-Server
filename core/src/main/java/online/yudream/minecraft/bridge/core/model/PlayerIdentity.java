package online.yudream.minecraft.bridge.core.model;

import online.yudream.minecraft.bridge.core.model.PlayerEventPayload;

import java.util.Objects;
import java.util.UUID;

/** A player as seen by the proxy or by a backend sensor: UUID plus last known name. */
public final class PlayerIdentity {

    private final UUID uuid;
    private final String name;

    public PlayerIdentity(UUID uuid, String name) {
        Objects.requireNonNull(uuid, "uuid");
        this.uuid = uuid;
        this.name = name == null ? "" : name;
    }

    public PlayerEventPayload payload(long eventAt) {
        return PlayerEventPayload.of(this, eventAt, null);
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public String uuidString() {
        return uuid.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlayerIdentity)) {
            return false;
        }
        PlayerIdentity other = (PlayerIdentity) o;
        return uuid.equals(other.uuid) && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return 31 * uuid.hashCode() + name.hashCode();
    }

    @Override
    public String toString() {
        return "PlayerIdentity[uuid=" + uuid + ", name=" + name + "]";
    }
}
