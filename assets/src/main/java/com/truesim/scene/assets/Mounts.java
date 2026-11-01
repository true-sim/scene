package com.truesim.scene.assets;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The mounts of {@link PathAssets}: per mount name, its locations in priority order, and redirects from old paths to
 * new ones. Mods, patches and DLC override a game's files by adding a location first; a renamed file keeps old
 * references loading through a redirect.
 *
 * <pre>{@code
 * mounts.add("game", PathSource.folder(gameDir));
 * mounts.addFirst("game", PathSource.folder(modDir));             // the mod's files win, path by path
 * mounts.redirect("game://models/rock.gltf", "game://models/rocks/granite.gltf");
 * }</pre>
 *
 * <p>Set up before loading; opening runs in the background and is thread-safe.
 */
@AllowAllocation("set up once")
public final class Mounts {

    private final Map<String, List<PathSource>> locations = new ConcurrentHashMap<>();
    private final Map<AssetPath, AssetPath> redirects = new ConcurrentHashMap<>();

    Mounts() {
    }

    /** Adds {@code location} to {@code mount}, after its others: it has what they do not. */
    public Mounts add(String mount, PathSource location) {
        locations(mount).add(Objects.requireNonNull(location, "location"));
        return this;
    }

    /** Adds {@code location} to {@code mount}, before its others: its files override theirs. */
    public Mounts addFirst(String mount, PathSource location) {
        locations(mount).addFirst(Objects.requireNonNull(location, "location"));
        return this;
    }

    private List<PathSource> locations(String mount) {
        new AssetPath(mount, "check"); // throws on a mount name that is not one
        return locations.computeIfAbsent(mount, m -> new CopyOnWriteArrayList<>());
    }

    /** Whether {@code mount} has a location. */
    public boolean has(String mount) {
        return locations.containsKey(mount);
    }

    /**
     * Loads {@code from}'s asset from {@code to} instead, and makes {@link PathAssets#canonical} name {@code to}'s id
     * for {@code from}'s: after a rename, so saved references keep working. Throws if it would make a cycle.
     */
    public Mounts redirect(String from, String to) {
        AssetPath source = AssetPath.parse(from).file();
        AssetPath target = AssetPath.parse(to).file();
        if (redirected(target).equals(source)) {
            throw new IllegalArgumentException("A redirect cycle: " + source + " -> " + target);
        }
        redirects.put(source, target);
        return this;
    }

    /**
     * Where {@code path} leads after every redirect: itself if none applies. Redirects are of files: a fragment stays
     * with the file it names an asset in.
     */
    public AssetPath redirected(AssetPath path) {
        AssetPath file = path.file();
        AssetPath at = file;
        for (AssetPath next = redirects.get(at); next != null; next = redirects.get(at)) {
            at = next;
        }
        return at == file ? path : at.fragment(path.fragment());
    }

    /**
     * Opens {@code path}, after redirects, from the first of its mount's locations that has it; {@code null} if none
     * does or the mount has no location.
     */
    public InputStream open(AssetPath path) throws IOException {
        AssetPath at = redirected(path);
        for (PathSource location : locations.getOrDefault(at.mount(), List.of())) {
            InputStream in = location.open(at.path());
            if (in != null) {
                return in;
            }
        }
        return null;
    }
}
