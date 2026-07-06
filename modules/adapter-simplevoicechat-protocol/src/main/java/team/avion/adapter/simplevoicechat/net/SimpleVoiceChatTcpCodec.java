package team.avion.adapter.simplevoicechat.net;

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
}
