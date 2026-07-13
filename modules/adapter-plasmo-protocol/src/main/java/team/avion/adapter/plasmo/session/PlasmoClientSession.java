package team.avion.adapter.plasmo.session;

import java.net.SocketAddress;
import java.util.UUID;

public final class PlasmoClientSession {
    private final UUID playerId;
    private final String playerName;
    private final int voiceCraftEntityId;
    private final UUID udpSecret;
    private volatile SocketAddress remoteAddress;
    private volatile long lastSeenMillis;
    private volatile long lastKeepAliveSentMillis;
    private volatile boolean configSent;

    public PlasmoClientSession(UUID playerId, String playerName, int voiceCraftEntityId, UUID udpSecret) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.voiceCraftEntityId = voiceCraftEntityId;
        this.udpSecret = udpSecret;
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

    public UUID udpSecret() {
        return udpSecret;
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

    public boolean configSent() {
        return configSent;
    }

    public void markConfigSent() {
        configSent = true;
    }

    public void markKeepAliveSent() {
        lastKeepAliveSentMillis = System.currentTimeMillis();
    }

    public void touch(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
        this.lastSeenMillis = System.currentTimeMillis();
    }
}
