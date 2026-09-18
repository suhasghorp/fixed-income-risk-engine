package com.fixedincomerisk.market;

import java.util.Objects;

/**
 * Identifies one Risk Factor. Every identifier carries a currency from the start, so a second currency
 * needs no new identifier scheme.
 *
 * @param currency ISO currency code, e.g. "USD"
 * @param name     distinguishes factors of the same type and currency, e.g. the Pillar label; empty when
 *                 there is only one
 */
public record RiskFactorId(String currency, FactorType type, String name) {

    public RiskFactorId {
        Objects.requireNonNull(currency);
        Objects.requireNonNull(type);
        Objects.requireNonNull(name);
    }

    public static RiskFactorId pillarZeroRate(String currency, Pillar pillar) {
        return new RiskFactorId(currency, FactorType.PILLAR_ZERO_RATE, pillar.label());
    }

    public static RiskFactorId mark(String currency, String issuerId) {
        return new RiskFactorId(currency, FactorType.MARK, issuerId);
    }

    public static RiskFactorId systemic(String currency) {
        return new RiskFactorId(currency, FactorType.SYSTEMIC, "");
    }

    /** @param ratingBucket the bucket's label, e.g. "BBB Industrials" */
    public static RiskFactorId sector(String currency, String ratingBucket) {
        return new RiskFactorId(currency, FactorType.SECTOR, ratingBucket);
    }

    public static RiskFactorId rating(String currency, String issuerId) {
        return new RiskFactorId(currency, FactorType.RATING, issuerId);
    }

    public static RiskFactorId basis(String currency, String futuresContract) {
        return new RiskFactorId(currency, FactorType.BASIS, futuresContract);
    }

    public static RiskFactorId proxyBond(String currency, String futuresContract) {
        return new RiskFactorId(currency, FactorType.PROXY_BOND, futuresContract);
    }

    public static RiskFactorId valuationDate(String currency) {
        return new RiskFactorId(currency, FactorType.VALUATION_DATE, "");
    }

    @Override
    public String toString() {
        return name.isEmpty() ? currency + ":" + type : currency + ":" + type + ":" + name;
    }
}
