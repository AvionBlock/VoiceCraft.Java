package team.avion.adapter.simplevoicechat.net;

import team.avion.adapter.VoiceCraftEntityIdentity;

import java.util.Collection;
import java.util.UUID;

public final class SimpleVoiceChatTcpCodec {
    private static final int CODEC_VOIP = 0;

    public int decodeRequestSecret(byte[] data) {
        return new SimpleVoiceChatBuffer(data).readInt();
    }

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

    public byte[] encodePlayerState(VoiceCraftEntityIdentity identity, boolean disabled, boolean disconnected) {
        SimpleVoiceChatBuffer output = new SimpleVoiceChatBuffer();
        writePlayerState(output, identity, disabled, disconnected);
        return output.toByteArray();
    }

    public byte[] encodePlayerStates(Collection<VoiceCraftEntityIdentity> identities) {
        SimpleVoiceChatBuffer output = new SimpleVoiceChatBuffer();
        output.writeInt(identities.size());
        for (VoiceCraftEntityIdentity identity : identities) {
            writePlayerState(output, identity, false, false);
        }
        return output.toByteArray();
    }

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
