package team.avion.protocol;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class NetDataWriter {
    private byte[] data;
    private int offset;

    public NetDataWriter() {
        this(new byte[0]);
    }

    public NetDataWriter(byte[] initialBuffer) {
        this.data = initialBuffer == null ? new byte[0] : initialBuffer;
        this.offset = 0;
    }

    public int length() {
        return offset;
    }

    public void reset() {
        offset = 0;
    }

    public byte[] copyData() {
        return Arrays.copyOf(data, offset);
    }

    public void putFloat(float value) {
        putBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array(), 0, 4);
    }

    public void putDouble(double value) {
        putBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array(), 0, 8);
    }

    public void putSbyte(int value) {
        ensureCapacity(offset + 1);
        data[offset++] = (byte) value;
    }

    public void putShort(int value) {
        putBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array(), 0, 2);
    }

    public void putInt(int value) {
        putBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(), 0, 4);
    }

    public void putUint(long value) {
        putInt((int) (value & 0xFFFF_FFFFL));
    }

    public void putLong(long value) {
        putBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array(), 0, 8);
    }

    public void putUlong(BigInteger value) {
        if (value == null || value.signum() < 0 || value.bitLength() > 64) {
            throw new IllegalArgumentException("Unsigned long must be between 0 and 2^64-1");
        }
        byte[] bigEndian = value.toByteArray();
        byte[] littleEndian = new byte[8];
        for (int index = 0; index < Math.min(bigEndian.length, 8); index++) {
            littleEndian[index] = bigEndian[bigEndian.length - 1 - index];
        }
        putBytes(littleEndian, 0, littleEndian.length);
    }

    public void putByte(int value) {
        ensureCapacity(offset + 1);
        data[offset++] = (byte) (value & 0xFF);
    }

    public void putUshort(int value) {
        putShort(value & 0xFFFF);
    }

    public void putBool(boolean value) {
        putByte(value ? 1 : 0);
    }

    public void putString(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            putUshort(0);
            return;
        }

        String limited = value;
        if (maxLength > 0 && limited.length() > maxLength) {
            limited = limited.substring(0, maxLength);
        }

        byte[] encoded = limited.getBytes(StandardCharsets.UTF_8);
        int encodedCount = encoded.length + 1;
        if (encodedCount > 0xFFFF) {
            throw new IllegalArgumentException("Exceeded allowed number of encoded bytes");
        }
        putUshort(encodedCount);
        putBytes(encoded, 0, encoded.length);
    }

    public void putBytes(byte[] value, int sourceOffset, int length) {
        ensureCapacity(offset + length);
        System.arraycopy(value, sourceOffset, data, offset, length);
        offset += length;
    }

    private void ensureCapacity(int newSize) {
        if (data.length >= newSize) {
            return;
        }
        int resized = Math.max(newSize, Math.max(1, data.length * 2));
        data = Arrays.copyOf(data, resized);
    }
}
