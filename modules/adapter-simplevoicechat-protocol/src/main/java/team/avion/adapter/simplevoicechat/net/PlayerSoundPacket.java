package team.avion.adapter.simplevoicechat.net;

import java.util.UUID;

public record PlayerSoundPacket(UUID channelId, UUID sender, byte[] data, long sequenceNumber,
                                boolean whispering, float distance) implements SimpleVoiceChatUdpPacket {
    @Override
    public SimpleVoiceChatPacketType type() {
        return SimpleVoiceChatPacketType.PLAYER_SOUND;
    }
}
