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
 * }</pre>
 *
 * @param mount the mount's name: lower-case letters, digits, {@code _ . -}
 * @param path the normalised path in the mount, without a leading {@code /}
 */
public record AssetPath(String mount, String path) {

    private static final Pattern MOUNT = Pattern.compile("[a-z0-9][a-z0-9_.-]*");
    private static final String SEPARATOR = "://";

    public AssetPath {
        if (!MOUNT.matcher(mount).matches()) {
            throw new IllegalArgumentException("A mount is lower-case letters, digits, _ . -: '" + mount + "'");
        }
        if (!path.equals(normalise(path, path))) {
            throw new IllegalArgumentException("Not a normalised path: '" + path + "'");
        }
    }

    /** {@code text}, {@code mount://path}, normalised. */
    @AllowAllocation("parsing a path")
    public static AssetPath parse(String text) {
        int separator = text.indexOf(SEPARATOR);
        if (separator < 0) {
            throw new IllegalArgumentException("An asset path is mount://path: '" + text + "'");
        }
        return new AssetPath(text.substring(0, separator), normalise(text.substring(separator + SEPARATOR.length()),
            text));
    }

    /**
     * The path {@code relative} names from this one's folder, as a format names its side resources (a glTF file's
     * buffers): {@code rock.bin}, {@code ../textures/rock.png}; a leading {@code /} starts at the mount's root.
     */
    @AllowAllocation("resolving a path")
    public AssetPath resolve(String relative) {
        String unified = relative.replace('\\', '/');
        if (unified.startsWith("/")) {
            return new AssetPath(mount, normalise(unified, relative));
        }
        int slash = path.lastIndexOf('/');
        String folder = slash < 0 ? "" : path.substring(0, slash + 1);
        return new AssetPath(mount, normalise(folder + unified, relative));
    }

    /** The file name's extension, lower-case, without the dot: {@code gltf}; empty if it has none. */
    @AllowAllocation("reading the extension")
    public String extension() {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The asset's id: a name-based UUID (version 3) of {@code path:} and this path as written. */
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
        return mount + SEPARATOR + path;
    }
}
