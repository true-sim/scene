package com.truesim.scene.assets;

import com.truesim.annotations.AllowAllocation;
import java.util.HexFormat;

/**
 * An asset's id: 128 bits, written as 32 hex digits. Either derived from the asset's path ({@link AssetPath#id()},
 * for assets addressed by path through {@link PathAssets}), so stable while the path is, with redirects keeping old
 * ids loading after a rename; or assigned once by whatever made the asset, and kept however it is stored.
 */
public record AssetId(long high, long low) {

    /** The id written as 32 hex digits, as {@link #toString()} gives it. */
    public static AssetId parse(String hex) {
        if (hex.length() != 32) {
            throw new IllegalArgumentException("An asset id is 32 hex digits: " + hex);
        }
        return new AssetId(HexFormat.fromHexDigitsToLong(hex, 0, 16), HexFormat.fromHexDigitsToLong(hex, 16, 32));
    }

    @Override
    @AllowAllocation("formatting")
    public String toString() {
        return HexFormat.of().toHexDigits(high) + HexFormat.of().toHexDigits(low);
    }
}
