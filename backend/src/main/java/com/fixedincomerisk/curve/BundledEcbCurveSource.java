package com.fixedincomerisk.curve;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Curve Source backed by a real, dated ECB spot-rate snapshot bundled with the application. */
public final class BundledEcbCurveSource implements CurveSource {

    private static final String RESOURCE = "/curves/bundled-eur-zero-curve.csv";

    private final EcbTenors tenors;

    public BundledEcbCurveSource(EcbTenors tenors) {
        this.tenors = tenors;
    }

    /** The ECB euro area yield curve is the EUR curve. */
    @Override
    public String currency() {
        return "EUR";
    }

    @Override
    public CurveSnapshot load() {
        InputStream in = BundledEcbCurveSource.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Bundled EUR curve snapshot missing: " + RESOURCE);
        }
        ZeroCurve curve = ZeroCurveCsv.parse(new InputStreamReader(in, StandardCharsets.UTF_8), tenors);
        return CurveSnapshot.of(curve, CurveSourceKind.BUNDLED);
    }
}
