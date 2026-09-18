package com.fixedincomerisk.credit;

/**
 * A Print or Quote for an issuer's bonds: the Latent Spread plus noise, as a decimal spread.
 */
public record CreditObservation(String issuerId, Kind kind, double spread) {

    public enum Kind {
        /** A reported trade: less noisy. */
        PRINT,
        /** A dealer quote: noisier than a Print. */
        QUOTE
    }
}
