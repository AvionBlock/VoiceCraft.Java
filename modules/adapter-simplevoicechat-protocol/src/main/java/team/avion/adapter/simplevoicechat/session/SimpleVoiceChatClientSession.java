package team.avion.adapter.simplevoicechat.session;

import team.avion.adapter.simplevoicechat.net.SimpleVoiceChatSecret;

import java.net.SocketAddress;
import java.util.UUID;

public final class SimpleVoiceChatClientSession {
    private final UUID playerId;
    private final String playerName;
    private final int voiceCraftEntityId;
    private final SimpleVoiceChatSecret secret;
    private SocketAddress remoteAddress;
    private long lastSeenMillis;
    private long lastKeepAliveSentMillis;
    private boolean connected;

    public SimpleVoiceChatClientSession(UUID playerId, String playerName, int voiceCraftEntityId, SimpleVoiceChatSecret secret) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.voiceCraftEntityId = voiceCraftEntityId;
        this.secret = secret;
        this.lastSeenMillis = System.currentTimeMillis();
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public int voiceCraftEntityId() {
        return voiceCraftEntityId;
    }

    public SimpleVoiceChatSecret secret() {
        return secret;
    }

    public SocketAddress remoteAddress() {
        return remoteAddress;
    }

    public long lastSeenMillis() {
        return lastSeenMillis;
    }

    public long lastKeepAliveSentMillis() {
        return lastKeepAliveSentMillis;
    }

    public boolean connected() {
        return connected;
    }

    public void markConnected() {
        connected = true;
    }

    public void markKeepAliveSent() {
        lastKeepAliveSentMillis = System.currentTimeMillis();
    }

    public void touch(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
        this.lastSeenMillis = System.currentTimeMillis();
    }
}
