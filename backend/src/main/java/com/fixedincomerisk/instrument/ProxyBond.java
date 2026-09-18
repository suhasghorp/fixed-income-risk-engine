package com.fixedincomerisk.instrument;

/**
 * A synthetic fixed-coupon bond that stands in for a Treasury future's cheapest-to-deliver bond, with
 * the conversion factor that scales its price into the future's.
 *
 * @param conversionFactor the CME conversion factor: the bond's price per unit at a 6% yield
 */
public record ProxyBond(TreasuryBond bond, double conversionFactor) {

    public ProxyBond {
        if (!(conversionFactor > 0)) {
            throw new IllegalArgumentException("Conversion factor must be positive for " + bond.id());
        }
    }
}
