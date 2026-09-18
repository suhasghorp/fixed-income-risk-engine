package com.fixedincomerisk.refdata;

import com.fixedincomerisk.book.Book;
import com.fixedincomerisk.book.Position;
import com.fixedincomerisk.credit.Issuer;
import com.fixedincomerisk.credit.RatingBucket;
import com.fixedincomerisk.credit.RatingBucketSpec;
import com.fixedincomerisk.instrument.CorporateBond;
import com.fixedincomerisk.instrument.Instrument;
import com.fixedincomerisk.instrument.InterestRateSwap;
import com.fixedincomerisk.instrument.ProxyBond;
import com.fixedincomerisk.instrument.TreasuryBond;
import com.fixedincomerisk.instrument.TreasuryFuture;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static reference data bundled with the application: real Treasuries, Treasury futures contract specs
 * (with their Proxy Bonds, which are not in the Book), fictional corporate issuers with their Rating
 * Buckets and bonds, interest rate swap terms, and the Book's Positions.
 *
 * @param instruments  every Instrument a Position can reference, by id
 * @param ratingBuckets the Rating Buckets, in reporting order
 * @param issuers       the corporate issuers, in reporting order
 */
public record ReferenceData(Map<String, Instrument> instruments, Book book, List<RatingBucketSpec> ratingBuckets,
                            List<Issuer> issuers) {

    public ReferenceData {
        instruments = Map.copyOf(instruments);
        ratingBuckets = List.copyOf(ratingBuckets);
        issuers = List.copyOf(issuers);
    }

    /** The Treasury futures contracts the Book holds, in Book order. */
    public List<TreasuryFuture> futuresInBook() {
        return book.positions().stream()
                .map(Position::instrument)
                .distinct()
                .filter(TreasuryFuture.class::isInstance)
                .map(TreasuryFuture.class::cast)
                .toList();
    }

    public static ReferenceData fromClasspath() {
        return parse(new Csv(
                resource("/refdata/treasuries.csv"),
                resource("/refdata/futures.csv"),
                resource("/refdata/rating-buckets.csv"),
                resource("/refdata/issuers.csv"),
                resource("/refdata/corporates.csv"),
                resource("/refdata/swaps.csv"),
                resource("/refdata/book.csv")));
    }

    /** Reference data with Treasuries only. */
    public static ReferenceData parse(Reader treasuriesCsv, Reader bookCsv) {
        return parse(new Csv(read(treasuriesCsv), "", "", "", "", "", read(bookCsv)));
    }

    /** Reference data with Treasuries and futures, and no credit. */
    public static ReferenceData parse(Reader treasuriesCsv, Reader futuresCsv, Reader bookCsv) {
        return parse(new Csv(read(treasuriesCsv), read(futuresCsv), "", "", "", "", read(bookCsv)));
    }

    public static ReferenceData parse(Csv csv) {
        Map<String, Instrument> instruments = new LinkedHashMap<>();
        for (String[] row : rows(csv.treasuries())) {
            TreasuryBond bond = new TreasuryBond(row[0], row[1], Double.parseDouble(row[2]) / 100.0,
                    LocalDate.parse(row[3]), LocalDate.parse(row[4]));
            add(instruments, bond);
        }
        Map<String, String> futureDescriptions = new LinkedHashMap<>();
        Map<String, List<ProxyBond>> deliverables = new LinkedHashMap<>();
        for (String[] row : rows(csv.futures())) {
            TreasuryBond bond = new TreasuryBond(row[2], "Proxy", Double.parseDouble(row[3]) / 100.0,
                    LocalDate.parse(row[4]), LocalDate.parse(row[5]));
            futureDescriptions.putIfAbsent(row[0], row[1]);
            deliverables.computeIfAbsent(row[0], c -> new ArrayList<>())
                    .add(new ProxyBond(bond, Double.parseDouble(row[6])));
        }
        deliverables.forEach((contract, bonds) ->
                add(instruments, new TreasuryFuture(contract, futureDescriptions.get(contract), bonds)));

        List<RatingBucketSpec> ratingBuckets = new ArrayList<>();
        for (String[] row : rows(csv.ratingBuckets())) {
            ratingBuckets.add(new RatingBucketSpec(new RatingBucket(row[0], row[1]), Double.parseDouble(row[2])));
        }
        Map<String, Issuer> issuers = new LinkedHashMap<>();
        for (String[] row : rows(csv.issuers())) {
            RatingBucket bucket = new RatingBucket(row[2], row[3]);
            if (ratingBuckets.stream().noneMatch(spec -> spec.bucket().equals(bucket))) {
                throw new IllegalArgumentException("Issuer " + row[0] + " is in unknown Rating Bucket " + bucket);
            }
            issuers.put(row[0], new Issuer(row[0], row[1], bucket, Double.parseDouble(row[4]),
                    Double.parseDouble(row[5]), Double.parseDouble(row[6]), Double.parseDouble(row[7]),
                    Double.parseDouble(row[8])));
        }
        for (String[] row : rows(csv.swaps())) {
            add(instruments, new InterestRateSwap(row[0], InterestRateSwap.Direction.valueOf(row[1]),
                    Double.parseDouble(row[2]) / 100.0, LocalDate.parse(row[3]), LocalDate.parse(row[4])));
        }
        for (String[] row : rows(csv.corporates())) {
            Issuer issuer = issuers.get(row[1]);
            if (issuer == null) {
                throw new IllegalArgumentException("Bond " + row[0] + " references unknown issuer " + row[1]);
            }
            add(instruments, new CorporateBond(row[0], issuer.id(), issuer.name(), Double.parseDouble(row[2]) / 100.0,
                    LocalDate.parse(row[3]), LocalDate.parse(row[4])));
        }

        List<Position> positions = new ArrayList<>();
        for (String[] row : rows(csv.book())) {
            Instrument instrument = instruments.get(row[1]);
            if (instrument == null) {
                throw new IllegalArgumentException("Position " + row[0] + " references unknown instrument " + row[1]);
            }
            positions.add(new Position(row[0], instrument, Double.parseDouble(row[2])));
        }
        return new ReferenceData(instruments, new Book(positions), ratingBuckets, List.copyOf(issuers.values()));
    }

    private static void add(Map<String, Instrument> instruments, Instrument instrument) {
        if (instruments.putIfAbsent(instrument.id(), instrument) != null) {
            throw new IllegalArgumentException("Duplicate instrument id: " + instrument.id());
        }
    }

    /**
     * The reference data CSV files' contents. Each has a header line; blank lines and lines starting with
     * '#' are ignored. An empty string means none of that kind.
     */
    public record Csv(String treasuries, String futures, String ratingBuckets, String issuers, String corporates,
                      String swaps, String book) {
    }

    /** Data rows of a CSV with a header line; blank lines and lines starting with '#' are ignored. */
    private static List<String[]> rows(String csv) {
        try (BufferedReader lines = new BufferedReader(new StringReader(csv))) {
            List<String[]> rows = new ArrayList<>();
            boolean headerSeen = false;
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (!headerSeen) {
                    headerSeen = true;
                    continue;
                }
                String[] cells = trimmed.split(",");
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = cells[i].trim();
                }
                rows.add(cells);
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String resource(String path) {
        try (InputStream in = ReferenceData.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Reference data missing: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Reader reader) {
        try (BufferedReader lines = new BufferedReader(reader)) {
            StringBuilder text = new StringBuilder();
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                text.append(line).append('\n');
            }
            return text.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
