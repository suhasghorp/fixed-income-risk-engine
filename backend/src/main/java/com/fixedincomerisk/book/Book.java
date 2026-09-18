package com.fixedincomerisk.book;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The set of Positions that risk rolls up to. */
public record Book(List<Position> positions) {

    public Book {
        positions = List.copyOf(positions);
        Set<String> ids = new HashSet<>();
        for (Position position : positions) {
            if (!ids.add(position.positionId())) {
                throw new IllegalArgumentException("Duplicate position id: " + position.positionId());
            }
        }
    }
}
