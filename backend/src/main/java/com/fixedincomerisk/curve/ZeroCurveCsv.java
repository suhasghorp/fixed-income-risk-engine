package com.fixedincomerisk.curve;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The compact format a fetched zero curve is cached and bundled in: a comment header, a row of tenor
 * labels, and one row of continuously compounded spot rates as percentages, dated in ISO form. The ECB's
 * own {@code csvdata} response carries forty columns of metadata per observation and is parsed by
 * {@link EcbSpotRatesCsv}; this is what the engine writes back out.
 */
public final class ZeroCurveCsv {

    /** The ECB publishes ten decimals; keep them all so a parsed curve survives a round trip. */
    private static final int PUBLISHED_DECIMALS = 10;

    private ZeroCurveCsv() {
    }

    public static ZeroCurve parse(Reader reader, EcbTenors tenors) {
        try (BufferedReader lines = new BufferedReader(reader)) {
            String header = null;
            String row = null;
            String line;
            while ((line = lines.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (header == null) {
                    header = trimmed;
                } else {
                    row = trimmed;
                    break;
                }
            }
            if (header == null || row == null) {
                throw new IllegalArgumentException("Zero curve CSV has no data row");
            }
            String[] labels = header.split(",");
            String[] values = row.split(",");
            List<ZeroPoint> points = new ArrayList<>();
            for (int i = 1; i < labels.length && i < values.length; i++) {
                String value = values[i].trim();
                if (value.isEmpty()) {
                    continue;
                }
                String label = labels[i].trim();
                points.add(new ZeroPoint(label, years(label, tenors), Double.parseDouble(value) / 100.0));
            }
            return new ZeroCurve(LocalDate.parse(values[0].trim()), points);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the curve so {@link #parse} reads it back unchanged. */
    public static String format(ZeroCurve curve) {
        StringBuilder header = new StringBuilder("Date");
        StringBuilder row = new StringBuilder(curve.curveDate().toString());
        for (ZeroPoint point : curve.points()) {
            header.append(',').append(point.tenor());
            row.append(',').append(BigDecimal.valueOf(point.zeroRate() * 100)
                    .setScale(PUBLISHED_DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        }
        return "# ECB euro area AAA-rated central government bond spot rates, continuously compounded, % p.a.\n"
                + header + "\n" + row + "\n";
    }

    private static double years(String label, EcbTenors tenors) {
        for (EcbTenors.Tenor tenor : tenors.all()) {
            if (tenor.label().equals(label)) {
                return tenor.years();
            }
        }
        throw new IllegalArgumentException("Unrecognised zero curve tenor label: " + label);
    }
}
