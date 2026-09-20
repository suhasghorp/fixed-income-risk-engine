package com.fixedincomerisk.curve;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production EUR Curve Source: today's ECB spot rates fetched live and cached; if the fetch fails,
 * the last cached curve; if there is no usable cache, the snapshot bundled with the application. The
 * same chain as {@link TreasuryCurveSource}, over a curve that needs no bootstrap.
 */
public final class EcbCurveSource implements CurveSource {

    private static final Logger log = LoggerFactory.getLogger(EcbCurveSource.class);

    private final EcbDataPortalCurveFetcher live;
    private final CurveCache<ZeroCurve> cache;
    private final CurveSource bundled;

    public EcbCurveSource(EcbDataPortalCurveFetcher live, CurveCache<ZeroCurve> cache, CurveSource bundled) {
        this.live = live;
        this.cache = cache;
        this.bundled = bundled;
    }

    @Override
    public String currency() {
        return "EUR";
    }

    @Override
    public CurveSnapshot load() {
        try {
            ZeroCurve curve = live.fetchLatest();
            cacheQuietly(curve);
            log.info("Curve Source LIVE: ECB euro area spot rates for {}", curve.curveDate());
            return CurveSnapshot.of(curve, CurveSourceKind.LIVE);
        } catch (CurveUnavailableException liveFailure) {
            Optional<ZeroCurve> cached = cache.read();
            if (cached.isPresent()) {
                log.warn("Live EUR curve fetch failed ({}); Curve Source CACHED: spot rates for {}",
                        liveFailure.getMessage(), cached.get().curveDate());
                return CurveSnapshot.of(cached.get(), CurveSourceKind.CACHED);
            }
            CurveSnapshot fallback = bundled.load();
            log.warn("Live EUR curve fetch failed ({}) and no usable cache at {}; Curve Source {}: "
                            + "spot rates for {}",
                    liveFailure.getMessage(), cache.file(), fallback.source(), fallback.curveDate());
            return fallback;
        }
    }

    /** A cache write failure must not turn a successful live fetch into a failed start. */
    private void cacheQuietly(ZeroCurve curve) {
        try {
            cache.write(curve);
        } catch (RuntimeException e) {
            log.warn("Could not cache the live EUR curve: {}", e.getMessage());
        }
    }
}
