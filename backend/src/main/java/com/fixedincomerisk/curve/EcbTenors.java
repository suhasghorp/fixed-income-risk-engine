package com.fixedincomerisk.curve;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which tenors of the ECB's yield curve the session asks for, read from reference data rather than
 * hardcoded: the series key's data-type code, the label the engine uses, and the tenor in years.
 *
 * @param byCode the tenors in published order, keyed by the ECB data-type code, e.g. "SR_10Y"
 */
public record EcbTenors(Map<String, Tenor> byCode) {

    private static final String RESOURCE = "/refdata/ecb-curve-tenors.csv";

    /** @param years tenor in years, used as the knot time on the discount curve */
    public record Tenor(String code, String label, double years) {
    }

    public EcbTenors {
        if (byCode.isEmpty()) {
            throw new IllegalArgumentException("At least one ECB tenor must be configured");
        }
        byCode = new LinkedHashMap<>(byCode);
    }

    public static EcbTenors fromClasspath() {
        InputStream in = EcbTenors.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("ECB tenor reference data missing: " + RESOURCE);
        }
        return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    public static EcbTenors parse(Reader reader) {
        Map<String, Tenor> tenors = new LinkedHashMap<>();
        try (BufferedReader lines = new BufferedReader(reader)) {
            String line;
            boolean header = true;
            while ((line = lines.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (header) {
                    header = false;
                    continue;
                }
                String[] fields = trimmed.split(",");
                if (fields.length < 3) {
                    throw new IllegalArgumentException("Unusable ECB tenor row: " + trimmed);
                }
                String code = fields[0].trim();
                if (tenors.put(code, new Tenor(code, fields[1].trim(), Double.parseDouble(fields[2].trim()))) != null) {
                    throw new IllegalArgumentException("Duplicate ECB tenor code: " + code);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new EcbTenors(tenors);
    }

    /** The data-type codes joined as one series key selection, e.g. "SR_3M+SR_6M+SR_10Y". */
    public String seriesKeySelection() {
        return String.join("+", byCode.keySet());
    }

    public List<Tenor> all() {
        return new ArrayList<>(byCode.values());
    }

    /** The tenor for an ECB data-type code, or null when the response carries one we did not ask for. */
    public Tenor forCode(String code) {
        return byCode.get(code);
    }
}
