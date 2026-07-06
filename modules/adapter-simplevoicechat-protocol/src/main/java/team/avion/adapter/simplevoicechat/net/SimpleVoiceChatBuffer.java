package team.avion.adapter.simplevoicechat.net;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class SimpleVoiceChatBuffer {
    private final ByteArrayOutputStream output;
    private final byte[] input;
    private int index;

    public SimpleVoiceChatBuffer() {
        this.output = new ByteArrayOutputStream();
        this.input = null;
    }

    public SimpleVoiceChatBuffer(byte[] input) {
        this.output = null;
        this.input = input;
    }

    public int readInt() {
        require(4);
        int value = ByteBuffer.wrap(input, index, 4).getInt();
        index += 4;
        return value;
    }

    public long readLong() {
        require(8);
        long value = ByteBuffer.wrap(input, index, 8).getLong();
        index += 8;
        return value;
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public byte readByte() {
        require(1);
        return input[index++];
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public UUID readUuid() {
        return new UUID(readLong(), readLong());
    }

    public byte[] readBytes(int length) {
        require(length);
        byte[] data = new byte[length];
        System.arraycopy(input, index, data, 0, length);
        index += length;
        return data;
    }

    public byte[] readByteArray(int maxLength) {
        int length = readVarInt();
        if (length < 0 || length > maxLength) {
            throw new IllegalArgumentException("Invalid byte array length " + length);
        }
        return readBytes(length);
    }

    public String readUtf(int maxChars) {
        int length = readVarInt();
        if (length < 0 || length > maxChars * 4) {
            throw new IllegalArgumentException("Invalid UTF-8 length " + length);
        }
        String value = new String(readBytes(length), StandardCharsets.UTF_8);
        if (value.length() > maxChars) {
            throw new IllegalArgumentException("String length exceeds " + maxChars);
        }
        return value;
    }

    public int readVarInt() {
        int value = 0;
        int position = 0;
        byte current;
        do {
            current = readByte();
            value |= (current & 0x7F) << position;
            position += 7;
            if (position > 35) {
                throw new IllegalArgumentException("VarInt too big");
            }
        } while ((current & 0x80) == 0x80);
        return value;
    }

    public int readableBytes() {
        return input.length - index;
    }

    public void writeInt(int value) {
        write(ByteBuffer.allocate(4).putInt(value).array());
    }

    public void writeLong(long value) {
        write(ByteBuffer.allocate(8).putLong(value).array());
    }

    public void writeDouble(double value) {
        writeLong(Double.doubleToLongBits(value));
    }

    public void writeByte(int value) {
        output.write(value);
    }

    public void writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
    }

    public void writeUuid(UUID value) {
        writeLong(value.getMostSignificantBits());
        writeLong(value.getLeastSignificantBits());
    }

    public void writeBytes(byte[] value) {
        write(value);
    }

    public void writeByteArray(byte[] value) {
        writeVarInt(value.length);
        writeBytes(value);
    }

    public void writeUtf(String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(encoded.length);
        writeBytes(encoded);
    }

    public void writeVarInt(int value) {
        while ((value & -128) != 0) {
            writeByte(value & 127 | 128);
            value >>>= 7;
        }
        writeByte(value);
    }

    public byte[] toByteArray() {
        return output.toByteArray();
    }

    private void write(byte[] data) {
        output.write(data, 0, data.length);
    }

    private void require(int bytes) {
        if (readableBytes() < bytes) {
            throw new IllegalArgumentException("Unexpected end of Simple Voice Chat packet");
        }
    }
}
