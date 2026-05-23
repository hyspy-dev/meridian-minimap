package meridian.minimap;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.worldmap.UpdateWorldMap;

/**
 * S2C MONITOR observer on the WorldMap channel — every received
 * {@code UpdateWorldMap} feeds its tile array into {@link TileCache}.
 *
 * <p>MONITOR position: we do not mutate or drop, only read. The downstream
 * client-bound forward is untouched and the in-game world map keeps working
 * normally alongside the minimap.
 */
final class WorldMapObserver implements PacketHandler {

    private final TileCache cache;

    WorldMapObserver(TileCache cache) {
        this.cache = cache;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof UpdateWorldMap m) {
            cache.put(m.chunks);
        }
        return Action.FORWARD;
    }
}
