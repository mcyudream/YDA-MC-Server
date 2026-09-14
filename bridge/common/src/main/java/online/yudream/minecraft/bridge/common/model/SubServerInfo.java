package online.yudream.minecraft.bridge.common.model;

/**
 * One downstream server behind the proxy, as the bridge reports it.
 *
 * @param name          Velocity's server name, the same string the sensors report in their events
 * @param address       the backend address Velocity was configured with
 * @param online        players currently on that backend
 * @param sensor        whether a YuDream sensor has said hello on that backend
 * @param defaultServer whether Velocity falls back to this one
 */
public record SubServerInfo(
        String name,
        String address,
        int online,
        boolean sensor,
        boolean defaultServer
) {
}
