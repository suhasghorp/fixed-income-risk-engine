package com.fixedincomerisk.curve;

import java.util.List;
import org.apache.commons.math3.analysis.solvers.BrentSolver;

/**
 * Bootstraps a par yield curve into a {@link DiscountCurve}.
 *
 * <p>Par instruments follow Treasury's semi-annual bond-equivalent convention: a tenor of at most
 * six months pays (1 + y·T) at maturity; longer tenors pay y/2 semi-annually, counted back from
 * maturity, plus principal. Because shape-preserving interpolation is local but not strictly
 * one-sided, knots are re-solved against the full curve until they stop moving, so every input
 * par instrument reprices to par on the final curve.
 */
public final class CurveBootstrapper {

    private static final double SHORT_TENOR_LIMIT = 0.5 + 1e-9;
    private static final int MAX_SWEEPS = 200;
    private static final double TOLERANCE = 1e-14;

    private CurveBootstrapper() {
    }

    public static DiscountCurve bootstrap(ParCurve parCurve) {
        List<ParPoint> points = parCurve.points();
        int n = points.size();
        double[] times = new double[n];
        double[] lnP = new double[n];
        for (int i = 0; i < n; i++) {
            times[i] = points.get(i).years();
            lnP[i] = -points.get(i).parYield() * times[i];
        }

        BrentSolver solver = new BrentSolver(1e-16, 1e-15);
        for (int sweep = 0; sweep < MAX_SWEEPS; sweep++) {
            double maxChange = 0;
            for (int i = 0; i < n; i++) {
                ParPoint point = points.get(i);
                int knot = i;
                double previous = lnP[knot];
                double solved = solver.solve(1_000, candidate -> {
                    lnP[knot] = candidate;
                    return parInstrumentValue(point, DiscountCurve.fromLogDiscountFactors(times, lnP)) - 1.0;
                }, -0.5 * times[knot], 0.05 * times[knot], previous);
                lnP[knot] = solved;
                maxChange = Math.max(maxChange, Math.abs(solved - previous));
            }
            if (maxChange < TOLERANCE) {
                break;
            }
        }
        return DiscountCurve.fromLogDiscountFactors(times, lnP);
    }

    /** Value per unit face of the par instrument for {@code point}; equals 1 on a correct curve. */
    public static double parInstrumentValue(ParPoint point, DiscountCurve curve) {
        double maturity = point.years();
        double y = point.parYield();
        if (maturity <= SHORT_TENOR_LIMIT) {
            return (1 + y * maturity) * curve.discountFactor(maturity);
        }
        double value = curve.discountFactor(maturity);
        for (double t = maturity; t > 1e-9; t -= 0.5) {
            value += y / 2 * curve.discountFactor(t);
        }
        return value;
    }
}
