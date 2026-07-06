package team.avion.adapter.simplevoicechat.net;

import java.net.SocketAddress;
import java.util.Arrays;
import java.util.UUID;

public final class SimpleVoiceChatUdpCodec {
    public static final byte MAGIC = (byte) 0xFF;

    public SimpleVoiceChatUdpEnvelope decodeClient(byte[] data, SocketAddress remoteAddress, SecretResolver resolver) throws Exception {
        SimpleVoiceChatBuffer outer = new SimpleVoiceChatBuffer(data);
        if (outer.readByte() != MAGIC) {
            throw new IllegalArgumentException("Invalid Simple Voice Chat UDP magic");
        }

        UUID playerId = outer.readUuid();
        SimpleVoiceChatSecret secret = resolver.secretFor(playerId);
        if (secret == null) {
            throw new IllegalArgumentException("No Simple Voice Chat secret for " + playerId);
        }

        byte[] encrypted = outer.readByteArray(65535);
        SimpleVoiceChatBuffer inner = new SimpleVoiceChatBuffer(secret.decrypt(encrypted));
        SimpleVoiceChatPacketType type = SimpleVoiceChatPacketType.byId(Byte.toUnsignedInt(inner.readByte()));
        return new SimpleVoiceChatUdpEnvelope(playerId, readPacket(type, inner), remoteAddress);
    }

    public byte[] encodeServer(SimpleVoiceChatUdpPacket packet, SimpleVoiceChatSecret secret) throws Exception {
        SimpleVoiceChatBuffer inner = new SimpleVoiceChatBuffer();
        inner.writeByte(packet.type().id());
        writePacket(packet, inner);

        byte[] encrypted = secret.encrypt(inner.toByteArray());
        SimpleVoiceChatBuffer outer = new SimpleVoiceChatBuffer();
        outer.writeByte(MAGIC);
        outer.writeByteArray(encrypted);
        return outer.toByteArray();
    }

    private SimpleVoiceChatUdpPacket readPacket(SimpleVoiceChatPacketType type, SimpleVoiceChatBuffer input) {
        return switch (type) {
            case MIC -> new MicPacket(input.readByteArray(MicPacket.MAX_OPUS_PAYLOAD_SIZE), input.readLong(), input.readBoolean());
            case AUTHENTICATE -> {
                UUID playerId = input.readUuid();
                yield new AuthenticatePacket(playerId, SimpleVoiceChatSecret.of(input.readBytes(SimpleVoiceChatSecret.SECRET_BYTES)));
            }
            case KEEP_ALIVE, CONNECTION_CHECK, AUTHENTICATE_ACK, CONNECTION_CHECK_ACK -> new SimplePacket(type);
        };
    }

    private void writePacket(SimpleVoiceChatUdpPacket packet, SimpleVoiceChatBuffer output) {
        if (packet instanceof MicPacket micPacket) {
            output.writeByteArray(micPacket.data());
            output.writeLong(micPacket.sequenceNumber());
            output.writeBoolean(micPacket.whispering());
        } else if (packet instanceof AuthenticatePacket authenticatePacket) {
            output.writeUuid(authenticatePacket.playerId());
            output.writeBytes(authenticatePacket.secret().value());
        }
    }

    public interface SecretResolver {
        SimpleVoiceChatSecret secretFor(UUID playerId);
    }
}
