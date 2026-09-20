package com.fixedincomerisk.curve;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.function.Function;

/**
 * The last successfully fetched curve for one currency, kept on disk in the format its Curve Source
 * publishes: Treasury's CSV for the par curve, the compact spot-rate CSV for the ECB's zero curve.
 */
public final class CurveCache<T> {

    private final Path file;
    private final Function<Reader, T> parse;
    private final Function<T, String> format;

    public CurveCache(Path file, Function<Reader, T> parse, Function<T, String> format) {
        this.file = file;
        this.parse = parse;
        this.format = format;
    }

    /** A cache of Treasury par curves, in Treasury's own published CSV format. */
    public static CurveCache<ParCurve> forParCurve(Path file) {
        return new CurveCache<>(file, TreasuryParCurveCsv::parse, TreasuryParCurveCsv::format);
    }

    /** A cache of ECB zero curves, in the compact spot-rate CSV format. */
    public static CurveCache<ZeroCurve> forZeroCurve(Path file, EcbTenors tenors) {
        return new CurveCache<>(file, reader -> ZeroCurveCsv.parse(reader, tenors), ZeroCurveCsv::format);
    }

    /** Replaces the cached curve atomically, so a crash never leaves a half-written cache. */
    public void write(T curve) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "curve", ".tmp");
            Files.writeString(temp, format.apply(curve), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write curve cache " + file, e);
        }
    }

    /** The cached curve, or empty if there is none or it can't be read. */
    public Optional<T> read() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse.apply(new StringReader(Files.readString(file, StandardCharsets.UTF_8))));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public Path file() {
        return file;
    }
}
