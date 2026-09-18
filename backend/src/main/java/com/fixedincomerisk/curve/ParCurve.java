package com.fixedincomerisk.curve;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** A dated par yield curve, points ordered by tenor. */
public record ParCurve(LocalDate curveDate, List<ParPoint> points) {

    public ParCurve {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("A par curve needs at least one point");
        }
        points = points.stream().sorted(Comparator.comparingDouble(ParPoint::years)).toList();
    }
}
