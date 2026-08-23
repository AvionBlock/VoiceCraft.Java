package team.avion.adapter;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Delays transient voice-end markers so short capture gaps do not split one utterance. */
public final class VoiceAudioEndDebouncer<K> implements AutoCloseable {
    public static final long DEFAULT_DELAY_MILLIS = 300L;

    private final Map<K, PendingEnd> pendingEnds = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService scheduler;
    private final long delayMillis;

    public VoiceAudioEndDebouncer(String threadName) {
        this(threadName, DEFAULT_DELAY_MILLIS);
    }

    public VoiceAudioEndDebouncer(String threadName, long delayMillis) {
        if (delayMillis < 0L) {
            throw new IllegalArgumentException("delayMillis must not be negative");
        }
        this.delayMillis = delayMillis;
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, Objects.requireNonNull(threadName, "threadName"));
            thread.setDaemon(true);
            return thread;
        });
    }

    public void schedule(K key, Runnable task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        if (closed.get()) {
            return;
        }
        long generation = generations.incrementAndGet();
        ScheduledFuture<?> future = scheduler.schedule(
                () -> runIfCurrent(key, generation, task), delayMillis, TimeUnit.MILLISECONDS);
        PendingEnd replacement = new PendingEnd(generation, future);
        PendingEnd previous = pendingEnds.put(key, replacement);
        if (previous != null) {
            previous.future().cancel(false);
        }
    }

    public boolean cancel(K key) {
        PendingEnd pending = pendingEnds.remove(key);
        if (pending != null) {
            pending.future().cancel(false);
            return true;
        }
        return false;
    }

    public void clear() {
        pendingEnds.values().forEach(pending -> pending.future().cancel(false));
        pendingEnds.clear();
    }

    private void runIfCurrent(K key, long generation, Runnable task) {
        PendingEnd pending = pendingEnds.get(key);
        if (pending == null || pending.generation() != generation || !pendingEnds.remove(key, pending)) {
            return;
        }
        task.run();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            clear();
            scheduler.shutdownNow();
        }
    }

    private record PendingEnd(long generation, ScheduledFuture<?> future) {
    }
}
