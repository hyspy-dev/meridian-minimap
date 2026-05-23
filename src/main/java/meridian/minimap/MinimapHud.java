package meridian.minimap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import meridian.api.session.ProxySession;
import meridian.core.api.EntityTracker;
import meridian.core.api.Vec3;
import meridian.protocol.packets.interface_.CustomHud;
import meridian.protocol.packets.interface_.CustomUICommand;
import meridian.protocol.packets.interface_.CustomUICommandType;
import meridian.protocol.packets.setup.RequestCommonAssetsRebuild;
import org.slf4j.Logger;

/**
 * The in-game minimap HUD — a {@code CustomHud} backed by a single dynamic
 * texture instead of a per-cell Panel grid. Each tick:
 *
 * <ol>
 *   <li>{@link MinimapAsset#regenerate} renders a PNG from the {@link TileCache}
 *       (fed by observed {@code UpdateWorldMap} packets) and pushes it as a
 *       new client asset under a sequence-suffixed name.</li>
 *   <li>The HUD's {@code #MinimapTexture.Background} is {@code Set} to the
 *       new asset reference; the client swaps in the fresh texture.</li>
 *   <li>The previous tick's asset is removed to keep client memory stable.</li>
 * </ol>
 *
 * <p>Static overlays — center marker, compass labels, directional arrow,
 * coordinates label — live in inline NOML markup pushed once at session
 * bind time.
 */
final class MinimapHud {

    // -- Layout ---------------------------------------------------------
    /** On-screen size of the minimap, pixels. Derived from the tile grid. */
    private static final int DISPLAY_SIZE = MinimapAsset.DISPLAY_SIZE;
    private static final int TILE_PX = MinimapAsset.TILE_PX;
    private static final int GRID_SIDE = MinimapAsset.GRID_SIDE;

    // -- Behaviour ------------------------------------------------------
    static final long TICK_MS = 500;
    private static final String HUD_ID = "meridian-minimap";
    private static final int Z_ORDER = 50;
    /** Name the .ui asset is pushed under (no @2x — UI docs don't use it). */
    private static final String TEXTURE_UI_NAME = "meridian-minimap-texture.ui";
    /** Reference path used in {@code .append("...")}. */
    private static final String TEXTURE_UI_REF = "meridian-minimap-texture.ui";

    // -- Wire ---------------------------------------------------------
    private final Logger log;
    private final EntityTracker entities;
    private final TileCache cache;
    private final MinimapAsset minimapAsset;

    private volatile ProxySession session;
    private volatile UUID currentWorld;
    private volatile float yaw;
    private volatile boolean haveYaw;

    // -- Render state ---------------------------------------------------
    private int lastArrowDir = -1;
    private String lastCoordsStr = "";
    private boolean textureUiPushed = false;
    private int lastSnapTileX = Integer.MIN_VALUE;
    private int lastSnapTileZ = Integer.MIN_VALUE;
    private int lastScrollOffsetX = Integer.MIN_VALUE;
    private int lastScrollOffsetZ = Integer.MIN_VALUE;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    MinimapHud(Logger log, EntityTracker entities, TileCache cache) {
        this.log = log;
        this.entities = entities;
        this.cache = cache;
        this.minimapAsset = new MinimapAsset(log, cache);
    }

    // ------------------------------------------------------------------
    // Inputs from observers
    // ------------------------------------------------------------------

    void bindSession(ProxySession s) {
        if (this.session == null) {
            this.session = s;
            log.info("meridian-minimap: bound Default-channel session");
        }
    }

    void onJoinWorld(UUID world) {
        if (!java.util.Objects.equals(world, this.currentWorld)) {
            this.currentWorld = world;
            this.lastArrowDir = -1;
            this.lastCoordsStr = "";
            this.initialized.set(false);
            log.info("meridian-minimap: entered world {} — resetting HUD", world);
        }
    }

    void updateYaw(float yawRadians) {
        this.yaw = yawRadians;
        this.haveYaw = true;
    }

    // ------------------------------------------------------------------
    // Tick — called from the module's scheduler
    // ------------------------------------------------------------------

    void tick() {
        ProxySession s = session;
        if (s == null) return;
        Optional<Vec3> maybePos = entities.localPosition();
        if (maybePos.isEmpty()) return;
        Vec3 pos = maybePos.get();

        if (initialized.compareAndSet(false, true)) {
            show(s, pos);
            return;
        }

        UICommandBuilder b = new UICommandBuilder();
        int updates = updateTerrain(b, s, pos);
        updates += updateSmoothScroll(b, pos);
        updates += updateArrow(b);
        updates += updateCoords(b, pos);

        if (updates > 0) {
            sendPatch(s, b);
        }
    }

    // ------------------------------------------------------------------
    // Initial render — one CustomHud lays out the static overlay
    // (marker + compass + coords) and a placeholder texture Panel; the
    // texture itself is updated per-tick.
    // ------------------------------------------------------------------

    private void show(ProxySession s, Vec3 pos) {
        // First push the placeholder tile (referenced by every Panel's
        // initial Background) so the asset exists before the .ui that
        // references it is parsed. Then push the .ui itself.
        if (!textureUiPushed) {
            try {
                minimapAsset.pushPlaceholder(s);
            } catch (IOException e) {
                log.warn("meridian-minimap: placeholder PNG push failed", e);
            }
            pushTextureUi(s);
            textureUiPushed = true;
        }
        // Reset boundary tracker so updateTerrain repaints all 169 slots.
        lastSnapTileX = Integer.MIN_VALUE;
        lastSnapTileZ = Integer.MIN_VALUE;

        UICommandBuilder b = new UICommandBuilder();

        int rootHeight = DISPLAY_SIZE + 20;
        b.appendInlineToRoot(String.format(Locale.ROOT,
                "Group #MinimapRoot { Anchor: (Top: 10, Right: 10, Width: %d, Height: %d); "
                        + "Panel #MinimapContainer { Anchor: (Top: 0, Width: %d, Height: %d); } "
                        + "Panel #MarkerOverlay { Anchor: (Top: 0, Width: %d, Height: %d); } "
                        + "Panel #CoordinatesContainer { Anchor: (Top: 0, Width: %d, Height: %d); } "
                        + "}",
                DISPLAY_SIZE, rootHeight,
                DISPLAY_SIZE, DISPLAY_SIZE,
                DISPLAY_SIZE, DISPLAY_SIZE,
                DISPLAY_SIZE, rootHeight));

        // Load the texture Panel into MinimapContainer.
        b.append("#MinimapContainer", TEXTURE_UI_REF);

        buildMarkerOverlay(b);
        buildCoordinates(b);

        // First frame's content.
        updateTerrain(b, s, pos);
        updateArrow(b);
        updateCoords(b, pos);

        CustomHud hud = new CustomHud(HUD_ID, Z_ORDER, true, b.commands());
        s.sendToClient(hud);
        log.info("meridian-minimap: pushed initial HUD (texture-backed)");
    }

    /**
     * Ships the .ui asset that defines the {@code GRID_SIDE}×{@code GRID_SIDE}
     * tile-Panel grid. Each Panel's initial {@code Background} is a path
     * string pointing at the placeholder PNG — that locks the Background's
     * type to "texture path" at parse time so subsequent
     * {@code Set Background = "<tile>.png"} commands are accepted (a
     * Color-typed initial would prevent that swap).
     */
    private void pushTextureUi(ProxySession s) {
        // The grid container is larger than the visible viewport — it carries
        // a 1-tile ring beyond the visible 7×7 area. The container shifts
        // each tick to give the player smooth scrolling; the ring keeps the
        // visible edge filled at any sub-tile offset. Initial Anchor.Left/Top
        // is the fracX=0 case (-TILE_PX/2); updateSmoothScroll() adjusts it.
        StringBuilder sb = new StringBuilder(GRID_SIDE * GRID_SIDE * 80);
        sb.append("Group #TileGrid {\n");
        sb.append("    Anchor: (Top: ").append(MinimapAsset.GRID_BASE_OFFSET)
                .append(", Left: ").append(MinimapAsset.GRID_BASE_OFFSET)
                .append(", Width: ").append(MinimapAsset.GRID_TOTAL_PX)
                .append(", Height: ").append(MinimapAsset.GRID_TOTAL_PX).append(");\n");
        for (int row = 0; row < GRID_SIDE; row++) {
            for (int col = 0; col < GRID_SIDE; col++) {
                int slot = row * GRID_SIDE + col;
                sb.append("    Panel #T").append(slot).append(" {\n")
                        .append("        Anchor: (Left: ").append(col * TILE_PX)
                        .append(", Top: ").append(row * TILE_PX)
                        .append(", Width: ").append(TILE_PX)
                        .append(", Height: ").append(TILE_PX).append(");\n")
                        .append("        Background: \"").append(MinimapAsset.PLACEHOLDER_REF)
                        .append("\";\n")
                        .append("    }\n");
            }
        }
        sb.append("}\n");
        byte[] uiBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        AssetPusher.push(s, TEXTURE_UI_NAME, uiBytes);
        AssetPusher.push(s, "UI/Custom/" + TEXTURE_UI_NAME, uiBytes);
        AssetPusher.push(s, "Common/UI/Custom/" + TEXTURE_UI_NAME, uiBytes);
        s.sendToClient(new RequestCommonAssetsRebuild());
        log.info("meridian-minimap: pushed tile-grid UI asset ({} bytes, {} panels)",
                uiBytes.length, GRID_SIDE * GRID_SIDE);
    }

    private void sendPatch(ProxySession s, UICommandBuilder b) {
        // clear=false → keep existing tree, apply the diff.
        CustomHud hud = new CustomHud(HUD_ID, Z_ORDER, false, b.commands());
        s.sendToClient(hud);
    }


    // ------------------------------------------------------------------
    // Scaffolding builders — fire once on first render
    // ------------------------------------------------------------------

    /** Static marker overlay: a center dot, four compass labels, eight arrow panels. */
    private void buildMarkerOverlay(UICommandBuilder b) {
        int markerSize = 6;
        int cx = DISPLAY_SIZE / 2 - markerSize / 2;
        int cy = DISPLAY_SIZE / 2 - markerSize / 2;
        StringBuilder sb = new StringBuilder(2048);

        // Centre dot.
        sb.append("Panel #Marker { Anchor: (Left: ").append(cx)
                .append(", Top: ").append(cy)
                .append(", Width: ").append(markerSize)
                .append(", Height: ").append(markerSize)
                .append("); Background: #FFFFFF; } ");

        // Eight directional arrow segments (two panels each). One direction
        // is visible at a time; the others sit transparent. We later flip
        // backgrounds via Set in updateArrow.
        arrowPair(sb, "ArrowN", DISPLAY_SIZE / 2 - 1, cy - 6, DISPLAY_SIZE / 2 - 2, cy - 4, true);
        arrowPair(sb, "ArrowNE", cx + 10, cy - 3, cx + 8, cy - 5, true);
        arrowPair(sb, "ArrowE", cx + markerSize + 4, cy + 2, cx + markerSize + 2, cy + 1, false);
        arrowPair(sb, "ArrowSE", cx + 10, cy + markerSize + 2, cx + 8, cy + markerSize + 4, true);
        arrowPair(sb, "ArrowS", DISPLAY_SIZE / 2 - 1, cy + markerSize + 4, DISPLAY_SIZE / 2 - 2, cy + markerSize + 2, true);
        arrowPair(sb, "ArrowSW", cx - 4, cy + markerSize + 4, cx - 6, cy + markerSize + 2, false);
        arrowPair(sb, "ArrowW", cx - 6, cy + 2, cx - 4, cy + 1, false);
        arrowPair(sb, "ArrowNW", cx - 4, cy - 2, cx - 4, cy - 4, true);

        // Compass labels.
        compassLabel(sb, "CompassN", DISPLAY_SIZE / 2 - 6, 6, "N");
        compassLabel(sb, "CompassS", DISPLAY_SIZE / 2 - 6, DISPLAY_SIZE - 12, "S");
        compassLabel(sb, "CompassW", 6, DISPLAY_SIZE / 2 - 6, "W");
        compassLabel(sb, "CompassE", DISPLAY_SIZE - 14, DISPLAY_SIZE / 2 - 6, "E");

        b.appendInline("#MarkerOverlay", sb.toString());
    }

    private static void arrowPair(StringBuilder sb, String name,
                                  int x1, int y1, int x2, int y2, boolean horizontal) {
        int w2 = horizontal ? 4 : 2;
        int h2 = horizontal ? 2 : 4;
        sb.append("Panel #").append(name).append("1 { Anchor: (Left: ").append(x1)
                .append(", Top: ").append(y1)
                .append(", Width: 2, Height: 2); Background: #00000000; } ");
        sb.append("Panel #").append(name).append("2 { Anchor: (Left: ").append(x2)
                .append(", Top: ").append(y2)
                .append(", Width: ").append(w2)
                .append(", Height: ").append(h2)
                .append("); Background: #00000000; } ");
    }

    private static void compassLabel(StringBuilder sb, String id, int x, int y, String text) {
        sb.append("Label #").append(id)
                .append(" { Anchor: (Left: ").append(x).append(", Top: ").append(y)
                .append(", Width: 12, Height: 10); Text: \"").append(text)
                .append("\"; Style: (FontSize: 10, TextColor: #FFFFFF, RenderBold: true, Alignment: Center); } ");
    }

    private void buildCoordinates(UICommandBuilder b) {
        b.appendInline("#CoordinatesContainer", String.format(Locale.ROOT,
                "Panel #CoordinatesDisplay { Anchor: (Top: %d, Right: 0, Width: %d, Height: 14); "
                        + "Label #CoordinatesLabel { Anchor: (Left: 0, Top: 0, Width: %d, Height: 14); "
                        + "Text: \"X: 0 Y: 0 Z: 0\"; "
                        + "Style: (FontSize: 10, TextColor: #FFFFFF, RenderBold: true, Alignment: Center); } }",
                DISPLAY_SIZE + 4, DISPLAY_SIZE, DISPLAY_SIZE));
    }

    // ------------------------------------------------------------------
    // Tick helpers — fire every tick
    // ------------------------------------------------------------------

    /**
     * Per-tick terrain refresh. Two phases:
     *
     * <ol>
     *   <li><b>Preload ring</b> — for every tile in {@code visible + 1-tile
     *       outer ring}, make sure it's already on the client. Most calls
     *       are cache hits (HashMap lookup, no work). Only never-seen tiles
     *       trigger render+push. This is what hides boundary-cross lag: the
     *       row about to enter the viewport is already on the client before
     *       the player gets there.</li>
     *   <li><b>Visible Set</b> — only when the player crossed a tile boundary
     *       (or new world data invalidated tiles), emit {@code Set Background}
     *       commands for the visible 13×13 panels. Sets reference names that
     *       are already cached client-side, so no waiting.</li>
     * </ol>
     */
    private int updateTerrain(UICommandBuilder b, ProxySession s, Vec3 pos) {
        int snapTileX = (int) Math.floor(pos.x() / MinimapAsset.TILE_WORLD);
        int snapTileZ = (int) Math.floor(pos.z() / MinimapAsset.TILE_WORLD);

        // Drain newly-arrived chunks and invalidate their corresponding
        // rendered tiles — tile coords map 1:1 to chunk coords because
        // TILE_WORLD == Hytale chunk side. Phase 1's ensureTile will then
        // re-render those whose chunk is in the visible+ring area; tiles
        // far from the player stay invalidated until visited again.
        java.util.Set<Long> changedChunks = cache.consumeChangedKeys();
        for (Long key : changedChunks) {
            minimapAsset.invalidate(TileCache.chunkXOf(key), TileCache.chunkZOf(key));
        }
        boolean tilesChanged = !changedChunks.isEmpty();

        // Phase 1 — preload the visible-plus-one-ring area unconditionally.
        // Cached tiles cost a HashMap hit and return immediately; only fresh
        // tiles encode + push.
        int half = GRID_SIDE / 2;
        int preloadRadius = half + 1;
        for (int dr = -preloadRadius; dr <= preloadRadius; dr++) {
            for (int dc = -preloadRadius; dc <= preloadRadius; dc++) {
                try {
                    minimapAsset.ensureTile(s, snapTileX + dc, snapTileZ + dr);
                } catch (IOException e) {
                    log.warn("meridian-minimap: preload tile failed", e);
                }
            }
        }
        minimapAsset.flushPending(s);

        // Phase 2 — Set Backgrounds only when the visible grid actually shifts.
        if (snapTileX == lastSnapTileX && snapTileZ == lastSnapTileZ && !tilesChanged) {
            return 0;
        }
        lastSnapTileX = snapTileX;
        lastSnapTileZ = snapTileZ;

        int updates = 0;
        for (int row = 0; row < GRID_SIDE; row++) {
            for (int col = 0; col < GRID_SIDE; col++) {
                int tileX = snapTileX + (col - half);
                int tileZ = snapTileZ + (row - half);
                try {
                    String ref = minimapAsset.ensureTile(s, tileX, tileZ);
                    b.set("#T" + (row * GRID_SIDE + col) + ".Background", ref);
                    updates++;
                } catch (IOException e) {
                    log.warn("meridian-minimap: tile render failed ({},{})", tileX, tileZ, e);
                }
            }
        }
        return updates;
    }

    /**
     * Smooth-scroll the tile-grid container: each tick the grid slides by
     * the player's fractional position within the current tile, so the map
     * moves continuously instead of jumping 32 px every boundary crossing.
     *
     * <p>Texture content stays the same between boundary crossings — only
     * the container's Anchor offset changes. At the crossing, the offset
     * wraps from {@code GRID_BASE_OFFSET - 31} back to {@code GRID_BASE_OFFSET}
     * (a 31 px discontinuity) AND the textures shift by one tile via
     * {@link #updateTerrain}'s Set commands. The net visual jump is one
     * pixel — sub-pixel snap, barely visible.
     *
     * <p>Skips emitting Set commands when the player hasn't moved enough to
     * shift the offset by at least one pixel — cheap to call every tick.
     */
    private int updateSmoothScroll(UICommandBuilder b, Vec3 pos) {
        int fracX = (int) Math.floor(pos.x() - Math.floor(pos.x() / MinimapAsset.TILE_WORLD)
                * MinimapAsset.TILE_WORLD);
        int fracZ = (int) Math.floor(pos.z() - Math.floor(pos.z() / MinimapAsset.TILE_WORLD)
                * MinimapAsset.TILE_WORLD);
        int offsetX = MinimapAsset.GRID_BASE_OFFSET - fracX;
        int offsetZ = MinimapAsset.GRID_BASE_OFFSET - fracZ;
        if (offsetX == lastScrollOffsetX && offsetZ == lastScrollOffsetZ) {
            return 0;
        }
        b.setAnchor("#TileGrid.Anchor", offsetX, offsetZ,
                MinimapAsset.GRID_TOTAL_PX, MinimapAsset.GRID_TOTAL_PX);
        lastScrollOffsetX = offsetX;
        lastScrollOffsetZ = offsetZ;
        return 1;
    }

    private int updateArrow(UICommandBuilder b) {
        if (!haveYaw) return 0;
        // Same yaw-to-octant mapping as Landscaper. Their convention: minus-yaw
        // gives screen-facing direction, then quantise to one of eight slots.
        double facingRad = -yaw;
        double deg = Math.toDegrees(facingRad);
        int dir = (int) (((deg % 360.0) + 360.0 + 22.5) / 45.0) % 8;
        if (dir == lastArrowDir) return 0;

        String[] ids = {"ArrowN", "ArrowNE", "ArrowE", "ArrowSE",
                "ArrowS", "ArrowSW", "ArrowW", "ArrowNW"};
        for (int i = 0; i < 8; i++) {
            String color = i == dir ? "#FFFFFF" : "#00000000";
            b.set("#" + ids[i] + "1.Background", color);
            b.set("#" + ids[i] + "2.Background", color);
        }
        lastArrowDir = dir;
        return 1;
    }

    private int updateCoords(UICommandBuilder b, Vec3 pos) {
        String text = String.format(Locale.ROOT,
                "X: %d Y: %d Z: %d", (int) pos.x(), (int) pos.y(), (int) pos.z());
        if (text.equals(lastCoordsStr)) return 0;
        b.set("#CoordinatesLabel.Text", text);
        lastCoordsStr = text;
        return 1;
    }

    private static String hex(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
    }

    // ------------------------------------------------------------------
    // Minimal in-package replica of Hytale's UICommandBuilder — we only
    // need {Append, AppendInline, Set} so we don't lean on the server-side
    // helper class.
    // ------------------------------------------------------------------

    private static final class UICommandBuilder {
        private final java.util.List<CustomUICommand> commands = new java.util.ArrayList<>(64);

        void appendInline(String selector, String document) {
            commands.add(new CustomUICommand(CustomUICommandType.AppendInline,
                    selector, null, document));
        }

        /** Append inline markup at the HUD root — no parent selector. */
        void appendInlineToRoot(String document) {
            commands.add(new CustomUICommand(CustomUICommandType.AppendInline,
                    null, null, document));
        }

        /**
         * Loads a {@code .ui} asset by path and appends it to the HUD root.
         * Unlike {@link #appendInlineToRoot}, the asset has a concrete source
         * location on the client, so {@code TexturePath} references inside
         * the document resolve correctly against {@code Common/UI/Custom/}.
         */
        void append(String documentPath) {
            commands.add(new CustomUICommand(CustomUICommandType.Append,
                    null, null, documentPath));
        }

        /** Loads a {@code .ui} asset into a specific selector target. */
        void append(String selector, String documentPath) {
            commands.add(new CustomUICommand(CustomUICommandType.Append,
                    selector, null, documentPath));
        }

        void set(String selector, String text) {
            // The server's Set protocol wraps the value as
            // {"0": <bsonValue>} — for a plain string that's {"0": "..."} as JSON.
            String data = "{\"0\":\"" + escapeJsonString(text) + "\"}";
            commands.add(new CustomUICommand(CustomUICommandType.Set, selector, data, null));
        }

        /** Set on an int-typed property — value goes unquoted as a JSON number. */
        void set(String selector, int value) {
            String data = "{\"0\":" + value + "}";
            commands.add(new CustomUICommand(CustomUICommandType.Set, selector, data, null));
        }

        /**
         * Set the whole {@code Anchor} struct on an element — Hytale's
         * runtime {@code Anchor.Left} sub-selector doesn't fire; the whole
         * object must be replaced. Field names follow the NOML PascalCase.
         */
        void setAnchor(String selector, int left, int top, int width, int height) {
            String data = String.format(Locale.ROOT,
                    "{\"0\":{\"Left\":%d,\"Top\":%d,\"Width\":%d,\"Height\":%d}}",
                    left, top, width, height);
            commands.add(new CustomUICommand(CustomUICommandType.Set, selector, data, null));
        }


        CustomUICommand[] commands() {
            return commands.toArray(new CustomUICommand[0]);
        }

        private static String escapeJsonString(String s) {
            StringBuilder out = new StringBuilder(s.length() + 4);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            return out.toString();
        }
    }
}
