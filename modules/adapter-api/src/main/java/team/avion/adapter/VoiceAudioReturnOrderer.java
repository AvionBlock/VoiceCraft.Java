package team.avion.adapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Restores ingress order when VoiceCraft returns audio events from concurrent request batches out of order. */
public final class VoiceAudioReturnOrderer {
    private static final long DEFAULT_MAX_WAIT_MILLIS = 750L;

    private final Map<UUID, StreamState> streams = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> streamsByEntity = new ConcurrentHashMap<>();
    private final long maxWaitNanos;
    private final LongSupplier nanoTime;

    public VoiceAudioReturnOrderer() {
        this(DEFAULT_MAX_WAIT_MILLIS, System::nanoTime);
    }

    VoiceAudioReturnOrderer(long maxWaitMillis, LongSupplier nanoTime) {
        if (maxWaitMillis < 0L) throw new IllegalArgumentException("maxWaitMillis must not be negative");
        this.maxWaitNanos = maxWaitMillis * 1_000_000L;
        this.nanoTime = nanoTime;
    }

    /** Registers the authoritative order before the frame is sent to VoiceCraft. */
    public void queued(VoiceAudioFrame frame) {
        UUID streamId = frame.source().streamId();
        streamsByEntity.put(frame.source().voiceCraftEntityId(), streamId);
        streams.computeIfAbsent(streamId, ignored -> new StreamState())
                .queued(frame.routeId(), nanoTime.getAsLong());
    }

    /** Returns every now-contiguous frame, in the exact order in which it was queued. */
    public List<VoiceAudioFrame> returned(VoiceAudioFrame frame) {
        StreamState state = streams.get(frame.source().streamId());
        return state == null ? List.of() : state.returned(frame, nanoTime.getAsLong(), maxWaitNanos);
    }

    /** Flushes received tail frames for an entity before its audio-end marker is delivered. */
    public List<VoiceAudioFrame> flushEntity(int entityId) {
        UUID streamId = streamsByEntity.get(entityId);
        StreamState state = streamId == null ? null : streams.get(streamId);
        return state == null ? List.of() : state.flush();
    }

    public void clear() {
        streams.clear();
        streamsByEntity.clear();
    }

    private static final class StreamState {
        private final Deque<PendingFrame> order = new ArrayDeque<>();
        private final Map<UUID, PendingFrame> byRouteId = new HashMap<>();

        private synchronized void queued(UUID routeId, long queuedNanos) {
            if (byRouteId.containsKey(routeId)) return;
            PendingFrame pending = new PendingFrame(routeId, queuedNanos);
            order.addLast(pending);
            byRouteId.put(routeId, pending);
        }

        private synchronized List<VoiceAudioFrame> returned(VoiceAudioFrame frame, long now, long maxWaitNanos) {
            PendingFrame pending = byRouteId.get(frame.routeId());
            if (pending == null) return List.of();
            pending.returned = frame;
            return drain(now, maxWaitNanos, false);
        }

        private synchronized List<VoiceAudioFrame> flush() {
            return drain(Long.MAX_VALUE, 0L, true);
        }

        private List<VoiceAudioFrame> drain(long now, long maxWaitNanos, boolean force) {
            List<VoiceAudioFrame> ready = new ArrayList<>();
            while (!order.isEmpty()) {
                PendingFrame head = order.peekFirst();
                if (head.returned != null) {
                    order.removeFirst();
                    byRouteId.remove(head.routeId);
                    ready.add(head.returned);
                    continue;
                }
                if (force || now - head.queuedNanos >= maxWaitNanos) {
                    order.removeFirst();
                    byRouteId.remove(head.routeId);
                    continue;
                }
                break;
            }
            return ready;
        }
    }

    private static final class PendingFrame {
        private final UUID routeId;
        private final long queuedNanos;
        private VoiceAudioFrame returned;

        private PendingFrame(UUID routeId, long queuedNanos) {
            this.routeId = routeId;
            this.queuedNanos = queuedNanos;
        }
    }
}
