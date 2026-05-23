package meridian.minimap;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.setup.RequestCommonAssetsRebuild;
import org.slf4j.Logger;

/**
 * Tile-based minimap renderer — the visible area is a fixed grid of N×N
 * texture-backed Panels, each showing one fixed-size world chunk. Each
 * unique chunk is rendered to a small PNG ONCE per session and cached
 * client-side by its content-addressed name; movement only requires
 * pushing the few new tiles that just entered the viewport.
 *
 * <p>Compared with the single-big-PNG approach this replaces:
 * <ul>
 *   <li>Per-tick PNG of {@code IMG_SIZE²} pixels → per-tile-crossing
 *       generation of ~one row/column's worth of new tiles (~{@value #GRID_SIDE}
 *       small PNGs of {@value #TILE_PX}² pixels each).</li>
 *   <li>Per-tick full asset push (~25 KB) → small tile pushes only when a new
 *       world chunk enters the viewport.</li>
 *   <li>Per-tick {@code RequestCommonAssetsRebuild} → emitted only when at
 *       least one new tile arrived.</li>
 * </ul>
 *
 * <p>The result is essentially zero work per tick when the player stands
 * still, a one-time burst of {@value #GRID_SIDE}² tile renders at startup,
 * and incremental pushes thereafter.
 */
final class MinimapAsset {

    // Profile B: 32-block tiles, 16-px render, 2 blocks per pixel.
    // Boundary crossings every ~32 blocks (~8 s of walking) instead of 16 —
    // ~2× fewer client-side rebuilds, the main source of in-motion stutters.
    // Per-tile data 4× larger than profile A, but only 3× fewer tiles per
    // ring → comparable total payload, much rarer cost spikes. Same render
    // density (2 world blocks per pixel) so the minimap looks essentially
    // the same, just on a slightly bigger display (7×32 = 224 px).

    /** World blocks per tile side. */
    static final int TILE_WORLD = 32;
    /** Image pixels per tile side — half the display, client upscales 2×. */
    static final int TILE_RENDER_PX = 16;
    /** On-screen size of one tile in pixels. */
    static final int TILE_PX = 32;
    /** World blocks each render pixel represents. */
    private static final int BLOCKS_PER_PIXEL = TILE_WORLD / TILE_RENDER_PX;
    /** Visible tiles per side — what the user actually sees through the viewport. */
    static final int VISIBLE_SIDE = 7;
    /**
     * Actual panel grid side. Includes a 1-tile ring around the visible
     * area so smooth-scroll offset always has tiles to show; otherwise
     * partial scroll would expose an empty strip on one edge.
     */
    static final int GRID_SIDE = VISIBLE_SIDE + 2;
    /** Visible window size on screen (what {@code MinimapContainer} clips to). */
    static final int DISPLAY_SIZE = VISIBLE_SIDE * TILE_PX;
    /** Total pixel size of the panel grid (larger than the visible window). */
    static final int GRID_TOTAL_PX = GRID_SIDE * TILE_PX;
    /**
     * Static base offset for the {@code TileGrid} container — places the
     * 9×9 grid so the centre tile's top-left lands at the viewport's centre
     * when the player sits exactly on a tile boundary (fracX = 0).
     * Smooth-scroll math: actual Left = {@code GRID_BASE_OFFSET - fracX}.
     */
    static final int GRID_BASE_OFFSET = -TILE_PX / 2;

    /** Sky / unknown colour for chunks not yet downloaded. */
    private static final int SKY = 0x87C5AB;

    private final Logger log;
    private final TileCache cache;
    /** chunkKey({@code tileX, tileZ}) → reference name for the rendered tile. */
    private final Map<Long, String> renderedTiles = new HashMap<>();
    /** Pre-allocated pixel buffer reused for every tile render. */
    private final int[] pixelBuffer = new int[TILE_RENDER_PX * TILE_RENDER_PX];
    private boolean pendingRebuild = false;

    MinimapAsset(Logger log, TileCache cache) {
        this.log = log;
        this.cache = cache;
    }

    /**
     * Reference name for the placeholder tile baked into the .ui document.
     * Each Panel in the grid starts with this Background to lock its type
     * to a path-string at parse time (so subsequent Set-Background to other
     * tile names works — Color-type initial would prevent that).
     */
    static final String PLACEHOLDER_REF = "mt_placeholder.png";

    /**
     * Pushes the placeholder tile (16×16 transparent PNG) under names the
     * resolver might look up. Returns the reference path to use in the .ui's
     * initial {@code Background:}.
     */
    String pushPlaceholder(ProxySession session) throws IOException {
        // Tiny mid-gray PNG at the same resolution as a tile — never seen
        // for long, replaced on first tick. No rebuild flag — the .ui push
        // that immediately follows sends its own RequestCommonAssetsRebuild.
        BufferedImage img = new BufferedImage(TILE_RENDER_PX, TILE_RENDER_PX, BufferedImage.TYPE_INT_RGB);
        int[] fill = new int[TILE_RENDER_PX * TILE_RENDER_PX];
        for (int i = 0; i < fill.length; i++) fill[i] = 0xFF202020;
        img.setRGB(0, 0, TILE_RENDER_PX, TILE_RENDER_PX, fill, 0, TILE_RENDER_PX);
        byte[] bytes = encode(img);
        pushUnderAllNames(session, "mt_placeholder", bytes);
        return PLACEHOLDER_REF;
    }

    /**
     * Ensures the tile for chunk coords {@code (tileX, tileZ)} exists on the
     * client. Renders + pushes if not seen before; otherwise returns the
     * cached reference. The reference name is what callers plug into a
     * Panel's {@code Set Background = "<ref>"} via the command builder.
     */
    String ensureTile(ProxySession session, int tileX, int tileZ) throws IOException {
        long key = ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
        String ref = renderedTiles.get(key);
        if (ref != null) return ref;

        byte[] bytes = encode(renderTile(tileX, tileZ));
        // Sign-safe coord encoding for the asset path.
        String base = "mt_" + signed(tileX) + "_" + signed(tileZ);
        ref = base + ".png";
        pushUnderAllNames(session, base, bytes);
        // Textures DO need the rebuild — empirically confirmed: skipping
        // this flag makes new tiles render as the missing-texture X.
        pendingRebuild = true;
        renderedTiles.put(key, ref);
        return ref;
    }

    /**
     * Flushes the cumulative {@link RequestCommonAssetsRebuild} after a batch
     * of tile pushes. Called once at the end of each tick that introduced new
     * tiles; skipped entirely when no new tiles were added.
     */
    void flushPending(ProxySession session) {
        if (pendingRebuild) {
            session.sendToClient(new RequestCommonAssetsRebuild());
            pendingRebuild = false;
        }
    }

    /**
     * Forgets the rendered tile for {@code (tileX, tileZ)}. The next
     * {@link #ensureTile} call for these coords will re-render and re-push
     * the asset under the same canonical name; an Anchor-level
     * {@code Set Background} from the caller will then re-bind the texture
     * (the asset name didn't change, but its content hash did).
     *
     * <p>Used when the underlying world chunk receives new data — without
     * this, the rendered tile would freeze at the colours present when it
     * was first generated (typically sky-blue for unloaded chunks).
     */
    void invalidate(int tileX, int tileZ) {
        long key = ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
        renderedTiles.remove(key);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Pushes {@code bytes} under the canonical pack-resolved name. The
     * client's image resolver takes a {@code Background}/{@code TexturePath}
     * value, resolves it relative to the .ui document's location
     * ({@code UI/Custom/meridian-minimap-texture.ui} for us), and appends
     * {@code @2x}. So {@code Background: "mt_p1_p2.png"} ends up looking up
     * {@code UI/Custom/mt_p1_p2@2x.png} — the single name we push here.
     *
     * <p>If textures stop appearing, restore the multi-name fallback (push
     * also under bare and {@code @2x}-only names) to cover resolvers that
     * use different conventions.
     */
    private static void pushUnderAllNames(ProxySession session, String baseNoExt, byte[] bytes) {
        AssetPusher.push(session, "UI/Custom/" + baseNoExt + "@2x.png", bytes);
    }

    private static String signed(int v) {
        return v < 0 ? "n" + (-v) : "p" + v;
    }

    private BufferedImage renderTile(int tileX, int tileZ) {
        int originX = tileX * TILE_WORLD;
        int originZ = tileZ * TILE_WORLD;
        // Each pixel covers a BLOCKS_PER_PIXEL × BLOCKS_PER_PIXEL world square.
        // Sample the top-left block of each square (nearest-neighbour) —
        // cheap and gives the same "chunky" look the user asked for at half
        // resolution. Could average for AA if quality matters more than CPU.
        for (int py = 0; py < TILE_RENDER_PX; py++) {
            int wz = originZ + py * BLOCKS_PER_PIXEL;
            for (int px = 0; px < TILE_RENDER_PX; px++) {
                int wx = originX + px * BLOCKS_PER_PIXEL;
                int color = cache.getColorAt(wx, wz);
                if (color == 0) color = SKY;
                pixelBuffer[py * TILE_RENDER_PX + px] = 0xFF000000 | color;
            }
        }
        BufferedImage img = new BufferedImage(TILE_RENDER_PX, TILE_RENDER_PX, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, TILE_RENDER_PX, TILE_RENDER_PX, pixelBuffer, 0, TILE_RENDER_PX);
        return img;
    }

    private static byte[] encode(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }
}
