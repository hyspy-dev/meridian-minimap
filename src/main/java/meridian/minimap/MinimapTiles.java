package meridian.minimap;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import meridian.core.api.ClientAssets;
import meridian.core.api.WorldMap;
import org.slf4j.Logger;

/**
 * Renders the minimap as a grid of small textures — one per square of world — and keeps them
 * on the client.
 *
 * <p>The alternative, one big picture redrawn every tick, means re-encoding and re-shipping the
 * whole view several times a second. Tiles are shipped once each: standing still costs nothing,
 * walking costs the handful of tiles that just came into range, and the client keeps the rest.
 *
 * <p>Colours come from {@link WorldMap}, which holds everything the server ever revealed, so a
 * tile the player walked past an hour ago still draws — the minimap remembers more than the
 * client's own map does.
 */
final class MinimapTiles {

    /** Image pixels per tile side. */
    static final int TILE_RENDER_PX = 16;
    /** On-screen size of one tile, pixels. */
    static final int TILE_PX = 32;
    /** Tiles across the visible window. */
    static final int VISIBLE_SIDE = 7;
    /**
     * Tiles across the panel grid. One ring wider than the window, so that sliding the grid by
     * a fraction of a tile never exposes a bare strip at an edge.
     */
    static final int GRID_SIDE = VISIBLE_SIDE + 2;
    /** Size of the window the grid is clipped to, pixels. */
    static final int DISPLAY_SIZE = VISIBLE_SIDE * TILE_PX;
    /** Size of the whole grid, pixels. */
    static final int GRID_TOTAL_PX = GRID_SIDE * TILE_PX;
    /** Grid offset when the player sits exactly on a tile boundary; scrolling subtracts from it. */
    static final int GRID_BASE_OFFSET = -TILE_PX / 2;

    /** Markup reference for the tile every panel starts out showing. */
    static final String PLACEHOLDER_REF = "mt_placeholder.png";

    /** Colour for world we have never been shown. */
    private static final int UNEXPLORED = 0x87C5AB;

    private final WorldMap map;
    private final ClientAssets assets;
    private final Logger log;

    /** World blocks per tile — the zoom. Written from settings callbacks. */
    private volatile int tileWorld = 32;
    /** tile key to the markup reference for the texture that is currently right for it. */
    private final Map<Long, String> rendered = new HashMap<>();
    /** Chunks the world map has learned about since we last redrew. */
    private final Set<Long> changed = ConcurrentHashMap.newKeySet();
    private final int[] pixels = new int[TILE_RENDER_PX * TILE_RENDER_PX];
    private int sequence;
    private boolean rebuildPending;

    MinimapTiles(WorldMap map, ClientAssets assets, Logger log) {
        this.map = map;
        this.assets = assets;
        this.log = log;
        map.onTileChanged(changed::add);
    }

    int tileWorld() {
        return tileWorld;
    }

    /**
     * Changes how much world one tile covers. Every rendered tile is dropped: the mapping from
     * world to tile moved, so none of them show what their slot now stands for.
     */
    void setTileWorld(int blocks) {
        if (blocks == tileWorld) {
            return;
        }
        tileWorld = blocks;
        rendered.clear();
    }

    /**
     * Pushes the tile each panel is born showing, and returns its reference. Its real job is to
     * fix the type of a panel's {@code Background} to "a texture" while the document is parsed —
     * a colour there would make the later swaps to real tiles fail.
     */
    String pushPlaceholder() {
        BufferedImage img = new BufferedImage(TILE_RENDER_PX, TILE_RENDER_PX,
                BufferedImage.TYPE_INT_RGB);
        int[] fill = new int[TILE_RENDER_PX * TILE_RENDER_PX];
        Arrays.fill(fill, 0xFF202020);
        img.setRGB(0, 0, TILE_RENDER_PX, TILE_RENDER_PX, fill, 0, TILE_RENDER_PX);
        try {
            String ref = push("mt_placeholder", encode(img));
            return ref == null ? PLACEHOLDER_REF : ref;
        } catch (IOException e) {
            log.warn("meridian-minimap: could not encode the placeholder tile", e);
            return PLACEHOLDER_REF;
        }
    }

    /**
     * The reference for the tile at these tile coordinates, rendering and shipping it if the
     * client has not got it yet. A repeat call for an unchanged tile is a map lookup, which is
     * what makes it cheap to ask for the whole grid every tick.
     */
    String ensureTile(int tileX, int tileZ) {
        long key = key(tileX, tileZ);
        String ref = rendered.get(key);
        if (ref != null) {
            return ref;
        }
        try {
            ref = push("mt_" + (sequence++), encode(render(tileX, tileZ)));
        } catch (IOException e) {
            log.warn("meridian-minimap: could not render the tile at {},{}", tileX, tileZ, e);
            return PLACEHOLDER_REF;
        }
        if (ref == null) {
            return PLACEHOLDER_REF;   // nowhere to send it yet; try again next pass
        }
        rendered.put(key, ref);
        return ref;
    }

    /**
     * Drops the tiles the world map has just filled in, so the next pass redraws them. Without
     * this a tile keeps the colours it had when it was first drawn — usually blank, since a
     * tile is normally drawn slightly before the server gets round to revealing it.
     *
     * @return whether anything was dropped, i.e. whether the grid needs repainting
     */
    boolean applyWorldChanges() {
        if (changed.isEmpty()) {
            return false;
        }
        int scale = tileWorld;
        boolean any = false;
        for (Iterator<Long> it = changed.iterator(); it.hasNext(); ) {
            long chunk = it.next();
            it.remove();
            // A chunk and a tile are the same size only at the default zoom. Zoomed in, one
            // chunk feeds several tiles; zoomed out, several chunks share one. Go through block
            // coordinates rather than assume the two line up.
            int fromX = Math.floorDiv(WorldMap.chunkX(chunk) * WorldMap.TILE_BLOCKS, scale);
            int toX = Math.floorDiv((WorldMap.chunkX(chunk) + 1) * WorldMap.TILE_BLOCKS - 1, scale);
            int fromZ = Math.floorDiv(WorldMap.chunkZ(chunk) * WorldMap.TILE_BLOCKS, scale);
            int toZ = Math.floorDiv((WorldMap.chunkZ(chunk) + 1) * WorldMap.TILE_BLOCKS - 1, scale);
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromZ; z <= toZ; z++) {
                    any |= rendered.remove(key(x, z)) != null;
                }
            }
        }
        return any;
    }

    /**
     * Tells the client to take notice of the tiles just pushed. Textures shipped mid-session
     * draw as the missing-texture cross until it does, and one call covers a whole batch, so
     * this belongs at the end of a pass rather than next to each push.
     */
    void flushRebuild() {
        if (rebuildPending) {
            assets.requestRebuild();
            rebuildPending = false;
        }
    }

    /** A new session starts with an empty client, so nothing we pushed is there any more. */
    void reset() {
        rendered.clear();
        changed.clear();
        rebuildPending = false;
    }

    // ------------------------------------------------------------------

    /**
     * Ships a tile and returns the name markup should use for it.
     *
     * <p>The two names differ. The client resolves a reference found in a document against that
     * document's own folder and asks for the high-resolution variant, so {@code mt_3.png} used
     * from a {@code UI/Custom} document is fetched as {@code UI/Custom/mt_3@2x.png} — which is
     * the name it has to arrive under. Identical tiles are shipped once, so the name that comes
     * back may belong to whichever tile got there first; it is the returned one that resolves.
     */
    private String push(String base, byte[] png) {
        boolean fresh = !assets.isPushed(png);
        String name = assets.push("UI/Custom/" + base + "@2x.png", png).orElse(null);
        if (name == null) {
            return null;   // no session yet, so nothing was sent and nothing is worth remembering
        }
        rebuildPending |= fresh;
        return name.substring(name.lastIndexOf('/') + 1).replace("@2x", "");
    }

    private BufferedImage render(int tileX, int tileZ) {
        int scale = tileWorld;
        int blocksPerPixel = Math.max(1, scale / TILE_RENDER_PX);
        int originX = tileX * scale;
        int originZ = tileZ * scale;
        for (int py = 0; py < TILE_RENDER_PX; py++) {
            int wz = originZ + py * blocksPerPixel;
            for (int px = 0; px < TILE_RENDER_PX; px++) {
                int colour = map.colourAtBlock(originX + px * blocksPerPixel, wz);
                pixels[py * TILE_RENDER_PX + px] = 0xFF000000 | (colour < 0 ? UNEXPLORED : colour);
            }
        }
        BufferedImage img = new BufferedImage(TILE_RENDER_PX, TILE_RENDER_PX,
                BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, TILE_RENDER_PX, TILE_RENDER_PX, pixels, 0, TILE_RENDER_PX);
        return img;
    }

    private static byte[] encode(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", out);
        return out.toByteArray();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
