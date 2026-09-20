package com.fixedincomerisk.curve;

/** Where one currency's starting curve came from. Reported per currency. */
public enum CurveSourceKind {
    LIVE,
    CACHED,
    BUNDLED
}
