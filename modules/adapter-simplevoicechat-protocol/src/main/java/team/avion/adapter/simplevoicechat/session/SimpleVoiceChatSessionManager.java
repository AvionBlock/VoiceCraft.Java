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

public final class SimpleVoiceChatSessionManager {
    private final VoiceCraftAudioBridge audioBridge;
    private final Map<UUID, SimpleVoiceChatClientSession> sessionsByPlayerId = new ConcurrentHashMap<>();
    private final Map<Integer, SimpleVoiceChatClientSession> sessionsByEntityId = new ConcurrentHashMap<>();

    public SimpleVoiceChatSessionManager(VoiceCraftAudioBridge audioBridge) {
        this.audioBridge = audioBridge;
    }

    public SimpleVoiceChatClientSession createOrReplace(UUID playerId, String playerName, int voiceCraftEntityId) {
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

    public Optional<SimpleVoiceChatClientSession> byPlayerId(UUID playerId) {
        return Optional.ofNullable(sessionsByPlayerId.get(playerId));
    }

    public Optional<SimpleVoiceChatClientSession> byEntityId(int entityId) {
        return Optional.ofNullable(sessionsByEntityId.get(entityId));
    }

    public Collection<SimpleVoiceChatClientSession> sessions() {
        return sessionsByPlayerId.values();
    }

    public void remove(UUID playerId) {
        SimpleVoiceChatClientSession removed = sessionsByPlayerId.remove(playerId);
        if (removed != null) {
            sessionsByEntityId.remove(removed.voiceCraftEntityId());
        }
    }

    public void forwardAudio(SimpleVoiceChatClientSession session, MicPacket packet) {
        if (packet.data().length == 0) {
            audioBridge.sendEntityAudioEnd(session.voiceCraftEntityId(), packet.sequenceNumber());
            return;
        }
        session.touch(session.remoteAddress());
        audioBridge.sendEntityAudio(
                session.voiceCraftEntityId(),
                (int) (packet.sequenceNumber() & 0xFFFF),
                1.0f,
                packet.data()
        );
    }
}
