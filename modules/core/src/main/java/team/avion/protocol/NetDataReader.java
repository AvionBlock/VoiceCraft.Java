package team.avion.protocol;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class NetDataReader {
    private byte[] data;
    private int dataSize;
    private int offset;

    public NetDataReader() {
        clear();
    }

    public NetDataReader(byte[] buffer) {
        setBufferSource(buffer);
    }

    public void setBufferSource(byte[] buffer) {
        data = buffer == null ? new byte[0] : buffer;
        dataSize = data.length;
        offset = 0;
    }

    public void clear() {
        data = new byte[0];
        dataSize = 0;
        offset = 0;
    }

    public int availableBytes() {
        return dataSize - offset;
    }

    public byte[] copyData() {
        return Arrays.copyOf(data, dataSize);
    }

    public float getFloat() {
        return littleEndian(4).getFloat();
    }

    public double getDouble() {
        return littleEndian(8).getDouble();
    }

    public int getSbyte() {
        return data[offset++];
    }

    public short getShort() {
        return littleEndian(2).getShort();
    }

    public int getInt() {
        return littleEndian(4).getInt();
    }

    public long getUint() {
        return Integer.toUnsignedLong(getInt());
    }

    public long getLong() {
        return littleEndian(8).getLong();
    }

    public BigInteger getUlong() {
        byte[] littleEndian = new byte[8];
        getBytes(littleEndian, littleEndian.length);
        byte[] bigEndian = new byte[9];
        for (int index = 0; index < littleEndian.length; index++) {
            bigEndian[bigEndian.length - 1 - index] = littleEndian[index];
        }
        return new BigInteger(bigEndian);
    }

    public int getByte() {
        return data[offset++] & 0xFF;
    }

    public int getUshort() {
        return getShort() & 0xFFFF;
    }

    public boolean getBool() {
        return getByte() == 1;
    }

    public String getString(int maxLength) {
        int encodedCount = getUshort();
        if (encodedCount == 0) {
            return "";
        }

        int count = encodedCount - 1;
        if (count < 0 || count > availableBytes()) {
            throw new IllegalStateException("Invalid string size in packet");
        }
        String value = new String(data, offset, count, StandardCharsets.UTF_8);
        offset += count;
        if (maxLength > 0 && value.length() > maxLength) {
            return "";
        }
        return value;
    }

    public void getBytes(byte[] destination, int length) {
        System.arraycopy(data, offset, destination, 0, length);
        offset += length;
    }

    private ByteBuffer littleEndian(int width) {
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, width).order(ByteOrder.LITTLE_ENDIAN);
        offset += width;
        return buffer;
    }
}
