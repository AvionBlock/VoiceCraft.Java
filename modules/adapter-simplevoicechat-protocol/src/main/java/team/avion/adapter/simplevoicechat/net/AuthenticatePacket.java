package team.avion.adapter.simplevoicechat.net;

import java.util.UUID;

public record AuthenticatePacket(UUID playerId, SimpleVoiceChatSecret secret) implements SimpleVoiceChatUdpPacket {
    @Override
    public SimpleVoiceChatPacketType type() {
        return SimpleVoiceChatPacketType.AUTHENTICATE;
    }
}
