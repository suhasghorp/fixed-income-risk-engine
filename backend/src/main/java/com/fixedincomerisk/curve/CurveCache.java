package com.fixedincomerisk.curve;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** The last successfully fetched par curve, kept on disk in Treasury's CSV format. */
public final class CurveCache {

    private final Path file;

    public CurveCache(Path file) {
        this.file = file;
    }

    /** Replaces the cached curve atomically, so a crash never leaves a half-written cache. */
    public void write(ParCurve curve) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "par-curve", ".tmp");
            Files.writeString(temp, TreasuryParCurveCsv.format(curve), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write curve cache " + file, e);
        }
    }

    /** The cached curve, or empty if there is none or it can't be read. */
    public Optional<ParCurve> read() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(TreasuryParCurveCsv.parse(new StringReader(Files.readString(file, StandardCharsets.UTF_8))));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public Path file() {
        return file;
    }
}
