package team.avion.adapter.plasmo.session;

import team.avion.adapter.VoiceCraftAudioBridge;
import team.avion.adapter.plasmo.udp.PlasmoUdpEnvelope;
import team.avion.adapter.plasmo.udp.packet.PlayerAudioPacket;

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

public final class PlasmoSessionManager {
    private static final long AUDIO_END_DELAY_MILLIS = 300L;

    private final VoiceCraftAudioBridge audioBridge;
    private final ScheduledExecutorService audioEndExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "VoiceCraft.Java-Plasmo-AudioEnd");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, PlasmoClientSession> sessionsByPlayerId = new ConcurrentHashMap<>();
    private final Map<UUID, PlasmoClientSession> sessionsBySecret = new ConcurrentHashMap<>();
    private final Map<Integer, PlasmoClientSession> sessionsByEntityId = new ConcurrentHashMap<>();
    private final Map<UUID, PendingAudioEnd> pendingAudioEnds = new ConcurrentHashMap<>();

    public PlasmoSessionManager(VoiceCraftAudioBridge audioBridge) {
        this.audioBridge = audioBridge;
    }

    public PlasmoClientSession createOrReplace(UUID playerId, String playerName, int voiceCraftEntityId) {
        remove(playerId);

        PlasmoClientSession session = new PlasmoClientSession(
                playerId,
                playerName,
                voiceCraftEntityId,
                UUID.randomUUID()
        );
        sessionsByPlayerId.put(playerId, session);
        sessionsBySecret.put(session.udpSecret(), session);
        sessionsByEntityId.put(voiceCraftEntityId, session);
        return session;
    }

    public Optional<PlasmoClientSession> byPlayerId(UUID playerId) {
        return Optional.ofNullable(sessionsByPlayerId.get(playerId));
    }

    public Optional<PlasmoClientSession> bySecret(UUID secret) {
        return Optional.ofNullable(sessionsBySecret.get(secret));
    }

    public Optional<PlasmoClientSession> byEntityId(int entityId) {
        return Optional.ofNullable(sessionsByEntityId.get(entityId));
    }

    public Collection<PlasmoClientSession> sessions() {
        return sessionsBySecret.values();
    }

    public void remove(UUID playerId) {
        PlasmoClientSession removed = sessionsByPlayerId.remove(playerId);
        if (removed != null) {
            sessionsBySecret.remove(removed.udpSecret());
            sessionsByEntityId.remove(removed.voiceCraftEntityId());
        }
        cancelPendingAudioEnd(playerId);
    }

    public void close() {
        pendingAudioEnds.values().forEach(pending -> pending.future().cancel(false));
        pendingAudioEnds.clear();
        audioEndExecutor.shutdownNow();
    }

    public boolean handleUdpPacket(PlasmoUdpEnvelope envelope, SocketAddress remoteAddress) {
        PlasmoClientSession session = sessionsBySecret.get(envelope.secret());
        if (session == null) {
            return false;
        }

        session.touch(remoteAddress);
        if (envelope.packet() instanceof PlayerAudioPacket audioPacket) {
            forwardAudio(session, audioPacket);
        }
        return true;
    }

    public void forwardAudioEnd(UUID playerId, long sequenceNumber) {
        PlasmoClientSession session = sessionsByPlayerId.get(playerId);
        if (session != null) {
            cancelPendingAudioEnd(playerId);
            Object token = new Object();
            ScheduledFuture<?> future = audioEndExecutor.schedule(
                    () -> sendPendingAudioEnd(session, sequenceNumber, token),
                    AUDIO_END_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS
            );
            pendingAudioEnds.put(playerId, new PendingAudioEnd(future, token));
        }
    }

    private void forwardAudio(PlasmoClientSession session, PlayerAudioPacket packet) {
        cancelPendingAudioEnd(session.playerId());
        // Temporary bridge limitation; will be removed in future when loudness is derived from decoded PCM.
        float approximatedLoudness = 1.0f;
        audioBridge.sendEntityAudio(
                session.voiceCraftEntityId(),
                (int) (packet.sequenceNumber() & 0xFFFF),
                approximatedLoudness,
                packet.data()
        );
    }

    private void sendPendingAudioEnd(PlasmoClientSession session, long sequenceNumber, Object token) {
        PendingAudioEnd pending = pendingAudioEnds.get(session.playerId());
        if (pending == null || pending.token() != token || pending.future().isCancelled()
                || sessionsByPlayerId.get(session.playerId()) != session
                || !pendingAudioEnds.remove(session.playerId(), pending)) {
            return;
        }
        audioBridge.sendEntityAudioEnd(session.voiceCraftEntityId(), sequenceNumber);
    }

    private void cancelPendingAudioEnd(UUID playerId) {
        PendingAudioEnd pending = pendingAudioEnds.remove(playerId);
        if (pending != null) {
            pending.future().cancel(false);
        }
    }

    private record PendingAudioEnd(ScheduledFuture<?> future, Object token) {
    }
}
