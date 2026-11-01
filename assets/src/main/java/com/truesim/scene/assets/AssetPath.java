package com.truesim.scene.assets;

import com.truesim.annotations.AllowAllocation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Where a path-addressed asset is: a mount and a path in it, written {@code mount://folder/file.ext}. Paths are
 * normalised when parsed ({@code \} becomes {@code /}, empty and {@code .} segments go, {@code ..} goes up and never
 * above the mount), and compared case-sensitively, as written: on any file system, a path names one asset. The path is
 * the asset's identity: {@link #id()} derives its {@link AssetId} from it.
 *
 * <pre>{@code
 * AssetPath rock = AssetPath.parse("game://models/rock.gltf");
 * AssetPath buffer = rock.resolve("rock.bin");          // game://models/rock.bin: a side resource next to it
 * AssetId id = rock.id();                               // the same on every run and machine
 * AssetPath body = rock.fragment("mesh/0");             // game://models/rock.gltf#mesh/0: one asset in the file
 * }</pre>
 *
 * <p>A fragment ({@code #mesh/0}) names one asset of several in a file: a glTF file's meshes, materials and images,
 * or how to read it ({@code #linear}). The file is the same; the asset, its id and its importer's reading differ.
 *
 * @param mount the mount's name: lower-case letters, digits, {@code _ . -}
 * @param path the normalised path in the mount, without a leading {@code /}
 * @param fragment the asset in the file, without the {@code #}; empty for the file as a whole
 */
public record AssetPath(String mount, String path, String fragment) {

    private static final Pattern MOUNT = Pattern.compile("[a-z0-9][a-z0-9_.-]*");
    private static final String SEPARATOR = "://";

    public AssetPath {
        if (fragment.indexOf('#') >= 0) {
            throw new IllegalArgumentException("A fragment holds no #: '" + fragment + "'");
        }
        if (!MOUNT.matcher(mount).matches()) {
            throw new IllegalArgumentException("A mount is lower-case letters, digits, _ . -: '" + mount + "'");
        }
        if (!path.equals(normalise(path, path))) {
            throw new IllegalArgumentException("Not a normalised path: '" + path + "'");
        }
    }

    /** The whole file {@code path} in {@code mount}: no fragment. */
    public AssetPath(String mount, String path) {
        this(mount, path, "");
    }

    /** {@code text}, {@code mount://path} or {@code mount://path#fragment}, normalised. */
    @AllowAllocation("parsing a path")
    public static AssetPath parse(String text) {
        int separator = text.indexOf(SEPARATOR);
        if (separator < 0) {
            throw new IllegalArgumentException("An asset path is mount://path: '" + text + "'");
        }
        int hash = text.indexOf('#', separator);
        String path = hash < 0 ? text.substring(separator + SEPARATOR.length())
            : text.substring(separator + SEPARATOR.length(), hash);
        return new AssetPath(text.substring(0, separator), normalise(path, text),
            hash < 0 ? "" : text.substring(hash + 1));
    }

    /** The same file with {@code name} as its fragment: one asset of several in it. */
    @AllowAllocation("making a path")
    public AssetPath fragment(String name) {
        return new AssetPath(mount, path, name);
    }

    /** The file as a whole: this path without its fragment. */
    @AllowAllocation("making a path")
    public AssetPath file() {
        return fragment.isEmpty() ? this : new AssetPath(mount, path);
    }

    /**
     * The path {@code relative} names from this one's folder, as a format names its side resources (a glTF file's
     * buffers): {@code rock.bin}, {@code ../textures/rock.png}; a leading {@code /} starts at the mount's root. A
     * fragment in {@code relative} is kept ({@code rock.png#linear}); this path's own is not.
     */
    @AllowAllocation("resolving a path")
    public AssetPath resolve(String relative) {
        int hash = relative.indexOf('#');
        String fragmentOf = hash < 0 ? "" : relative.substring(hash + 1);
        String unified = (hash < 0 ? relative : relative.substring(0, hash)).replace('\\', '/');
        if (unified.startsWith("/")) {
            return new AssetPath(mount, normalise(unified, relative), fragmentOf);
        }
        int slash = path.lastIndexOf('/');
        String folder = slash < 0 ? "" : path.substring(0, slash + 1);
        return new AssetPath(mount, normalise(folder + unified, relative), fragmentOf);
    }

    /** The file name's extension, lower-case, without the dot: {@code gltf}; empty if it has none. */
    @AllowAllocation("reading the extension")
    public String extension() {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The asset's id: a name-based UUID (version 3) of {@code path:} and this path as written, fragment and all. */
    @AllowAllocation("hashing a path")
    public AssetId id() {
        UUID uuid = UUID.nameUUIDFromBytes(("path:" + this).getBytes(StandardCharsets.UTF_8));
        return new AssetId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    @AllowAllocation("parsing a path")
    private static String normalise(String path, String original) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("The path goes above its mount: '" + original + "'");
                }
                segments.removeLast();
            } else {
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("The path names no file: '" + original + "'");
        }
        return String.join("/", segments);
    }

    @Override
    @AllowAllocation("formatting")
    public String toString() {
        return fragment.isEmpty() ? mount + SEPARATOR + path : mount + SEPARATOR + path + "#" + fragment;
    }
}
