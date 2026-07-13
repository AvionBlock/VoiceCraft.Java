package team.avion.adapter.simplevoicechat.session;

import team.avion.adapter.VoiceCraftAudioBridge;
import team.avion.adapter.simplevoicechat.net.MicPacket;
import team.avion.adapter.simplevoicechat.net.SimpleVoiceChatSecret;

import java.net.SocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tracks Simple Voice Chat UDP sessions and forwards microphone frames into the shared audio bridge.
 */
public final class SimpleVoiceChatSessionManager {
    private static final long AUDIO_END_DELAY_MILLIS = 300L;

    private final VoiceCraftAudioBridge audioBridge;
    private final AudioEndDebugMetrics audioEndDebugMetrics;
    private final ScheduledExecutorService audioEndExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "VoiceCraft.Java-SVC-AudioEnd");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, SimpleVoiceChatClientSession> sessionsByPlayerId = new ConcurrentHashMap<>();
    private final Map<Integer, SimpleVoiceChatClientSession> sessionsByEntityId = new ConcurrentHashMap<>();
    private final Map<UUID, PendingAudioEnd> pendingAudioEnds = new ConcurrentHashMap<>();

    public SimpleVoiceChatSessionManager(VoiceCraftAudioBridge audioBridge) {
        this(audioBridge, AudioEndDebugMetrics.NOOP);
    }

    public SimpleVoiceChatSessionManager(VoiceCraftAudioBridge audioBridge, AudioEndDebugMetrics audioEndDebugMetrics) {
        this.audioBridge = audioBridge;
        this.audioEndDebugMetrics = audioEndDebugMetrics == null ? AudioEndDebugMetrics.NOOP : audioEndDebugMetrics;
    }

    /**
     * Creates or replaces a Simple Voice Chat session for a Minecraft player.
     *
     * @param playerId Minecraft player UUID
     * @param playerName Minecraft player name
     * @param voiceCraftEntityId associated VoiceCraft entity id
     * @return created session
     */
    public SimpleVoiceChatClientSession createOrReplace(UUID playerId, String playerName, int voiceCraftEntityId) {
        remove(playerId);

        SimpleVoiceChatClientSession session = new SimpleVoiceChatClientSession(
                playerId,
                playerName,
                voiceCraftEntityId,
                SimpleVoiceChatSecret.random()
        );
        sessionsByPlayerId.put(playerId, session);
        sessionsByEntityId.put(voiceCraftEntityId, session);
        return session;
    }

    /**
     * Finds a session by Minecraft player UUID.
     *
     * @param playerId Minecraft player UUID
     * @return matching session, if present
     */
    public Optional<SimpleVoiceChatClientSession> byPlayerId(UUID playerId) {
        return Optional.ofNullable(sessionsByPlayerId.get(playerId));
    }

    /**
     * Finds a session by VoiceCraft entity id.
     *
     * @param entityId VoiceCraft entity id
     * @return matching session, if present
     */
    public Optional<SimpleVoiceChatClientSession> byEntityId(int entityId) {
        return Optional.ofNullable(sessionsByEntityId.get(entityId));
    }

    /**
     * Returns all active Simple Voice Chat sessions.
     *
     * @return active sessions
     */
    public Collection<SimpleVoiceChatClientSession> sessions() {
        return sessionsByPlayerId.values();
    }

    /**
     * Removes a session by Minecraft player UUID.
     *
     * @param playerId Minecraft player UUID
     */
    public void remove(UUID playerId) {
        SimpleVoiceChatClientSession removed = sessionsByPlayerId.remove(playerId);
        if (removed != null) {
            sessionsByEntityId.remove(removed.voiceCraftEntityId());
        }
        cancelPendingAudioEnd(playerId, false);
    }

    /**
     * Stops delayed audio-end work owned by this session manager.
     */
    public void close() {
        pendingAudioEnds.values().forEach(pending -> pending.future().cancel(false));
        pendingAudioEnds.clear();
        audioEndExecutor.shutdownNow();
    }

    /**
     * Forwards a decoded microphone packet to the shared bridge.
     *
     * @param session source Simple Voice Chat session
     * @param packet decoded microphone packet
     */
    public void forwardAudio(SimpleVoiceChatClientSession session, MicPacket packet) {
        if (packet.data().length == 0) {
            audioEndDebugMetrics.svcEmptyMicReceived();
            scheduleAudioEnd(session, packet.sequenceNumber());
            return;
        }
        cancelPendingAudioEnd(session.playerId(), true);
        session.touch(session.remoteAddress());
        audioBridge.sendEntityAudio(
                session.voiceCraftEntityId(),
                (int) (packet.sequenceNumber() & 0xFFFF),
                1.0f,
                packet.data()
        );
    }

    private void scheduleAudioEnd(SimpleVoiceChatClientSession session, long sequenceNumber) {
        PendingAudioEnd existing = pendingAudioEnds.get(session.playerId());
        if (existing != null && !existing.future().isDone()) {
            return;
        }

        Object token = new Object();
        ScheduledFuture<?> future = audioEndExecutor.schedule(
                () -> sendPendingAudioEnd(session.playerId(), session.voiceCraftEntityId(), sequenceNumber, token),
                AUDIO_END_DELAY_MILLIS,
                TimeUnit.MILLISECONDS
        );
        pendingAudioEnds.put(session.playerId(), new PendingAudioEnd(future, token));
    }

    private void sendPendingAudioEnd(UUID playerId, int entityId, long sequenceNumber, Object token) {
        PendingAudioEnd pending = pendingAudioEnds.get(playerId);
        if (pending == null || pending.token() != token || pending.future().isCancelled()
                || !pendingAudioEnds.remove(playerId, pending)) {
            return;
        }
        audioBridge.sendEntityAudioEnd(entityId, sequenceNumber);
        audioEndDebugMetrics.svcAudioEndSent();
    }

    private void cancelPendingAudioEnd(UUID playerId, boolean cancelledByAudio) {
        PendingAudioEnd pending = pendingAudioEnds.remove(playerId);
        if (pending == null || !pending.future().cancel(false) || !cancelledByAudio) {
            return;
        }
        audioEndDebugMetrics.svcAudioEndCancelledByAudio();
    }

    private record PendingAudioEnd(ScheduledFuture<?> future, Object token) {
    }

    public interface AudioEndDebugMetrics {
        AudioEndDebugMetrics NOOP = new AudioEndDebugMetrics() {
        };

        default void svcEmptyMicReceived() {
        }

        default void svcAudioEndSent() {
        }

        default void svcAudioEndCancelledByAudio() {
        }
    }
}
