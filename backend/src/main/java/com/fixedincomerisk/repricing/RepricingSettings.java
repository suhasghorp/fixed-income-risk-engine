package com.fixedincomerisk.repricing;

/**
 * @param thresholds         Materiality Thresholds per factor type
 * @param minPillarExposure  the smallest share of an Instrument's Bucketed DV01 (sum of absolute
 *                           buckets) at a Pillar for that Pillar to count as a dependency, e.g. 0.05. Zero
 *                           keeps every Pillar the Instrument's cash flows touch.
 * @param workerThreads      how many threads reprice dirty Instruments in parallel; 1 prices them on the
 *                           calling thread
 */
public record RepricingSettings(MaterialityThresholds thresholds, double minPillarExposure, int workerThreads) {

    public RepricingSettings {
        if (!(minPillarExposure >= 0 && minPillarExposure < 1)) {
            throw new IllegalArgumentException("Minimum Pillar exposure must be in [0, 1)");
        }
        if (workerThreads < 1) {
            throw new IllegalArgumentException("Need at least one repricing worker thread");
        }
    }

    /** Repricing on the calling thread. */
    public RepricingSettings(MaterialityThresholds thresholds, double minPillarExposure) {
        this(thresholds, minPillarExposure, 1);
    }

    /** Reprices every Instrument on every tick. */
    public static final RepricingSettings REPRICE_EVERYTHING = new RepricingSettings(MaterialityThresholds.ZERO, 0);

    public RepricingSettings withWorkerThreads(int threads) {
        return new RepricingSettings(thresholds, minPillarExposure, threads);
    }
}
