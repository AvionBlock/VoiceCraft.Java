package team.avion.adapter.simplevoicechat.net;

import team.avion.adapter.VoiceCraftEntityIdentity;

import java.util.Collection;
import java.util.UUID;

/**
 * Encoder and decoder for Simple Voice Chat plugin-message packets used during TCP-style setup.
 */
public final class SimpleVoiceChatTcpCodec {
    private static final int CODEC_VOIP = 0;

    /**
     * Decodes the compatibility version from a client secret request.
     *
     * @param data raw plugin-message payload
     * @return client compatibility version
     */
    public int decodeRequestSecret(byte[] data) {
        return new SimpleVoiceChatBuffer(data).readInt();
    }

    /**
     * Encodes the server secret response that tells the client how to open the UDP voice session.
     *
     * @param secret UDP session secret
     * @param serverPort advertised UDP port
     * @param playerId Minecraft player UUID
     * @param mtuSize maximum voice datagram size
     * @param voiceDistance proximity voice distance
     * @param keepAliveMillis UDP keepalive interval
     * @param voiceHost optional advertised host
     * @param allowRecording whether the client may record voice
     * @return encoded plugin-message payload
     */
    public byte[] encodeSecret(SimpleVoiceChatSecret secret, int serverPort, UUID playerId, int mtuSize,
                               double voiceDistance, int keepAliveMillis, String voiceHost, boolean allowRecording) {
        SimpleVoiceChatBuffer output = new SimpleVoiceChatBuffer();
        output.writeBytes(secret.value());
        output.writeInt(serverPort);
        output.writeUuid(playerId);
        output.writeByte(CODEC_VOIP);
        output.writeInt(mtuSize);
        output.writeDouble(voiceDistance);
        output.writeInt(keepAliveMillis);
        output.writeBoolean(false);
        output.writeUtf(voiceHost);
        output.writeBoolean(allowRecording);
        return output.toByteArray();
    }

    /**
     * Encodes a single player state update for the client-side voice state cache.
     *
     * @param identity player identity to expose
     * @param disabled whether voice is disabled for the player
     * @param disconnected whether the player should be shown as disconnected
     * @return encoded player state payload
     */
    public byte[] encodePlayerState(VoiceCraftEntityIdentity identity, boolean disabled, boolean disconnected) {
        SimpleVoiceChatBuffer output = new SimpleVoiceChatBuffer();
        writePlayerState(output, identity, disabled, disconnected);
        return output.toByteArray();
    }

    /**
     * Encodes the initial full player state snapshot sent after UDP connection validation.
     *
     * @param identities identities currently visible through VoiceCraft
     * @return encoded state snapshot payload
     */
    public byte[] encodePlayerStates(Collection<VoiceCraftEntityIdentity> identities) {
        SimpleVoiceChatBuffer output = new SimpleVoiceChatBuffer();
        output.writeInt(identities.size());
        for (VoiceCraftEntityIdentity identity : identities) {
            writePlayerState(output, identity, false, false);
        }
        return output.toByteArray();
    }

    /**
     * Encodes a player state removal packet.
     *
     * @param playerId Minecraft player UUID to remove from the state cache
     * @return encoded removal payload
     */
    public byte[] encodeRemovePlayerState(UUID playerId) {
        SimpleVoiceChatBuffer output = new SimpleVoiceChatBuffer();
        output.writeUuid(playerId);
        return output.toByteArray();
    }

    private void writePlayerState(SimpleVoiceChatBuffer output, VoiceCraftEntityIdentity identity,
                                  boolean disabled, boolean disconnected) {
        output.writeBoolean(disabled);
        output.writeBoolean(disconnected);
        output.writeUuid(identity.playerId());
        output.writeUtf(identity.playerName());
        output.writeBoolean(false);
    }
}
