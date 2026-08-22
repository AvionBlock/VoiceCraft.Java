package team.avion.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
public final class ProxyMessageCodec {
    public static final String CHANNEL_NAME = "voicecraft-java:main";

    public static final String SNAPSHOT = "PlayerSnapshot";
    public static final String BIND_REQUEST = "BindRequest";
    public static final String UNBIND_REQUEST = "UnbindRequest";
    public static final String BIND_SYNC = "BindSync";

    private ProxyMessageCodec() {
    }

    public static byte[] encodeSnapshot(ProxyPlayerSnapshot snapshot) {
        return write(writer -> {
            writer.writeUTF(SNAPSHOT);
            writer.writeUTF(snapshot.playerId());
            writer.writeUTF(snapshot.playerName());
            writer.writeUTF(snapshot.dimensionId());
            writer.writeDouble(snapshot.x());
            writer.writeDouble(snapshot.y());
            writer.writeDouble(snapshot.z());
            writer.writeDouble(snapshot.rotation());
            writer.writeDouble(snapshot.echoFactor());
            writer.writeBoolean(snapshot.muffled());
            writer.writeBoolean(snapshot.dead());
        });
    }

    public static ProxyPlayerSnapshot decodeSnapshot(byte[] message) {
        return read(message, reader -> {
            expect(reader.readUTF(), SNAPSHOT);
            return new ProxyPlayerSnapshot(
                    reader.readUTF(),
                    reader.readUTF(),
                    reader.readUTF(),
                    reader.readDouble(),
                    reader.readDouble(),
                    reader.readDouble(),
                    reader.readDouble(),
                    reader.readDouble(),
                    reader.readBoolean(),
                    reader.readBoolean());
        });
    }

    public static byte[] encodeBindRequest(String playerId, String playerName, int bindingKey) {
        return write(writer -> {
            writer.writeUTF(BIND_REQUEST);
            writer.writeUTF(playerId);
            writer.writeUTF(playerName);
            writer.writeInt(bindingKey);
        });
    }

    public static BindRequest decodeBindRequest(byte[] message) {
        return read(message, reader -> {
            expect(reader.readUTF(), BIND_REQUEST);
            return new BindRequest(reader.readUTF(), reader.readUTF(), reader.readInt());
        });
    }

    public static byte[] encodeUnbindRequest(String playerId, String playerName) {
        return write(writer -> {
            writer.writeUTF(UNBIND_REQUEST);
            writer.writeUTF(playerId);
            writer.writeUTF(playerName);
        });
    }

    public static UnbindRequest decodeUnbindRequest(byte[] message) {
        return read(message, reader -> {
            expect(reader.readUTF(), UNBIND_REQUEST);
            return new UnbindRequest(reader.readUTF(), reader.readUTF());
        });
    }

    public static byte[] encodeBindSync(String playerName, boolean bound) {
        return write(writer -> {
            writer.writeUTF(BIND_SYNC);
            writer.writeUTF(playerName);
            writer.writeBoolean(bound);
        });
    }

    public static BindSync decodeBindSync(byte[] message) {
        return read(message, reader -> {
            expect(reader.readUTF(), BIND_SYNC);
            return new BindSync(reader.readUTF(), reader.readBoolean());
        });
    }

    public static String peekType(byte[] message) {
        return read(message, input -> input.readUTF());
    }

    private static byte[] write(Writer writer) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (DataOutputStream dataOutput = new DataOutputStream(output)) {
                writer.write(dataOutput);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode proxy message.", exception);
        }
    }

    private static <T> T read(byte[] message, Reader<T> reader) {
        try (DataInputStream dataInput = new DataInputStream(new ByteArrayInputStream(message))) {
            return reader.read(dataInput);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to decode proxy message.", exception);
        }
    }

    private static void expect(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Unexpected proxy message type: " + actual + ", expected " + expected);
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(DataInputStream input) throws IOException;
    }

    public record BindRequest(String playerId, String playerName, int bindingKey) {
    }

    public record UnbindRequest(String playerId, String playerName) {
    }

    public record BindSync(String playerName, boolean bound) {
    }

}
