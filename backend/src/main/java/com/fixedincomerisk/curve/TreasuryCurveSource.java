package com.fixedincomerisk.curve;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production Curve Source: today's curve fetched live and cached; if the fetch fails, the last
 * cached curve; if there is no usable cache, the snapshot bundled with the application.
 */
public final class TreasuryCurveSource implements CurveSource {

    private static final Logger log = LoggerFactory.getLogger(TreasuryCurveSource.class);

    private final TreasuryWebsiteCurveFetcher live;
    private final CurveCache<ParCurve> cache;
    private final CurveSource bundled;

    public TreasuryCurveSource(TreasuryWebsiteCurveFetcher live, CurveCache<ParCurve> cache, CurveSource bundled) {
        this.live = live;
        this.cache = cache;
        this.bundled = bundled;
    }

    /** The Treasury par curve is the USD curve. */
    @Override
    public String currency() {
        return "USD";
    }

    @Override
    public CurveSnapshot load() {
        try {
            ParCurve curve = live.fetchLatest();
            cacheQuietly(curve);
            log.info("Curve Source LIVE: Treasury par curve for {}", curve.curveDate());
            return CurveSnapshot.of(curve, CurveSourceKind.LIVE);
        } catch (CurveUnavailableException liveFailure) {
            Optional<ParCurve> cached = cache.read();
            if (cached.isPresent()) {
                log.warn("Live curve fetch failed ({}); Curve Source CACHED: par curve for {}",
                        liveFailure.getMessage(), cached.get().curveDate());
                return CurveSnapshot.of(cached.get(), CurveSourceKind.CACHED);
            }
            CurveSnapshot fallback = bundled.load();
            log.warn("Live curve fetch failed ({}) and no usable cache at {}; Curve Source {}: par curve for {}",
                    liveFailure.getMessage(), cache.file(), fallback.source(), fallback.curveDate());
            return fallback;
        }
    }

    /** A cache write failure must not turn a successful live fetch into a failed start. */
    private void cacheQuietly(ParCurve curve) {
        try {
            cache.write(curve);
        } catch (RuntimeException e) {
            log.warn("Could not cache the live curve: {}", e.getMessage());
        }
    }
}
