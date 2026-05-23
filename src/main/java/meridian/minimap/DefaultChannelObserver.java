package meridian.minimap;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.JoinWorld;
import meridian.protocol.packets.player.SetClientId;

/**
 * Captures the Default-channel client session (so {@link MinimapHud} has a
 * pipe to send {@code CustomHud} back to the client), plus mirrors the
 * player's yaw and current world UUID from observed traffic.
 *
 * <p>{@code CustomHud} ships on the Default channel, so a session captured
 * from any Default-channel packet (S2C {@code SetClientId} / {@code JoinWorld},
 * C2S {@code ClientMovement}) is the right target. We bind the session on
 * the first packet we see and keep it pinned.
 */
final class DefaultChannelObserver implements PacketHandler {

    private final MinimapHud hud;

    DefaultChannelObserver(MinimapHud hud) {
        this.hud = hud;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        // Only Default-channel packets bind the session — packets on Chunks /
        // WorldMap / etc. would put us on the wrong stream and our CustomHud
        // sends would die with "not valid on channel".
        if (packet instanceof SetClientId || packet instanceof JoinWorld) {
            hud.bindSession(session);
        }
        if (packet instanceof JoinWorld j) {
            hud.onJoinWorld(j.worldUuid);
        }
        return Action.FORWARD;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof ClientMovement m) {
            // ClientMovement also rides Default — capture the session for sends
            // even before the server has acknowledged us with SetClientId.
            hud.bindSession(session);
            if (m.lookOrientation != null) {
                hud.updateYaw(m.lookOrientation.yaw);
            }
        }
        return Action.FORWARD;
    }
}
