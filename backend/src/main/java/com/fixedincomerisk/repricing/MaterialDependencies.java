package com.fixedincomerisk.repricing;

import com.fixedincomerisk.market.FactorType;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.risk.CurveSensitivities;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Narrows an Instrument's declared curve factors to the Pillars where it has material exposure.
 *
 * <p>Every coupon bond has a cash flow near the short end, so declaring every Pillar a cash flow touches
 * would make every bond depend on the fastest-moving short Pillars, and the whole Book would reprice
 * together. Keeping only Pillars that carry a material share of the Instrument's Bucketed DV01 lets
 * short-dated Instruments reprice more often than long-dated ones, as the model implies. The
 * exposure left out is immaterial by construction.
 */
public final class MaterialDependencies {

    private MaterialDependencies() {
    }

    /**
     * @param declared the Instrument's declared Risk Factors
     * @param pillars  the Pillars, in the order of {@code perUnit}'s buckets
     * @param perUnit  the Instrument's current per-unit sensitivities
     * @param minShare the smallest share of the sum of absolute buckets a Pillar must carry
     */
    public static Set<RiskFactorId> of(Set<RiskFactorId> declared, List<Pillar> pillars, CurveSensitivities perUnit,
                                       double minShare) {
        double[] buckets = perUnit.bucketedDv01();
        double total = Arrays.stream(buckets).map(Math::abs).sum();
        Set<RiskFactorId> material = new LinkedHashSet<>();
        for (RiskFactorId factor : declared) {
            if (factor.type() != FactorType.PILLAR_ZERO_RATE) {
                material.add(factor);
                continue;
            }
            int bucket = indexOf(pillars, factor.name());
            if (total > 0 && Math.abs(buckets[bucket]) / total >= minShare) {
                material.add(factor);
            }
        }
        return material;
    }

    private static int indexOf(List<Pillar> pillars, String label) {
        for (int i = 0; i < pillars.size(); i++) {
            if (pillars.get(i).label().equals(label)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown Pillar " + label);
    }
}
