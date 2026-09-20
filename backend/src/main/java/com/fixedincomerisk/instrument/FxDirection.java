package com.fixedincomerisk.instrument;

/**
 * Which side of a currency pair the holder has bought. A pair is written base first, quote second, and
 * its rate is quote units per base unit — so EURUSD 1.146 is 1.146 USD per EUR, and USDKRW 1388.10 is
 * 1388.10 KRW per USD.
 *
 * <p>Named for the <em>base</em> currency rather than the "foreign" one on purpose. USDKRW's base is the
 * dollar, which is also the Reporting Currency, so calling it foreign would be wrong in exactly the case
 * the engine has to get right.
 */
public enum FxDirection {

    /** Long the base currency, short the quote: gains when the pair's rate rises. */
    BUY_BASE(1),

    /** Short the base currency, long the quote: gains when the pair's rate falls. */
    SELL_BASE(-1);

    final int sign;

    FxDirection(int sign) {
        this.sign = sign;
    }

    public int sign() {
        return sign;
    }
}
