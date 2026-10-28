package com.truesim.scene.assets;

import com.truesim.annotations.AllowAllocation;
import java.util.HexFormat;

/**
 * An asset's stable id: 128 bits assigned once, at import, and kept when the asset is renamed or moved, so references
 * never break and never depend on paths. Written as 32 hex digits.
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
