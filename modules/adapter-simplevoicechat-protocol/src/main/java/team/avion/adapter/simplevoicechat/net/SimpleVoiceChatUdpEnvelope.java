package team.avion.adapter.simplevoicechat.net;

import java.net.SocketAddress;
import java.util.UUID;

public record SimpleVoiceChatUdpEnvelope(UUID playerId, SimpleVoiceChatUdpPacket packet, SocketAddress remoteAddress) {
}
