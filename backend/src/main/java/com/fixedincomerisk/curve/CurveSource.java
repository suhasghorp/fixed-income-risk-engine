package com.fixedincomerisk.curve;

/** Port that supplies one currency's real curve, and names the currency it belongs to. */
public interface CurveSource {

    /** The ISO code of the currency this source's curve discounts. */
    String currency();

    CurveSnapshot load();
}
