package com.fixedincomerisk.market;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** A fixed curve tenor at which the curve is reported and Bucketed DV01 is measured. */
public record Pillar(String label, double years) {

    public static final List<Pillar> DEFAULTS = parseList("3M,1Y,2Y,3Y,5Y,7Y,10Y,20Y,30Y");

    public Pillar {
        if (!(years > 0)) {
            throw new IllegalArgumentException("Pillar tenor must be positive: " + label);
        }
    }

    /** Parses a tenor label such as "3M" or "10Y". */
    public static Pillar parse(String tenor) {
        String label = tenor.trim().toUpperCase(Locale.ROOT);
        if (label.length() < 2) {
            throw new IllegalArgumentException("Invalid Pillar tenor: " + tenor);
        }
        int amount;
        try {
            amount = Integer.parseInt(label.substring(0, label.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid Pillar tenor: " + tenor, e);
        }
        return switch (label.charAt(label.length() - 1)) {
            case 'M' -> new Pillar(label, amount / 12.0);
            case 'Y' -> new Pillar(label, amount);
            default -> throw new IllegalArgumentException("Invalid Pillar tenor: " + tenor);
        };
    }

    /** Parses a comma-separated list of tenors, which must be strictly increasing. */
    public static List<Pillar> parseList(String tenors) {
        List<Pillar> pillars = Arrays.stream(tenors.split(",")).map(Pillar::parse).toList();
        validateOrder(pillars);
        return pillars;
    }

    /**
     * The Pillars whose Bucketed DV01 bump reaches tenor {@code years}: the one or two Pillars either
     * side of it, or the first or last Pillar beyond the ends of the list.
     */
    public static List<Pillar> around(List<Pillar> pillars, double years) {
        for (int i = 0; i < pillars.size(); i++) {
            double here = pillars.get(i).years();
            if (years == here) {
                return List.of(pillars.get(i));
            }
            if (years < here) {
                return i == 0 ? List.of(pillars.get(0)) : List.of(pillars.get(i - 1), pillars.get(i));
            }
        }
        return List.of(pillars.getLast());
    }

    /** @throws IllegalArgumentException if the list is empty or not strictly increasing in tenor */
    public static void validateOrder(List<Pillar> pillars) {
        if (pillars.isEmpty()) {
            throw new IllegalArgumentException("At least one Pillar is required");
        }
        for (int i = 1; i < pillars.size(); i++) {
            if (!(pillars.get(i).years() > pillars.get(i - 1).years())) {
                throw new IllegalArgumentException("Pillars must be strictly increasing: " + pillars);
            }
        }
    }
}
