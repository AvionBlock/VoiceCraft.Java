package team.avion.protocol;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import team.avion.protocol.McPackets.AcceptResponsePacket;
import team.avion.protocol.McPackets.AudioEffect;
import team.avion.protocol.McPackets.ClearEffectsRequestPacket;
import team.avion.protocol.McPackets.CreateEntityRequestPacket;
import team.avion.protocol.McPackets.CreateEntityResponsePacket;
import team.avion.protocol.McPackets.DenyResponsePacket;
import team.avion.protocol.McPackets.DestroyEntityRequestPacket;
import team.avion.protocol.McPackets.DestroyEntityResponsePacket;
import team.avion.protocol.McPackets.DirectionalEffect;
import team.avion.protocol.McPackets.EchoEffect;
import team.avion.protocol.McPackets.EntityAudioRequestPacket;
import team.avion.protocol.McPackets.EventRequestPacket;
import team.avion.protocol.McPackets.LoginRequestPacket;
import team.avion.protocol.McPackets.LogoutRequestPacket;
import team.avion.protocol.McPackets.McApiPacket;
import team.avion.protocol.McPackets.MuffleEffect;
import team.avion.protocol.McPackets.PingRequestPacket;
import team.avion.protocol.McPackets.PingResponsePacket;
import team.avion.protocol.McPackets.ProximityEchoEffect;
import team.avion.protocol.McPackets.ProximityEffect;
import team.avion.protocol.McPackets.ProximityMuffleEffect;
import team.avion.protocol.McPackets.ResetRequestPacket;
import team.avion.protocol.McPackets.ResetResponsePacket;
import team.avion.protocol.McPackets.SetEffectRequestPacket;
import team.avion.protocol.McPackets.SetEntityDeafenRequestPacket;
import team.avion.protocol.McPackets.SetEntityDescriptionRequestPacket;
import team.avion.protocol.McPackets.SetEntityEffectBitmaskRequestPacket;
import team.avion.protocol.McPackets.SetEntityListenBitmaskRequestPacket;
import team.avion.protocol.McPackets.SetEntityMuteRequestPacket;
import team.avion.protocol.McPackets.SetEntityNameRequestPacket;
import team.avion.protocol.McPackets.SetEntityPositionRequestPacket;
import team.avion.protocol.McPackets.SetEntityRotationRequestPacket;
import team.avion.protocol.McPackets.SetEntityTalkBitmaskRequestPacket;
import team.avion.protocol.McPackets.SetEntityTitleRequestPacket;
import team.avion.protocol.McPackets.SetEntityWorldIdRequestPacket;
import team.avion.protocol.McPackets.VisibilityEffect;
import team.avion.protocol.McProtocolEnums.EffectType;
import team.avion.protocol.McProtocolEnums.McApiPacketType;

public final class McProtocolCodec {
    private static final Map<McApiPacketType, Supplier<McApiPacket>> PACKET_FACTORIES = new EnumMap<>(McApiPacketType.class);

    static {
        register(McApiPacketType.LOGIN_REQUEST, LoginRequestPacket::new);
        register(McApiPacketType.LOGOUT_REQUEST, LogoutRequestPacket::new);
        register(McApiPacketType.PING_REQUEST, PingRequestPacket::new);
        register(McApiPacketType.ACCEPT_RESPONSE, AcceptResponsePacket::new);
        register(McApiPacketType.DENY_RESPONSE, DenyResponsePacket::new);
        register(McApiPacketType.PING_RESPONSE, PingResponsePacket::new);
        register(McApiPacketType.EVENT_REQUEST, EventRequestPacket::new);
        register(McApiPacketType.RESET_REQUEST, ResetRequestPacket::new);
        register(McApiPacketType.SET_EFFECT_REQUEST, SetEffectRequestPacket::new);
        register(McApiPacketType.CLEAR_EFFECTS_REQUEST, ClearEffectsRequestPacket::new);
        register(McApiPacketType.CREATE_ENTITY_REQUEST, CreateEntityRequestPacket::new);
        register(McApiPacketType.DESTROY_ENTITY_REQUEST, DestroyEntityRequestPacket::new);
        register(McApiPacketType.ENTITY_AUDIO_REQUEST, EntityAudioRequestPacket::new);
        register(McApiPacketType.SET_ENTITY_TITLE_REQUEST, SetEntityTitleRequestPacket::new);
        register(McApiPacketType.SET_ENTITY_DESCRIPTION_REQUEST, SetEntityDescriptionRequestPacket::new);
        register(McApiPacketType.SET_ENTITY_WORLD_ID_REQUEST, SetEntityWorldIdRequestPacket::new);
        register(McApiPacketType.SET_ENTITY_NAME_REQUEST, SetEntityNameRequestPacket::new);
        register(McApiPacketType.SET_ENTITY_MUTE_REQUEST, SetEntityMuteRequestPacket::new);
        register(McApiPacketType.SET_ENTITY_DEAFEN_REQUEST, SetEntityDeafenRequestPacket::new);
        register(McApiPacketType.SET_ENTITY_TALK_BITMASK_REQUEST, SetEntityTalkBitmaskRequestPacket::new);
        register(McApiPacketType.SET_ENTITY_LISTEN_BITMASK_REQUEST, SetEntityListenBitmaskRequestPacket::new);
        register(McApiPacketType.SET_ENTITY_EFFECT_BITMASK_REQUEST, SetEntityEffectBitmaskRequestPacket::new);
        register(McApiPacketType.SET_ENTITY_POSITION_REQUEST, SetEntityPositionRequestPacket::new);
        register(McApiPacketType.SET_ENTITY_ROTATION_REQUEST, SetEntityRotationRequestPacket::new);
        register(McApiPacketType.RESET_RESPONSE, ResetResponsePacket::new);
        register(McApiPacketType.CREATE_ENTITY_RESPONSE, CreateEntityResponsePacket::new);
        register(McApiPacketType.DESTROY_ENTITY_RESPONSE, DestroyEntityResponsePacket::new);
    }

    private McProtocolCodec() {
    }

    public static byte[] encode(McApiPacket packet) {
        NetDataWriter writer = new NetDataWriter();
        writer.putByte(packet.packetType().ordinal());
        packet.serialize(writer);
        return writer.copyData();
    }

    public static McApiPacket decode(byte[] data) {
        NetDataReader reader = new NetDataReader(data);
        McApiPacketType type = McApiPacketType.fromByte(reader.getByte());
        Supplier<McApiPacket> factory = PACKET_FACTORIES.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("Unsupported packet type: " + type);
        }
        McApiPacket packet = factory.get();
        packet.deserialize(reader);
        return packet;
    }

    public static AudioEffect readEffect(EffectType effectType, NetDataReader reader) {
        if (effectType == null || effectType == EffectType.NONE) {
            return null;
        }
        AudioEffect effect = switch (effectType) {
            case VISIBILITY -> new VisibilityEffect();
            case PROXIMITY -> new ProximityEffect();
            case DIRECTIONAL -> new DirectionalEffect();
            case PROXIMITY_ECHO -> new ProximityEchoEffect();
            case ECHO -> new EchoEffect();
            case PROXIMITY_MUFFLE -> new ProximityMuffleEffect();
            case MUFFLE -> new MuffleEffect();
            case NONE -> null;
        };
        if (effect != null) {
            effect.deserialize(reader);
        }
        return effect;
    }

    private static void register(McApiPacketType type, Supplier<McApiPacket> factory) {
        PACKET_FACTORIES.put(type, factory);
    }
}
