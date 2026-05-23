package meridian.minimap;

import java.time.Duration;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.packet.Direction;
import meridian.api.packet.HandlerPosition;
import meridian.core.api.EntityTracker;
import org.slf4j.Logger;

/**
 * meridian-minimap — in-game minimap HUD for the Meridian proxy.
 *
 * <p>A Jedi (Layer-1) module: it observes raw {@code UpdateWorldMap},
 * {@code ClientMovement} and {@code JoinWorld}, then pushes {@code CustomHud}
 * packets carrying a {@code N×N} grid of coloured {@code Panel} elements to
 * the client. The technique — colour cells, mutate cell backgrounds via
 * {@code Set} selector commands per tick — is borrowed from Landscaper's
 * SimpleMinimap server plugin; we just sit on the wire instead of behind a
 * server's API.
 *
 * <p>The module is permanently on while loaded — no settings UI yet. Future:
 * a SettingsSpec page for radius / cell size / toggles, plus a
 * {@code CustomUIEventBinding.KeyDown} for toggling visibility from a hotkey.
 */
public class MinimapModule implements ProxyModule {

    @Override
    public void onEnable(ModuleContext ctx) {
        Logger log = ctx.getLogger();
        EntityTracker entities = ctx.services().require(EntityTracker.class);

        TileCache cache = new TileCache();
        MinimapHud hud = new MinimapHud(log, entities, cache);

        // S2C WorldMap channel — captures UpdateWorldMap tiles into the cache.
        // MONITOR so we don't disturb the in-game world map's own pipeline.
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new WorldMapObserver(cache));

        // BOTH Default-channel — capture the session (S2C) and yaw (C2S).
        ctx.registerHandler(Direction.BOTH, HandlerPosition.MONITOR,
                (direction, session) -> new DefaultChannelObserver(hud));

        // Tick the HUD at the same cadence as Landscaper's default (500 ms is
        // a good trade-off: the player rarely moves more than one cell per
        // half-second at walk speed, and 2 Hz keeps coordinate / arrow
        // updates feeling live without flooding the client).
        ctx.scheduler().scheduleAtFixedRate(hud::tick,
                Duration.ofMillis(MinimapHud.TICK_MS),
                Duration.ofMillis(MinimapHud.TICK_MS));

        log.info("meridian-minimap enabled — 44×44 minimap HUD, 500 ms tick");
    }
}
