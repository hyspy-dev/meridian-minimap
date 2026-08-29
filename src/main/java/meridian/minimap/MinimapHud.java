package meridian.minimap;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import meridian.core.api.ClientAssets;
import meridian.core.api.Hud;
import meridian.core.api.Player;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import meridian.core.api.WorldMap;
import org.slf4j.Logger;

/**
 * The minimap itself: a window onto the world map, drawn from a grid of tile textures with the
 * player fixed at its centre.
 *
 * <p>Everything is sent as a difference against what the client already has. A tick where the
 * player has not moved sends nothing at all; walking sends a handful of property changes. Only
 * crossing a tile boundary repaints the grid, and between boundaries the whole grid slides by a
 * few pixels, which is what makes the map scroll rather than jump.
 */
final class MinimapHud {

    /** Where on the screen the minimap sits. */
    public enum Corner {
        TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT
    }

    /**
     * How much world fits in one tile. The tiles keep their size on screen — what changes is
     * how much ground each of them covers, and so how far the map sees.
     */
    public enum Zoom {
        NEAR(16),     // about 112 blocks across
        NORMAL(32),   // about 224 blocks
        FAR(64),      // about 448 blocks
        FURTHEST(128);

        final int tileWorld;

        Zoom(int tileWorld) {
            this.tileWorld = tileWorld;
        }
    }

    static final long TICK_MS = 500;

    private static final int DISPLAY_SIZE = MinimapTiles.DISPLAY_SIZE;
    private static final int TILE_PX = MinimapTiles.TILE_PX;
    private static final int GRID_SIDE = MinimapTiles.GRID_SIDE;
    private static final int CORNER_MARGIN = 10;

    private static final String HUD_ID = "meridian-minimap";
    private static final int Z_ORDER = 50;
    /**
     * Where the grid document is put on the client, and how markup inside it refers to itself.
     * The two differ: a reference inside a document is resolved against the folder the document
     * came from, which is precisely why the tiles it shows can be named without a path.
     */
    private static final String GRID_UI_NAME = "UI/Custom/meridian-minimap-grid.ui";
    private static final String GRID_UI_REF = "meridian-minimap-grid.ui";

    private final Logger log;
    private final World world;
    private final WorldMap map;
    private final Hud hud;
    private final ClientAssets assets;
    private final MinimapTiles tiles;

    // -- Settings, live from the settings page --------------------------
    private volatile Corner position = Corner.TOP_RIGHT;
    private volatile Zoom zoom = Zoom.NORMAL;
    private volatile boolean showCoords = true;
    private volatile boolean showCompass = true;
    private volatile boolean showMarker = true;

    // -- What the client is currently showing ---------------------------
    private UUID drawnWorld;
    private boolean drawn;
    private int lastTileX = Integer.MIN_VALUE;
    private int lastTileZ = Integer.MIN_VALUE;
    private int lastScrollX = Integer.MIN_VALUE;
    private int lastScrollZ = Integer.MIN_VALUE;
    private int lastArrow = -1;
    private String lastCoords = "";

    MinimapHud(Logger log, World world, WorldMap map, Hud hud, ClientAssets assets) {
        this.log = log;
        this.world = world;
        this.map = map;
        this.hud = hud;
        this.assets = assets;
        this.tiles = new MinimapTiles(map, assets, log);
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    void setZoom(Zoom z) {
        if (z == null || z == zoom) {
            return;
        }
        zoom = z;
        tiles.setTileWorld(z.tileWorld);
        // Every tile now stands for a different piece of world, so nothing on screen is right
        // any more. Forgetting where we were makes the next tick repaint and re-place the grid.
        lastTileX = Integer.MIN_VALUE;
        lastTileZ = Integer.MIN_VALUE;
        lastScrollX = Integer.MIN_VALUE;
        lastScrollZ = Integer.MIN_VALUE;
    }

    void setPosition(Corner p) {
        if (p == null || p == position) {
            return;
        }
        position = p;
        if (drawn) {
            hud.batch()
                    .setRaw("#MinimapRoot.Anchor", cornerAnchor(p, DISPLAY_SIZE, rootHeight()))
                    .patch(HUD_ID, Z_ORDER);
        }
    }

    void setShowCoords(boolean show) {
        if (show == showCoords) {
            return;
        }
        showCoords = show;
        if (drawn) {
            hud.batch().set("#CoordinatesDisplay.Visible", show).patch(HUD_ID, Z_ORDER);
        }
    }

    void setShowCompass(boolean show) {
        if (show == showCompass) {
            return;
        }
        showCompass = show;
        if (drawn) {
            Hud.Batch b = hud.batch();
            compassVisibility(b, show);
            b.patch(HUD_ID, Z_ORDER);
        }
    }

    void setShowMarker(boolean show) {
        if (show == showMarker) {
            return;
        }
        showMarker = show;
        if (drawn) {
            hud.batch().set("#Marker.Visible", show).patch(HUD_ID, Z_ORDER);
        }
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    void tick() {
        UUID current = map.currentWorld();
        if (!current.equals(drawnWorld)) {
            // A new world means a client that has been emptied: our documents and textures are
            // gone from it, whatever we think we sent. Start over.
            drawnWorld = current;
            drawn = false;
            tiles.reset();
            lastTileX = Integer.MIN_VALUE;
            lastTileZ = Integer.MIN_VALUE;
            lastScrollX = Integer.MIN_VALUE;
            lastScrollZ = Integer.MIN_VALUE;
            lastArrow = -1;
            lastCoords = "";
        }

        Player player = world.player().orElse(null);
        Vec3 pos = player == null ? null : player.position();
        if (pos == null) {
            return;
        }

        if (!drawn) {
            draw(pos, player);
            return;
        }

        Hud.Batch b = hud.batch();
        paintTiles(b, pos);
        scroll(b, pos);
        arrow(b, player);
        coordinates(b, pos);
        b.patch(HUD_ID, Z_ORDER);
    }

    // ------------------------------------------------------------------
    // First draw
    // ------------------------------------------------------------------

    private void draw(Vec3 pos, Player player) {
        // The grid document names the tile it starts out showing, so that tile has to be on the
        // client before the document is read.
        tiles.pushPlaceholder();
        pushGridDocument();
        tiles.flushRebuild();

        int rootHeight = rootHeight();
        Hud.Batch b = hud.batch();
        b.appendInline(String.format(Locale.ROOT,
                "Group #MinimapRoot { Anchor: %s; "
                        + "Panel #MinimapContainer { Anchor: (Top: 0, Width: %d, Height: %d); } "
                        + "Panel #MarkerOverlay { Anchor: (Top: 0, Width: %d, Height: %d); } "
                        + "Panel #CoordinatesContainer { Anchor: (Top: 0, Width: %d, Height: %d); } "
                        + "}",
                cornerMarkup(position, DISPLAY_SIZE, rootHeight),
                DISPLAY_SIZE, DISPLAY_SIZE,
                DISPLAY_SIZE, DISPLAY_SIZE,
                DISPLAY_SIZE, rootHeight));
        b.appendTo("#MinimapContainer", GRID_UI_REF);
        markerOverlay(b);
        coordinatesPanel(b);

        paintTiles(b, pos);
        scroll(b, pos);
        arrow(b, player);
        coordinates(b, pos);

        // The markup shows everything; the player may have turned some of it off in an earlier
        // session, and those preferences arrive before the first frame is drawn.
        if (!showCoords) {
            b.set("#CoordinatesDisplay.Visible", false);
        }
        if (!showCompass) {
            compassVisibility(b, false);
        }
        if (!showMarker) {
            b.set("#Marker.Visible", false);
        }

        b.show(HUD_ID, Z_ORDER);
        drawn = true;
        log.info("meridian-minimap: drew the minimap for world {}", drawnWorld);
    }

    /**
     * Ships the document holding the grid of tile panels.
     *
     * <p>Each panel is born showing the placeholder image, given as a path. That is deliberate:
     * it settles the panel background as being a picture rather than a colour, and a panel that
     * started out a colour refuses to become a picture later.
     */
    private void pushGridDocument() {
        StringBuilder sb = new StringBuilder(GRID_SIDE * GRID_SIDE * 80);
        sb.append("Group #TileGrid {\n");
        sb.append("    Anchor: (Top: ").append(MinimapTiles.GRID_BASE_OFFSET)
                .append(", Left: ").append(MinimapTiles.GRID_BASE_OFFSET)
                .append(", Width: ").append(MinimapTiles.GRID_TOTAL_PX)
                .append(", Height: ").append(MinimapTiles.GRID_TOTAL_PX).append(");\n");
        for (int row = 0; row < GRID_SIDE; row++) {
            for (int col = 0; col < GRID_SIDE; col++) {
                sb.append("    Panel #T").append(row * GRID_SIDE + col).append(" {\n")
                        .append("        Anchor: (Left: ").append(col * TILE_PX)
                        .append(", Top: ").append(row * TILE_PX)
                        .append(", Width: ").append(TILE_PX)
                        .append(", Height: ").append(TILE_PX).append(");\n")
                        .append("        Background: \"").append(MinimapTiles.PLACEHOLDER_REF)
                        .append("\";\n")
                        .append("    }\n");
            }
        }
        sb.append("}\n");
        assets.push(GRID_UI_NAME, sb.toString().getBytes(StandardCharsets.UTF_8));
        assets.requestRebuild();
    }

    private int rootHeight() {
        return DISPLAY_SIZE + 20;
    }

    // ------------------------------------------------------------------
    // The parts that never change
    // ------------------------------------------------------------------

    /** The dot for the player, the direction they face, and the four compass letters. */
    private void markerOverlay(Hud.Batch b) {
        int markerSize = 6;
        int cx = DISPLAY_SIZE / 2 - markerSize / 2;
        int cy = DISPLAY_SIZE / 2 - markerSize / 2;
        StringBuilder sb = new StringBuilder(2048);

        sb.append("Panel #Marker { Anchor: (Left: ").append(cx)
                .append(", Top: ").append(cy)
                .append(", Width: ").append(markerSize)
                .append(", Height: ").append(markerSize)
                .append("); Background: #FFFFFF; } ");

        // All eight directions are laid out once and left transparent; turning the player only
        // colours one of them in, which is far less work than moving a shape around.
        arrowPair(sb, "ArrowN", DISPLAY_SIZE / 2 - 1, cy - 6, DISPLAY_SIZE / 2 - 2, cy - 4, true);
        arrowPair(sb, "ArrowNE", cx + 10, cy - 3, cx + 8, cy - 5, true);
        arrowPair(sb, "ArrowE", cx + markerSize + 4, cy + 2, cx + markerSize + 2, cy + 1, false);
        arrowPair(sb, "ArrowSE", cx + 10, cy + markerSize + 2, cx + 8, cy + markerSize + 4, true);
        arrowPair(sb, "ArrowS", DISPLAY_SIZE / 2 - 1, cy + markerSize + 4,
                DISPLAY_SIZE / 2 - 2, cy + markerSize + 2, true);
        arrowPair(sb, "ArrowSW", cx - 4, cy + markerSize + 4, cx - 6, cy + markerSize + 2, false);
        arrowPair(sb, "ArrowW", cx - 6, cy + 2, cx - 4, cy + 1, false);
        arrowPair(sb, "ArrowNW", cx - 4, cy - 2, cx - 4, cy - 4, true);

        compassLabel(sb, "CompassN", DISPLAY_SIZE / 2 - 6, 6, "N");
        compassLabel(sb, "CompassS", DISPLAY_SIZE / 2 - 6, DISPLAY_SIZE - 12, "S");
        compassLabel(sb, "CompassW", 6, DISPLAY_SIZE / 2 - 6, "W");
        compassLabel(sb, "CompassE", DISPLAY_SIZE - 14, DISPLAY_SIZE / 2 - 6, "E");

        b.appendInlineTo("#MarkerOverlay", sb.toString());
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
                .append("\"; Style: (FontSize: 10, TextColor: #FFFFFF, RenderBold: true, "
                        + "Alignment: Center); } ");
    }

    private void coordinatesPanel(Hud.Batch b) {
        b.appendInlineTo("#CoordinatesContainer", String.format(Locale.ROOT,
                "Panel #CoordinatesDisplay { Anchor: (Top: %d, Right: 0, Width: %d, Height: 14); "
                        + "Label #CoordinatesLabel { Anchor: (Left: 0, Top: 0, Width: %d, "
                        + "Height: 14); Text: \"X: 0 Y: 0 Z: 0\"; "
                        + "Style: (FontSize: 10, TextColor: #FFFFFF, RenderBold: true, "
                        + "Alignment: Center); } }",
                DISPLAY_SIZE + 4, DISPLAY_SIZE, DISPLAY_SIZE));
    }

    private static void compassVisibility(Hud.Batch b, boolean show) {
        b.set("#CompassN.Visible", show);
        b.set("#CompassS.Visible", show);
        b.set("#CompassW.Visible", show);
        b.set("#CompassE.Visible", show);
    }

    // ------------------------------------------------------------------
    // The parts that change
    // ------------------------------------------------------------------

    /**
     * Keeps the grid showing the right piece of world.
     *
     * <p>Two things happen here. The ring of tiles just beyond the window is made sure of every
     * tick, whether or not anything is drawn with it — that is what stops the map stalling at a
     * boundary, since the row about to appear is already on the client by the time the player
     * reaches it. Then, only if the player has actually crossed a boundary or the world map has
     * learned something new, each panel is pointed at the tile it should now show.
     */
    private void paintTiles(Hud.Batch b, Vec3 pos) {
        int scale = tiles.tileWorld();
        int tileX = (int) Math.floor(pos.x() / scale);
        int tileZ = (int) Math.floor(pos.z() / scale);

        boolean worldChanged = tiles.applyWorldChanges();

        int half = GRID_SIDE / 2;
        for (int dz = -half - 1; dz <= half + 1; dz++) {
            for (int dx = -half - 1; dx <= half + 1; dx++) {
                tiles.ensureTile(tileX + dx, tileZ + dz);
            }
        }
        tiles.flushRebuild();

        if (tileX == lastTileX && tileZ == lastTileZ && !worldChanged) {
            return;
        }
        lastTileX = tileX;
        lastTileZ = tileZ;

        for (int row = 0; row < GRID_SIDE; row++) {
            for (int col = 0; col < GRID_SIDE; col++) {
                String ref = tiles.ensureTile(tileX + (col - half), tileZ + (row - half));
                b.set("#T" + (row * GRID_SIDE + col) + ".Background", ref);
            }
        }
    }

    /**
     * Slides the grid by how far the player stands into their current tile, so the map drifts
     * with them instead of standing still and then jumping a whole tile at the boundary. At the
     * crossing the slide wraps back to nothing at the same moment the tiles shift over by one,
     * and the two cancel out to about a pixel.
     */
    private void scroll(Hud.Batch b, Vec3 pos) {
        int scale = tiles.tileWorld();
        // How far into the tile the player is, measured in world blocks, then in screen pixels.
        // The two are the same only at the default zoom; further out a block is worth less than
        // a pixel, and sliding by blocks would drag the grid clean off its own edge.
        int intoX = (int) Math.floor(pos.x() - Math.floor(pos.x() / scale) * scale);
        int intoZ = (int) Math.floor(pos.z() - Math.floor(pos.z() / scale) * scale);
        int offsetX = MinimapTiles.GRID_BASE_OFFSET - intoX * TILE_PX / scale;
        int offsetZ = MinimapTiles.GRID_BASE_OFFSET - intoZ * TILE_PX / scale;
        if (offsetX == lastScrollX && offsetZ == lastScrollZ) {
            return;
        }
        lastScrollX = offsetX;
        lastScrollZ = offsetZ;
        b.setRaw("#TileGrid.Anchor", String.format(Locale.ROOT,
                "{\"Left\":%d,\"Top\":%d,\"Width\":%d,\"Height\":%d}",
                offsetX, offsetZ, MinimapTiles.GRID_TOTAL_PX, MinimapTiles.GRID_TOTAL_PX));
    }

    private void arrow(Hud.Batch b, Player player) {
        Vec3 look = player.lookDirection();
        if (look == null) {
            return;
        }
        // North on the map is negative Z, and the angle grows clockwise from there, which is the
        // order the eight arrows are listed in. The half-step keeps a direction lit while the
        // player looks anywhere within its slice rather than only dead on.
        double degrees = Math.toDegrees(Math.atan2(look.x(), -look.z()));
        int dir = (int) (((degrees % 360.0) + 360.0 + 22.5) / 45.0) % 8;
        if (dir == lastArrow) {
            return;
        }
        lastArrow = dir;
        String[] ids = {"ArrowN", "ArrowNE", "ArrowE", "ArrowSE",
                "ArrowS", "ArrowSW", "ArrowW", "ArrowNW"};
        for (int i = 0; i < ids.length; i++) {
            String colour = i == dir ? "#FFFFFF" : "#00000000";
            b.set("#" + ids[i] + "1.Background", colour);
            b.set("#" + ids[i] + "2.Background", colour);
        }
    }

    private void coordinates(Hud.Batch b, Vec3 pos) {
        String text = String.format(Locale.ROOT,
                "X: %d Y: %d Z: %d", (int) pos.x(), (int) pos.y(), (int) pos.z());
        if (text.equals(lastCoords)) {
            return;
        }
        lastCoords = text;
        b.set("#CoordinatesLabel.Text", text);
    }

    // ------------------------------------------------------------------

    /** Anchor markup pinning an element to a corner of the screen. */
    private static String cornerMarkup(Corner corner, int width, int height) {
        String vertical = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT
                ? "Top" : "Bottom";
        String horizontal = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT
                ? "Left" : "Right";
        return String.format(Locale.ROOT, "(%s: %d, %s: %d, Width: %d, Height: %d)",
                vertical, CORNER_MARGIN, horizontal, CORNER_MARGIN, width, height);
    }

    /**
     * The same anchor as JSON, for moving the minimap after it has been drawn. Only the two
     * edges it hangs from are named: an edge left out is one the element does not answer to,
     * which is what pins it to a corner rather than stretching it across the screen.
     */
    private static String cornerAnchor(Corner corner, int width, int height) {
        String vertical = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT
                ? "Top" : "Bottom";
        String horizontal = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT
                ? "Left" : "Right";
        return String.format(Locale.ROOT,
                "{\"%s\":%d,\"%s\":%d,\"Width\":%d,\"Height\":%d}",
                vertical, CORNER_MARGIN, horizontal, CORNER_MARGIN, width, height);
    }
}
