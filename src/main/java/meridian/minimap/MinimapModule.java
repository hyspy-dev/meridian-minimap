package meridian.minimap;

import java.time.Duration;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.ClientAssets;
import meridian.core.api.Hud;
import meridian.core.api.World;
import meridian.core.api.WorldMap;

/**
 * meridian-minimap — a minimap in the corner of the screen.
 *
 * <p>The module reads no packets and writes none. The world it draws comes from
 * {@link WorldMap}, the player from {@link World}, the pictures go out through {@link ClientAssets}
 * and the drawing through {@link Hud} — so it is written against what the game means rather than
 * what it says on the wire, and it keeps working when the wire changes.
 */
public class MinimapModule implements ProxyModule {

    @Override
    public void onEnable(ModuleContext ctx) {
        MinimapHud hud = new MinimapHud(
                ctx.getLogger(),
                ctx.services().require(World.class),
                ctx.services().require(WorldMap.class),
                ctx.services().require(Hud.class),
                ctx.services().require(ClientAssets.class));

        // Twice a second. A player walking crosses far less than one tile in that time, so the
        // map keeps up, while the client is left alone the rest of the time.
        ctx.scheduler().scheduleAtFixedRate(hud::tick,
                Duration.ofMillis(MinimapHud.TICK_MS),
                Duration.ofMillis(MinimapHud.TICK_MS));

        ctx.registerSettings(SettingsSpec.builder()
                .enum_("position", "Position on screen",
                        MinimapHud.Corner.class, MinimapHud.Corner.TOP_RIGHT,
                        hud::setPosition)
                .enum_("zoom", "Zoom level",
                        MinimapHud.Zoom.class, MinimapHud.Zoom.NORMAL,
                        hud::setZoom)
                .bool("showCoords", "Show coordinates", true, hud::setShowCoords)
                .bool("showCompass", "Show compass labels (N/S/W/E)", true, hud::setShowCompass)
                .bool("showMarker", "Show player marker (center dot)", true, hud::setShowMarker)
                .persistent("position", "zoom", "showCoords", "showCompass", "showMarker")
                .build());

        ctx.getLogger().info("meridian-minimap enabled");
    }
}
