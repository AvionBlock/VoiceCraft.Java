package team.avion.adapter.plasmo.tcp;

import team.avion.adapter.plasmo.tcp.packet.ConnectionPacket;
import team.avion.adapter.plasmo.tcp.packet.ConfigPacket;
import team.avion.adapter.plasmo.tcp.packet.LanguagePacket;
import team.avion.adapter.plasmo.tcp.packet.LanguageRequestPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerActivationDistancesPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerAudioEndPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerListPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerInfoPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerInfoRequestPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerStatePacket;
import team.avion.adapter.plasmo.tcp.packet.SourceInfoRequestPacket;

public final class PlasmoTcpCodecs {
    private PlasmoTcpCodecs() {
    }

    public static PlasmoTcpCodec createBaseCodec() {
        PlasmoTcpCodec codec = new PlasmoTcpCodec();
        codec.register(PlasmoTcpPacketType.CONNECTION, ConnectionPacket.class, ConnectionPacket::new);
        codec.register(PlasmoTcpPacketType.PLAYER_INFO_REQUEST, PlayerInfoRequestPacket.class, PlayerInfoRequestPacket::new);
        codec.register(PlasmoTcpPacketType.CONFIG, ConfigPacket.class, ConfigPacket::new);
        codec.register(PlasmoTcpPacketType.LANGUAGE_REQUEST, LanguageRequestPacket.class, LanguageRequestPacket::new);
        codec.register(PlasmoTcpPacketType.LANGUAGE, LanguagePacket.class, LanguagePacket::new);
        codec.register(PlasmoTcpPacketType.PLAYER_LIST, PlayerListPacket.class, PlayerListPacket::new);
        codec.register(PlasmoTcpPacketType.PLAYER_INFO, PlayerInfoPacket.class, PlayerInfoPacket::new);
        codec.register(PlasmoTcpPacketType.PLAYER_STATE, PlayerStatePacket.class, PlayerStatePacket::new);
        codec.register(PlasmoTcpPacketType.PLAYER_AUDIO_END, PlayerAudioEndPacket.class, PlayerAudioEndPacket::new);
        codec.register(PlasmoTcpPacketType.PLAYER_ACTIVATION_DISTANCES, PlayerActivationDistancesPacket.class, PlayerActivationDistancesPacket::new);
        codec.register(PlasmoTcpPacketType.SOURCE_INFO_REQUEST, SourceInfoRequestPacket.class, SourceInfoRequestPacket::new);
        return codec;
    }
}
