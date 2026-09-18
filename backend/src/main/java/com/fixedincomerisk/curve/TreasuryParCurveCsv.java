package com.fixedincomerisk.curve;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the CSV format published by Treasury ("Daily Treasury Par Yield Curve Rates"):
 * a header of tenor labels ("1 Mo", "1.5 Month", "2 Yr", ...) followed by one row per date,
 * most recent first. The first data row is used.
 */
public final class TreasuryParCurveCsv {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    /** Treasury publishes two decimals; keep more so any parsed value survives a round trip. */
    private static final int PUBLISHED_DECIMALS = 6;
    private static final Pattern TENOR = Pattern.compile("([0-9.]+)\\s*(Mo|Month|Yr|Year)s?");

    private TreasuryParCurveCsv() {
    }

    public static ParCurve parse(Reader reader) {
        try (BufferedReader lines = new BufferedReader(reader)) {
            String header = lines.readLine();
            String row = lines.readLine();
            if (header == null || row == null) {
                throw new IllegalArgumentException("Treasury par curve CSV has no data row");
            }
            String[] labels = header.split(",");
            String[] values = row.split(",");
            List<ParPoint> points = new ArrayList<>();
            for (int i = 1; i < labels.length && i < values.length; i++) {
                String value = values[i].trim();
                if (value.isEmpty()) {
                    continue;
                }
                String label = labels[i].replace("\"", "").trim();
                points.add(new ParPoint(shortLabel(label), years(label), Double.parseDouble(value) / 100.0));
            }
            return new ParCurve(LocalDate.parse(values[0].trim(), DATE), points);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes a single-row CSV in the same published format, so {@link #parse} reads it back. */
    public static String format(ParCurve curve) {
        StringBuilder header = new StringBuilder("Date");
        StringBuilder row = new StringBuilder(curve.curveDate().format(DATE));
        for (ParPoint point : curve.points()) {
            header.append(",\"").append(publishedLabel(point)).append('"');
            row.append(',').append(BigDecimal.valueOf(point.parYield() * 100)
                    .setScale(PUBLISHED_DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        }
        return header + "\n" + row + "\n";
    }

    private static String publishedLabel(ParPoint point) {
        boolean months = point.tenor().endsWith("M");
        String amount = point.tenor().substring(0, point.tenor().length() - 1);
        return amount + (months ? " Mo" : " Yr");
    }

    static double years(String label) {
        Matcher m = matcher(label);
        double amount = Double.parseDouble(m.group(1));
        return m.group(2).startsWith("Mo") ? amount / 12.0 : amount;
    }

    private static String shortLabel(String label) {
        Matcher m = matcher(label);
        return m.group(1) + (m.group(2).startsWith("Mo") ? "M" : "Y");
    }

    private static Matcher matcher(String label) {
        Matcher m = TENOR.matcher(label);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unrecognised tenor label: " + label);
        }
        return m;
    }
}
