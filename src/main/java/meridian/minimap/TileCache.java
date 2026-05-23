package meridian.minimap;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import meridian.protocol.packets.worldmap.MapChunk;
import meridian.protocol.packets.worldmap.MapImage;

/**
 * Per-chunk colour cache fed by observed {@code UpdateWorldMap} packets.
 *
 * <p>Stores the most recent {@link MapImage} per chunk-column key and decodes
 * arbitrary world-block coordinates into an RGB colour on demand. Same data
 * source as Landscaper's {@code TerrainCache} — only the ingress differs:
 * they pull from {@code WorldMapManager.getImageAsync}, we feed from packets.
 *
 * <p>Hytale chunk-map tiles are 32×32 palette-indexed images packed at
 * {@code bitsPerIndex} bits per pixel. The decoder mirrors
 * {@code Landscaper.TerrainCache#getPixelColor} byte-for-byte; the palette
 * entries themselves are stored as {@code 0xRRGGBBAA} ints by the server
 * and we strip the alpha into the standard {@code 0xRRGGBB} colour the UI
 * uses.
 */
final class TileCache {

    /** Hytale chunk side, in blocks. */
    private static final int CHUNK_SIZE = 32;
    /** Shift = log2(CHUNK_SIZE). */
    private static final int CHUNK_SHIFT = 5;
    /** Sky / unknown colour — same as Landscaper's SKY constant. */
    private static final int SKY = 0x87C5AB;

    /** chunkKey = ((long)cx << 32) | (cz & 0xFFFFFFFFL) → MapImage. */
    private final ConcurrentHashMap<Long, MapImage> chunks = new ConcurrentHashMap<>();
    /** Flipped to true on any put — minimap reads it to know "redraw needed". */
    private final AtomicBoolean modified = new AtomicBoolean(false);
    /**
     * Chunk keys that arrived (or changed) since the last
     * {@link #consumeChangedKeys}. The minimap drains this each tick to
     * invalidate exactly the rendered tiles whose underlying data changed,
     * avoiding the "tile permanently shows sky-colour after the chunk
     * finally arrives" failure mode.
     */
    private final Set<Long> changedKeys = ConcurrentHashMap.newKeySet();

    /** Replaces (or installs) the tile for one chunk. */
    void put(int cx, int cz, MapImage img) {
        if (img == null || img.packedIndices == null || img.packedIndices.length == 0) {
            return;
        }
        long k = key(cx, cz);
        chunks.put(k, img);
        changedKeys.add(k);
        modified.set(true);
    }

    /** Replaces tiles in bulk from one {@code UpdateWorldMap} packet. */
    void put(MapChunk[] mapChunks) {
        if (mapChunks == null) return;
        for (MapChunk c : mapChunks) {
            if (c == null) continue;
            if (c.image == null) {
                // Server-initiated removal — we keep whatever we had; the
                // server is signalling "no longer current", but our last
                // snapshot remains the best guess until something replaces it.
                continue;
            }
            put(c.chunkX, c.chunkZ, c.image);
        }
    }

    /**
     * Resolves the colour of world-block {@code (wx, wz)} as a 24-bit
     * {@code 0xRRGGBB} value, or {@link #SKY} when the chunk has not arrived
     * (yet). Cheap — one map lookup and a constant-time pixel decode.
     */
    int getColorAt(int wx, int wz) {
        int cx = wx >> CHUNK_SHIFT;
        int cz = wz >> CHUNK_SHIFT;
        MapImage img = chunks.get(key(cx, cz));
        if (img == null) return SKY;

        // The minimap tile is W×H pixels covering 32×32 blocks; we map a
        // world block to the corresponding pixel by scaling. Most tiles
        // are 32×32 (1 px = 1 block), but server-side scaling is allowed.
        int stride = img.width > 0 ? img.width : guessStride(img);
        int localBlockX = wx & (CHUNK_SIZE - 1);
        int localBlockZ = wz & (CHUNK_SIZE - 1);
        int localPxX = localBlockX * stride / CHUNK_SIZE;
        int localPxZ = localBlockZ * stride / CHUNK_SIZE;
        int idx = localPxZ * stride + localPxX;
        if (idx < 0 || idx >= img.width * img.height) {
            return SKY;
        }
        int rgba = getPixelColor(img, idx);
        if (rgba == 0) return SKY;
        // Palette entries are 0xRRGGBBAA — drop alpha, return 0xRRGGBB.
        return ((rgba >> 24) & 0xFF) << 16
                | ((rgba >> 16) & 0xFF) << 8
                | ((rgba >> 8) & 0xFF);
    }

    /** Returns true exactly once after each {@link #put} burst. */
    boolean consumeModified() {
        return modified.getAndSet(false);
    }

    /**
     * Returns the chunk keys that received new data since the last call and
     * clears the internal set. Empty if no changes since last consume. The
     * minimap uses this to invalidate exactly the affected tiles, leaving
     * unchanged tiles cached.
     */
    Set<Long> consumeChangedKeys() {
        Set<Long> snapshot = Set.copyOf(changedKeys);
        changedKeys.clear();
        return snapshot;
    }

    /** Decompose a chunk key produced by this cache into (cx, cz). */
    static int chunkXOf(long key) { return (int) (key >> 32); }
    static int chunkZOf(long key) { return (int) (long) key; }

    void clear() {
        chunks.clear();
        modified.set(true);
    }

    // ------------------------------------------------------------------
    // Internals — pixel decoder + key packing
    // ------------------------------------------------------------------

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** Reconstructs stride for an image whose {@code width} field is zero. */
    private static int guessStride(MapImage img) {
        int totalPixels = img.packedIndices.length * 8 / Math.max(1, img.bitsPerIndex & 0xFF);
        return (int) Math.round(Math.sqrt(totalPixels));
    }

    /** Direct port of Landscaper's pixel decoder — bit-packed palette index lookup. */
    private static int getPixelColor(MapImage img, int idx) {
        int bits = img.bitsPerIndex & 0xFF;
        if (bits == 0 || img.palette == null || img.palette.length == 0) {
            return 0;
        }
        int bitOffset = idx * bits;
        int byteIndex = bitOffset >>> 3;
        int bitShift = bitOffset & 7;
        int mask = (1 << bits) - 1;
        int raw = (img.packedIndices[byteIndex] & 0xFF) >>> bitShift;
        if (bitShift + bits > 8 && byteIndex + 1 < img.packedIndices.length) {
            raw |= (img.packedIndices[byteIndex + 1] & 0xFF) << (8 - bitShift);
        }
        int paletteIdx = raw & mask;
        if (paletteIdx >= img.palette.length) return 0;
        return img.palette[paletteIdx];
    }
}
