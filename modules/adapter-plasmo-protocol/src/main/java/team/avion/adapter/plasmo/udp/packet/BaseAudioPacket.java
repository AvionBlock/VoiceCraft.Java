package team.avion.adapter.plasmo.udp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.PlasmoPacketUtil;
import team.avion.adapter.plasmo.PlasmoProtocolConstants;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public abstract class BaseAudioPacket implements PlasmoPacket {
    protected long sequenceNumber;
    protected byte[] data = new byte[0];

    public long sequenceNumber() {
        return sequenceNumber;
    }

    public byte[] data() {
        return data;
    }

    protected void setAudio(long sequenceNumber, byte[] data) {
        this.sequenceNumber = sequenceNumber;
        this.data = data == null ? new byte[0] : data;
    }

    @Override
    public void read(DataInput input) throws IOException {
        sequenceNumber = input.readLong();
        data = PlasmoPacketUtil.readBytes(input, 1, PlasmoProtocolConstants.MAX_AUDIO_BYTES);
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeLong(sequenceNumber);
        PlasmoPacketUtil.writeBytes(output, data);
    }
}
