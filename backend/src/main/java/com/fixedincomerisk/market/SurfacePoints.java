package com.fixedincomerisk.market;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Surface Points a session quotes a Normal Volatility at, named in configuration rather than
 * hardcoded. Two today, because the Book holds two Swaptions; a third Swaption is a third name here and
 * one more level in the properties, not a change to any class.
 *
 * <p>{@link #get(String)} is the only way in, so an unknown point name fails naming the offender and
 * listing what is configured, rather than defaulting to some nearby vol.
 */
public record SurfacePoints(List<SurfacePoint> points) {

    /** No volatility at all: the engine before its first option. */
    public static final SurfacePoints NONE = new SurfacePoints(List.of());

    public SurfacePoints {
        points = List.copyOf(points);
        Map<String, SurfacePoint> seen = new LinkedHashMap<>();
        for (SurfacePoint point : points) {
            if (seen.put(point.label(), point) != null) {
                throw new IllegalArgumentException("Duplicate Surface Point '" + point.label()
                        + "' in risk.vol.surface-points");
            }
        }
    }

    /** Parses the point labels, separated by ',', e.g. {@code "USD 1Mx5Y, USD 1Yx10Y"}. */
    public static SurfacePoints parse(String labels) {
        return new SurfacePoints(Arrays.stream(labels.split(","))
                .map(String::trim)
                .filter(label -> !label.isEmpty())
                .map(SurfacePoint::parse)
                .toList());
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public List<String> labels() {
        return points.stream().map(SurfacePoint::label).toList();
    }

    public boolean has(String label) {
        return points.stream().anyMatch(point -> point.label().equals(label));
    }

    /** The named point, or a failure naming the offender and listing what is configured. */
    public SurfacePoint get(String label) {
        return points.stream().filter(point -> point.label().equals(label)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Surface Point '" + label
                        + "'; risk.vol.surface-points names " + labels()));
    }

    /** The point for a currency and coordinate, e.g. {@code get("USD", "1Mx5Y")}. */
    public SurfacePoint get(String currency, String point) {
        return get(currency + " " + point);
    }
}
