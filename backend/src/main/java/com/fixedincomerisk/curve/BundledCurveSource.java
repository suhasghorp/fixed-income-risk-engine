package com.fixedincomerisk.curve;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Curve Source backed by a real Treasury par curve snapshot bundled with the application. */
public final class BundledCurveSource implements CurveSource {

    private static final String RESOURCE = "/curves/bundled-par-curve.csv";

    @Override
    public CurveSnapshot load() {
        InputStream in = BundledCurveSource.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Bundled curve snapshot missing: " + RESOURCE);
        }
        ParCurve curve = TreasuryParCurveCsv.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        return new CurveSnapshot(curve, CurveSourceKind.BUNDLED);
    }
}
