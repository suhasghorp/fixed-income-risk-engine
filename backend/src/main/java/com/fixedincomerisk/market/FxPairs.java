package com.fixedincomerisk.market;

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

/** The currency pairs a session simulates, from reference data rather than hardcoded. */
public record FxPairs(List<FxPair> pairs) {

    private static final String RESOURCE = "/refdata/fx-pairs.csv";

    public FxPairs {
        pairs = List.copyOf(pairs);
        Map<String, FxPair> seen = new LinkedHashMap<>();
        for (FxPair pair : pairs) {
            if (seen.put(pair.pair(), pair) != null) {
                throw new IllegalArgumentException("Duplicate currency pair " + pair.pair());
            }
        }
    }

    /** No FX at all: the engine before a second currency. */
    public static final FxPairs NONE = new FxPairs(List.of());

    public static FxPairs fromClasspath() {
        InputStream in = FxPairs.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("FX pair reference data missing: " + RESOURCE);
        }
        return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    public static FxPairs parse(Reader reader) {
        List<FxPair> pairs = new ArrayList<>();
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
                String[] f = trimmed.split(",");
                if (f.length < 5) {
                    throw new IllegalArgumentException("Unusable FX pair row: " + trimmed);
                }
                pairs.add(new FxPair(f[0].trim(), f[1].trim(), Double.parseDouble(f[2].trim()),
                        Double.parseDouble(f[3].trim()), Boolean.parseBoolean(f[4].trim())));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new FxPairs(pairs);
    }

    public boolean isEmpty() {
        return pairs.isEmpty();
    }

    /** The pairs whose forward is quoted as spot plus Forward Points, in configuration order. */
    public List<FxPair> nonDeliverable() {
        return pairs.stream().filter(FxPair::quotesPoints).toList();
    }

    public FxPair get(String pair) {
        return pairs.stream().filter(p -> p.pair().equals(pair)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No FX pair " + pair + "; configured "
                        + pairs.stream().map(FxPair::pair).toList()));
    }
}
