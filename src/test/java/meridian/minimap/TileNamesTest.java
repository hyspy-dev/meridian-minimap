package meridian.minimap;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import meridian.core.api.ClientAssets;
import meridian.core.api.WorldMap;
import org.slf4j.LoggerFactory;

/**
 * Tiles travel under the name core gives a {@code UI/Custom} picture and come back as the name
 * markup uses. A plain test class (no framework on hand offline): every {@code test*} method runs,
 * an {@link AssertionError} fails it.
 */
public class TileNamesTest {

    /** A client that takes whatever it is given, under the name asked for. */
    static final class Assets implements ClientAssets {
        final List<String> pushed = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        final Set<Integer> contents = new HashSet<>();

        @Override public Optional<String> push(String name, byte[] bytes) {
            pushed.add(name);
            contents.add(java.util.Arrays.hashCode(bytes));
            return Optional.of(name);
        }
        @Override public Optional<String> push(byte[] bytes, String extension) { return Optional.empty(); }
        @Override public Optional<String> replace(String name, byte[] bytes) { return Optional.of(name); }
        @Override public void remove(String name) { removed.add(name); }
        @Override public void provideAtConnect(String name, byte[] bytes) { }
        @Override public void withdrawAtConnect(String name) { }
        @Override public boolean deliveredAtConnect(String name) { return false; }
        @Override public void requestRebuild() { }
        @Override public boolean isPushed(byte[] bytes) { return contents.contains(java.util.Arrays.hashCode(bytes)); }
    }

    public void testTilesGoOutAndComeBackByCoresNames() {
        Assets assets = new Assets();
        MinimapTiles tiles = new MinimapTiles(map(), assets, LoggerFactory.getLogger("test"));
        check("mt_placeholder.png".equals(tiles.pushPlaceholder()), "the placeholder's reference");
        String near = tiles.ensureTile(0, 0);
        check("mt_0.png".equals(near), "a tile's reference: " + near);
        check(assets.pushed.contains("UI/Custom/mt_0@2x.png"), "pushed as " + assets.pushed);
        tiles.ensureTile(9, 9);
        tiles.evictOutsideWindow(-1, 1, -1, 1);
        check(assets.removed.equals(List.of("UI/Custom/mt_1@2x.png")), "taken back: " + assets.removed);
    }

    private static WorldMap map() {
        return (WorldMap) Proxy.newProxyInstance(TileNamesTest.class.getClassLoader(), new Class<?>[] {WorldMap.class},
                (proxy, method, args) -> {
                    Class<?> t = method.getReturnType();
                    if (t == int.class) return -1;
                    if (t == boolean.class) return false;
                    if (t == long.class) return 0L;
                    if (t == Optional.class) return Optional.empty();
                    return null;
                });
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
