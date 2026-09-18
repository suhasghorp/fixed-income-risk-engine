package com.fixedincomerisk.curve;

import java.util.Arrays;
import java.util.Locale;

/** Which Curve Source a session starts from, as configured by {@code risk.curve.source}. */
public enum CurveSourceChoice {

    /** Today's Treasury curve: live if reachable, else the last cached fetch, else the bundled snapshot. */
    TREASURY,

    /** Always the bundled snapshot, with no network or cache access: a reproducible run. */
    BUNDLED;

    public static CurveSourceChoice parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid risk.curve.source '" + value + "'; expected one of "
                    + Arrays.stream(values()).map(v -> v.name().toLowerCase(Locale.ROOT)).toList(), e);
        }
    }
}
