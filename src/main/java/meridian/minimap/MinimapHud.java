package meridian.minimap;

import java.util.Arrays;
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
import org.slf4j.Logger;

/**
 * The in-game minimap HUD — a {@code CustomHud} of N×N tiny {@code Panel}
 * elements whose backgrounds are mutated by per-cell {@code Set} commands
 * every tick. Algorithm and layout ported from Landscaper's SimpleMinimap;
 * data source swapped from server-side {@code WorldMapManager} to our
 * {@link TileCache} fed by observed {@code UpdateWorldMap} packets.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #bindSession} fires on the first observed Default-channel
 *       packet — we now have a pipe to the client.</li>
 *   <li>{@link #show} pushes the initial CustomHud (grid scaffolding, player
 *       marker, compass labels, coords container). One-time setup.</li>
 *   <li>{@link #tick} runs every {@link #TICK_MS} ms — recomputes the grid
 *       origin from the player's position, diffs cell colours against the
 *       last render, and emits a single CustomHud with the changed Set
 *       commands.</li>
 * </ol>
 */
final class MinimapHud {

    // -- Layout ---------------------------------------------------------
    // Bumped 1.5× on screen (and in visible blocks) — 50×50 cells of 6 px,
    // each covering 6 world blocks. Display 300 px, visible diameter 300
    // blocks, 2500 panels. Stays well under the empirically-found ceiling
    // (~5000 panels was clean, ~9800 broke). Cell pixel-size and
    // WORLD_BLOCKS unchanged from the prior chunky profile, so the
    // readability and movement-stutter behaviour stay the same.
    /** Number of cells per side. */
    private static final int BLOCKS_PER_SIDE = 50;
    /** Pixel size of one cell — chunky for readability. */
    private static final int BLOCK_SIZE = 6;
    /** World blocks each cell represents. Higher = wider view, less detail. */
    private static final int WORLD_BLOCKS = 6;
    private static final int TOTAL_BLOCKS = BLOCKS_PER_SIDE * BLOCKS_PER_SIDE;
    private static final int DISPLAY_SIZE = BLOCKS_PER_SIDE * BLOCK_SIZE;

    // -- Behaviour ------------------------------------------------------
    static final long TICK_MS = 500;
    private static final String HUD_ID = "meridian-minimap";
    private static final int Z_ORDER = 50;
    /** Sky / unknown colour, matches TileCache's SKY. Used for empty cells. */
    private static final int SKY_COLOR = 0x87C5AB;

    // -- Wire ---------------------------------------------------------
    private final Logger log;
    private final EntityTracker entities;
    private final TileCache cache;

    private volatile ProxySession session;
    private volatile UUID currentWorld;
    private volatile float yaw;
    private volatile boolean haveYaw;

    // -- Render state ---------------------------------------------------
    private final boolean[] circleMask;
    private final int[] displayedColors;
    private int gridOriginX = Integer.MIN_VALUE;
    private int gridOriginZ = Integer.MIN_VALUE;
    private int lastArrowDir = -1;
    private String lastCoordsStr = "";
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    MinimapHud(Logger log, EntityTracker entities, TileCache cache) {
        this.log = log;
        this.entities = entities;
        this.cache = cache;
        this.circleMask = new boolean[TOTAL_BLOCKS];
        this.displayedColors = new int[TOTAL_BLOCKS];
        Arrays.fill(this.displayedColors, -1);
        // Circle mask: cells inside the inscribed radius get terrain colour,
        // everything else is transparent.
        int r = BLOCKS_PER_SIDE / 2;
        int rSq = r * r;
        for (int i = 0; i < TOTAL_BLOCKS; i++) {
            int dx = i % BLOCKS_PER_SIDE - r;
            int dy = i / BLOCKS_PER_SIDE - r;
            this.circleMask[i] = dx * dx + dy * dy <= rSq;
        }
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
            // World change — discard cached cells so we don't blit stale
            // colours into the new world's terrain.
            Arrays.fill(this.displayedColors, -1);
            this.gridOriginX = Integer.MIN_VALUE;
            this.gridOriginZ = Integer.MIN_VALUE;
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
        int updates = updateTerrain(b, pos);
        updates += updateArrow(b);
        updates += updateCoords(b, pos);

        if (updates > 0) {
            sendPatch(s, b);
        }
    }

    // ------------------------------------------------------------------
    // Initial render — one big CustomHud that lays out everything
    // ------------------------------------------------------------------

    private void show(ProxySession s, Vec3 pos) {
        // Recompute origin so the very first frame paints the right tiles.
        recomputeOrigin(pos);

        UICommandBuilder b = new UICommandBuilder();

        // Root anchored to the top-right corner — minimap display +
        // coordinates label below.
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

        // Cell grid — one Panel per cell with id #B<i>, background set by
        // per-tick Set commands after scaffolding lands.
        buildTerrainGrid(b);
        buildMarkerOverlay(b);
        buildCoordinates(b);

        // First frame's content so the user sees the map immediately.
        updateTerrain(b, pos);
        updateArrow(b);
        updateCoords(b, pos);

        CustomHud hud = new CustomHud(HUD_ID, Z_ORDER, true, b.commands());
        s.sendToClient(hud);
        log.info("meridian-minimap: pushed initial HUD ({} cells)", TOTAL_BLOCKS);
    }

    /** Lays out the TOTAL_BLOCKS cell Panels in a grid. */
    private void buildTerrainGrid(UICommandBuilder b) {
        for (int row = 0; row < BLOCKS_PER_SIDE; row++) {
            StringBuilder sb = new StringBuilder(BLOCKS_PER_SIDE * 80);
            for (int col = 0; col < BLOCKS_PER_SIDE; col++) {
                int i = row * BLOCKS_PER_SIDE + col;
                sb.append("Panel #B").append(i)
                        .append(" { Anchor: (Left: ").append(col * BLOCK_SIZE)
                        .append(", Top: ").append(row * BLOCK_SIZE)
                        .append(", Width: ").append(BLOCK_SIZE)
                        .append(", Height: ").append(BLOCK_SIZE)
                        .append("); Background: ")
                        .append(circleMask[i] ? "#000000" : "#00000000")
                        .append("; } ");
            }
            b.appendInline("#MinimapContainer", sb.toString());
        }
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

    private void recomputeOrigin(Vec3 pos) {
        double cx = pos.x();
        double cz = pos.z();
        int r = BLOCKS_PER_SIDE / 2;
        // Snap origin to a multiple of WORLD_BLOCKS, then shift back by half
        // the grid so the player sits at the centre. The grid only redraws
        // when the player crosses a cell boundary — cheap to keep stable.
        this.gridOriginX = (int) Math.floor(cx / WORLD_BLOCKS) * WORLD_BLOCKS - r * WORLD_BLOCKS;
        this.gridOriginZ = (int) Math.floor(cz / WORLD_BLOCKS) * WORLD_BLOCKS - r * WORLD_BLOCKS;
    }

    /**
     * Per-tick terrain refresh: for each cell inside the disc, look up the
     * world-block colour from the tile cache and Set the corresponding
     * Panel's Background. Diffed against the previous frame — only changed
     * cells emit Set commands, so steady-state (camera moving without
     * crossing cell boundaries) costs almost nothing.
     */
    private int updateTerrain(UICommandBuilder b, Vec3 pos) {
        int oldX = gridOriginX;
        int oldZ = gridOriginZ;
        recomputeOrigin(pos);
        boolean moved = (oldX != gridOriginX) || (oldZ != gridOriginZ);
        boolean tilesChanged = cache.consumeModified();
        if (!moved && !tilesChanged) {
            return 0;
        }

        int count = 0;
        for (int i = 0; i < TOTAL_BLOCKS; i++) {
            if (!circleMask[i]) continue;
            int col = i % BLOCKS_PER_SIDE;
            int row = i / BLOCKS_PER_SIDE;
            int wx = gridOriginX + col * WORLD_BLOCKS;
            int wz = gridOriginZ + row * WORLD_BLOCKS;
            int color = cache.getColorAt(wx, wz);
            if (color == 0) color = SKY_COLOR;
            if (displayedColors[i] != color) {
                b.set("#B" + i + ".Background", hex(color));
                displayedColors[i] = color;
                count++;
            }
        }
        return count;
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

        void set(String selector, String text) {
            // The server's Set protocol wraps the value as
            // {"0": <bsonValue>} — for a plain string that's {"0": "..."} as JSON.
            String data = "{\"0\":\"" + escapeJsonString(text) + "\"}";
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
