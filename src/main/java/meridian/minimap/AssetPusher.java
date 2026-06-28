package meridian.minimap;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import meridian.api.session.ProxySession;
import meridian.protocol.Asset;
import meridian.protocol.packets.setup.AssetFinalize;
import meridian.protocol.packets.setup.AssetInitialize;
import meridian.protocol.packets.setup.AssetPart;

/**
 * Ships a binary blob to the client as an asset, addressable from later
 * {@code CustomHud}/{@code CustomPage} markup via {@code TexturePath: "<name>"}
 * or by passing the name to {@code commandBuilder.append("name")} for {@code .ui}
 * documents.
 *
 * <p>Mirrors the server's pack-streaming flow ({@code CommonAssetModule}):
 * SHA-256 of the bytes is the asset's content-addressable id; bytes split into
 * ≤2.5 MB parts; sequence is {@link AssetInitialize} → N × {@link AssetPart}
 * → {@link AssetFinalize}.
 */
final class AssetPusher {

    /** Server uses 2.5 MB chunks ({@code ArrayUtil.split(bytes, 2621440)}). */
    private static final int PART_SIZE = 2_621_440;

    private AssetPusher() {}

    /**
     * Pushes {@code bytes} to the client under the asset name {@code name}.
     * Returns the SHA-256 hex hash assigned to the asset.
     */
    /**
     * The content-addressable SHA-256 hex id {@link #push} assigns to {@code bytes}.
     * Exposed so callers can dedup by content before pushing — the client rejects a
     * second {@link AssetInitialize} for a hash whose download already started.
     */
    static String hash(byte[] bytes) {
        return sha256Hex(bytes);
    }

    static String push(ProxySession session, String name, byte[] bytes) {
        String hash = sha256Hex(bytes);
        Asset asset = new Asset(hash, name);

        session.sendToClient(new AssetInitialize(asset, bytes.length));
        for (int offset = 0; offset < bytes.length; offset += PART_SIZE) {
            int end = Math.min(offset + PART_SIZE, bytes.length);
            byte[] part = new byte[end - offset];
            System.arraycopy(bytes, offset, part, 0, part.length);
            session.sendToClient(new AssetPart(part));
        }
        session.sendToClient(new AssetFinalize());
        return hash;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
