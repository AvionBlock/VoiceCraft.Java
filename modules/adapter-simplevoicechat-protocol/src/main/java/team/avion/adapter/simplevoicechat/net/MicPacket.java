package team.avion.adapter.simplevoicechat.net;

public record MicPacket(byte[] data, long sequenceNumber, boolean whispering) implements SimpleVoiceChatUdpPacket {
    public static final int MAX_OPUS_PAYLOAD_SIZE = 2048;

    @Override
    public SimpleVoiceChatPacketType type() {
        return SimpleVoiceChatPacketType.MIC;
    }
}
