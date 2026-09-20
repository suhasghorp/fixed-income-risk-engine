package com.fixedincomerisk.curve;

/** What a publisher's quoted rates are: the two are not interchangeable and must never be mislabelled. */
public enum CurveQuoteKind {

    /** Par yields, which have to be bootstrapped before they discount anything. Treasury publishes these. */
    PAR_YIELD,

    /** Continuously compounded spot rates, already bootstrapped by the publisher. The ECB publishes these. */
    ZERO_RATE
}
