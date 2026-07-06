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

/**
 * Tracks Simple Voice Chat UDP sessions and forwards microphone frames into the shared audio bridge.
 */
public final class SimpleVoiceChatSessionManager {
    private final VoiceCraftAudioBridge audioBridge;
    private final Map<UUID, SimpleVoiceChatClientSession> sessionsByPlayerId = new ConcurrentHashMap<>();
    private final Map<Integer, SimpleVoiceChatClientSession> sessionsByEntityId = new ConcurrentHashMap<>();

    public SimpleVoiceChatSessionManager(VoiceCraftAudioBridge audioBridge) {
        this.audioBridge = audioBridge;
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
    }

    /**
     * Forwards a decoded microphone packet to the shared bridge.
     *
     * @param session source Simple Voice Chat session
     * @param packet decoded microphone packet
     */
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
