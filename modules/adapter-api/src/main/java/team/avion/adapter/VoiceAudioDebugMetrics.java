package team.avion.adapter;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Compact, rate-limited diagnostics for locating loss and jitter in the cross-mod audio path. */
public final class VoiceAudioDebugMetrics {
    private static final long WINDOW_MILLIS = 1_000L;
    private static final long STALL_NANOS = 40_000_000L;
    private static final long BURST_NANOS = 5_000_000L;

    private final BooleanSupplier enabled;
    private final Consumer<String> logger;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public VoiceAudioDebugMetrics(BooleanSupplier enabled, Consumer<String> logger) {
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void recordFrame(String flow, long sequence, int bytes) {
        if (!enabled.getAsBoolean()) return;
        window(flow).recordFrame(sequence, bytes);
    }

    public void recordEnd(String flow, String action) {
        if (!enabled.getAsBoolean()) return;
        Window window = window(flow);
        window.recordEnd(action);
        if ("fired".equals(action)) window.flush(true);
    }

    public void recordBatch(String flow, int batchSize, int queueDepth, long exchangeNanos,
            int responseAudioFrames) {
        if (!enabled.getAsBoolean()) return;
        window(flow).recordBatch(batchSize, queueDepth, exchangeNanos, responseAudioFrames);
    }

    public void recordCorrelation(String flow, boolean matched, long latencyNanos) {
        if (!enabled.getAsBoolean()) return;
        window(flow).recordCorrelation(matched, latencyNanos);
    }

    /** Flushes short utterances immediately when their audio-end marker is emitted. */
    public void flushAll() {
        if (!enabled.getAsBoolean()) return;
        windows.values().forEach(window -> window.flush(true));
    }

    private Window window(String flow) {
        return windows.computeIfAbsent(flow, Window::new);
    }

    private final class Window {
        private final String flow;
        private long startedMillis = System.currentTimeMillis();
        private long lastArrivalNanos;
        private long firstSequence;
        private long lastSequence;
        private boolean hasSequence;
        private long frames;
        private long bytes;
        private long sequenceGaps;
        private long backwards;
        private long arrivalNanos;
        private long arrivalSamples;
        private long maxArrivalNanos;
        private long stalls;
        private long currentBurst;
        private long maxBurst;
        private long endsScheduled;
        private long endsCancelled;
        private long endsFired;
        private long batches;
        private long batchFrames;
        private long maxBatch;
        private long maxQueue;
        private long exchangeNanos;
        private long maxExchangeNanos;
        private long responseFrames;
        private long matched;
        private long unmatched;
        private long latencyNanos;
        private long maxLatencyNanos;

        private Window(String flow) {
            this.flow = flow;
        }

        private synchronized void recordFrame(long sequence, int frameBytes) {
            long now = System.nanoTime();
            if (hasSequence) {
                if (sequence <= lastSequence) backwards++;
                else if (sequence != lastSequence + 1L) sequenceGaps += sequence - lastSequence - 1L;
            } else {
                firstSequence = sequence;
                hasSequence = true;
            }
            if (lastArrivalNanos != 0L) {
                long delta = now - lastArrivalNanos;
                arrivalNanos += delta;
                arrivalSamples++;
                maxArrivalNanos = Math.max(maxArrivalNanos, delta);
                if (delta > STALL_NANOS) stalls++;
                if (delta < BURST_NANOS) currentBurst++;
                else currentBurst = 1L;
                maxBurst = Math.max(maxBurst, currentBurst);
            } else {
                currentBurst = 1L;
                maxBurst = 1L;
            }
            lastArrivalNanos = now;
            lastSequence = sequence;
            frames++;
            bytes += Math.max(0, frameBytes);
            flush(false);
        }

        private synchronized void recordEnd(String action) {
            switch (action) {
                case "scheduled" -> endsScheduled++;
                case "cancelled" -> endsCancelled++;
                case "fired" -> endsFired++;
                default -> { }
            }
            flush(false);
        }

        private synchronized void recordBatch(int size, int queueDepth, long durationNanos, int responses) {
            batches++;
            batchFrames += Math.max(0, size);
            maxBatch = Math.max(maxBatch, size);
            maxQueue = Math.max(maxQueue, queueDepth);
            exchangeNanos += Math.max(0L, durationNanos);
            maxExchangeNanos = Math.max(maxExchangeNanos, durationNanos);
            responseFrames += Math.max(0, responses);
            flush(false);
        }

        private synchronized void recordCorrelation(boolean isMatched, long durationNanos) {
            if (isMatched) {
                matched++;
                latencyNanos += Math.max(0L, durationNanos);
                maxLatencyNanos = Math.max(maxLatencyNanos, durationNanos);
            } else {
                unmatched++;
            }
            flush(false);
        }

        private synchronized void flush(boolean force) {
            long now = System.currentTimeMillis();
            long duration = now - startedMillis;
            if (!force && duration < WINDOW_MILLIS) return;
            if (frames == 0L && batches == 0L && endsScheduled == 0L && endsCancelled == 0L
                    && endsFired == 0L && matched == 0L && unmatched == 0L) {
                startedMillis = now;
                return;
            }
            double arrivalAvg = arrivalSamples == 0L ? 0D : nanosToMillis(arrivalNanos / arrivalSamples);
            double exchangeAvg = batches == 0L ? 0D : nanosToMillis(exchangeNanos / batches);
            double latencyAvg = matched == 0L ? 0D : nanosToMillis(latencyNanos / matched);
            logger.accept(String.format(Locale.ROOT,
                    "[AudioDebug] flow=%s windowMs=%d frames=%d bytes=%d seq=%s gaps=%d backwards=%d "
                            + "arrivalMs(avg/max)=%.1f/%.1f stalls=%d burstMax=%d ends(s/c/f)=%d/%d/%d "
                            + "batch(count/frames/max/qmax)=%d/%d/%d/%d exchangeMs(avg/max)=%.1f/%.1f "
                            + "responses=%d correlation(matched/unmatched)=%d/%d latencyMs(avg/max)=%.1f/%.1f",
                    flow, Math.max(0L, duration), frames, bytes,
                    hasSequence ? firstSequence + ".." + lastSequence : "-", sequenceGaps, backwards,
                    arrivalAvg, nanosToMillis(maxArrivalNanos), stalls, maxBurst,
                    endsScheduled, endsCancelled, endsFired,
                    batches, batchFrames, maxBatch, maxQueue, exchangeAvg, nanosToMillis(maxExchangeNanos),
                    responseFrames, matched, unmatched, latencyAvg, nanosToMillis(maxLatencyNanos)));
            reset(now);
        }

        private void reset(long now) {
            startedMillis = now;
            lastArrivalNanos = 0L;
            hasSequence = false;
            frames = bytes = sequenceGaps = backwards = arrivalNanos = arrivalSamples = maxArrivalNanos = stalls = 0L;
            currentBurst = maxBurst = 0L;
            endsScheduled = endsCancelled = endsFired = 0L;
            batches = batchFrames = maxBatch = maxQueue = exchangeNanos = maxExchangeNanos = responseFrames = 0L;
            matched = unmatched = latencyNanos = maxLatencyNanos = 0L;
        }
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000D;
    }
}
