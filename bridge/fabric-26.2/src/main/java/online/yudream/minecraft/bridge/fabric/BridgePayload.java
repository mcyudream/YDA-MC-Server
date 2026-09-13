package online.yudream.minecraft.bridge.fabric;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import online.yudream.minecraft.bridge.common.protocol.BridgeProtocol;

/**
 * Carries one UTF-8 JSON bridge message over the custom {@code yudream:bridge} channel.
 *
 * <p>The payload is opaque to Minecraft: the bridge keeps its own JSON envelope so the protocol can
 * grow without a new payload class, and so a proxy-side dump stays readable.
 *
 * <p><b>The codec must not add framing.</b> Velocity hands the proxy plugin the raw payload bytes and
 * the Bukkit sensor sends raw bytes through {@code sendPluginMessage}, so anything the codec adds —
 * a length prefix, a discriminant — makes the two sensors disagree and the proxy rejects the message
 * as malformed. Writing the bytes directly is what keeps Fabric and Bukkit byte-compatible.
 */
public record BridgePayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BridgePayload> TYPE = new CustomPacketPayload.Type<BridgePayload>(
            Identifier.fromNamespaceAndPath(BridgeProtocol.CHANNEL_NAMESPACE, BridgeProtocol.CHANNEL_PATH));

    public static final StreamCodec<RegistryFriendlyByteBuf, BridgePayload> CODEC =
            CustomPacketPayload.codec(BridgePayload::write, BridgePayload::new);

    public BridgePayload(RegistryFriendlyByteBuf buffer) {
        this(readRemaining(buffer));
    }

    private static byte[] readRemaining(RegistryFriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBytes(data);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
