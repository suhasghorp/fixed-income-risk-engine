package com.fixedincomerisk.market;

import java.util.Locale;

/**
 * One (expiry, tenor) coordinate on a currency's volatility surface, at which a Normal Volatility is
 * quoted. Only the points the Book needs exist: there is no grid and no interpolation between them, so a
 * Swaption prices from exactly one point or from none at all.
 *
 * @param currency ISO currency code, e.g. "USD"
 * @param point    the coordinate as it is quoted, e.g. "1Mx5Y": option expiry, then underlying tenor
 */
public record SurfacePoint(String currency, String point) {

    public SurfacePoint {
        currency = currency.trim().toUpperCase(Locale.ROOT);
        point = point.trim();
        if (currency.length() != 3) {
            throw new IllegalArgumentException("A Surface Point's currency is a three-letter code, got " + currency);
        }
        if (!point.matches("(?i)\\d+[DWMY]x\\d+[DWMY]")) {
            throw new IllegalArgumentException("A Surface Point is <expiry>x<tenor>, e.g. 1Mx5Y, got '" + point + "'");
        }
    }

    /** Parses {@code <CCY> <expiry>x<tenor>}, e.g. {@code USD 1Mx5Y}, as the properties spell it. */
    public static SurfacePoint parse(String label) {
        String[] parts = label.trim().split("\\s+");
        if (parts.length != 2) {
            throw new IllegalArgumentException("A Surface Point is a currency then a point, e.g. 'USD 1Mx5Y', got '"
                    + label + "'");
        }
        return new SurfacePoint(parts[0], parts[1]);
    }

    /** The point as configuration and messages spell it, e.g. {@code USD 1Mx5Y}. */
    public String label() {
        return currency + " " + point;
    }

    /** The Risk Factor this point's Normal Volatility is identified by. */
    public RiskFactorId volFactor() {
        return RiskFactorId.normalVol(currency, point);
    }

    /** The correlated-driver name for this point, as {@code risk.correlation.factors} spells it. */
    public String volShockFactor() {
        return "normalVol." + currency + "." + point;
    }

    /** The prefix its process parameters are configured under, e.g. {@code risk.vol.USD.1Mx5Y.} */
    public String propertyPrefix() {
        return "risk.vol." + currency + "." + point + ".";
    }

    @Override
    public String toString() {
        return label();
    }
}
