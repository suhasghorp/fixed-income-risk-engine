package com.fixedincomerisk.curve;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the ECB Data Portal's {@code format=csvdata} response for the euro area AAA-rated central
 * government bond spot-rate series ({@code YC.B.U2.EUR.4F.G_N_A.SV_C_YM.SR_<tenor>}).
 *
 * <p>The response is one row per (series, observation) with the series key's parts spread across named
 * columns. Only three matter: {@code DATA_TYPE_FM} carries the tenor code, {@code TIME_PERIOD} the
 * observation date, and {@code OBS_VALUE} the rate. The ECB publishes these as <em>continuously
 * compounded</em> percentages per annum, which is exactly what {@link DiscountCurve#fromZeroRates}
 * wants, so the only conversion is the division by 100.
 *
 * <p>Later columns carry free text containing commas inside quotes, so rows are split quote-aware
 * rather than on every comma.
 */
public final class EcbSpotRatesCsv {

    private static final String TENOR_COLUMN = "DATA_TYPE_FM";
    private static final String DATE_COLUMN = "TIME_PERIOD";
    private static final String VALUE_COLUMN = "OBS_VALUE";

    private EcbSpotRatesCsv() {
    }

    /**
     * The most recent observation date present, with one point per {@code tenors} entry found on it.
     * Tenors the response does not carry on that date are left out; the caller decides whether what
     * is left is usable.
     */
    public static ZeroCurve parse(Reader reader, EcbTenors tenors) {
        List<String[]> rows = new ArrayList<>();
        String[] header;
        try (BufferedReader lines = new BufferedReader(reader)) {
            String headerLine = lines.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("ECB spot rate CSV is empty");
            }
            header = split(headerLine);
            String line;
            while ((line = lines.readLine()) != null) {
                if (!line.isBlank()) {
                    rows.add(split(line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int tenorAt = columnIndex(header, TENOR_COLUMN);
        int dateAt = columnIndex(header, DATE_COLUMN);
        int valueAt = columnIndex(header, VALUE_COLUMN);

        LocalDate latest = null;
        for (String[] row : rows) {
            if (row.length <= Math.max(tenorAt, Math.max(dateAt, valueAt))) {
                continue;
            }
            LocalDate date = LocalDate.parse(row[dateAt].trim());
            if (latest == null || date.isAfter(latest)) {
                latest = date;
            }
        }
        if (latest == null) {
            throw new IllegalArgumentException("ECB spot rate CSV has no observations");
        }
        List<ZeroPoint> points = new ArrayList<>();
        for (String[] row : rows) {
            if (row.length <= Math.max(tenorAt, Math.max(dateAt, valueAt))
                    || !latest.equals(LocalDate.parse(row[dateAt].trim()))) {
                continue;
            }
            EcbTenors.Tenor tenor = tenors.forCode(row[tenorAt].trim());
            String value = row[valueAt].trim();
            if (tenor == null || value.isEmpty()) {
                continue;
            }
            points.add(new ZeroPoint(tenor.label(), tenor.years(), Double.parseDouble(value) / 100.0));
        }
        if (points.isEmpty()) {
            throw new IllegalArgumentException("ECB spot rate CSV carries no requested tenor for " + latest);
        }
        return new ZeroCurve(latest, points);
    }

    private static int columnIndex(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("ECB spot rate CSV has no " + name + " column");
    }

    /** Splits one CSV row, honouring double-quoted fields that contain commas. */
    static String[] split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }
}
