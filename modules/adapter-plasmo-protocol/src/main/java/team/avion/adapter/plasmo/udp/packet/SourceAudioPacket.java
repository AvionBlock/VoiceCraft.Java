package team.avion.adapter.plasmo.udp.packet;

import team.avion.adapter.plasmo.PlasmoPacketUtil;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

public final class SourceAudioPacket extends BaseAudioPacket {
    private UUID sourceId;
    private byte sourceState;
    private short distance;

    public SourceAudioPacket() {
    }

    public SourceAudioPacket(UUID sourceId, byte sourceState, short distance, long sequenceNumber, byte[] data) {
        this.sourceId = sourceId;
        this.sourceState = sourceState;
        this.distance = distance;
        setAudio(sequenceNumber, data);
    }

    public UUID sourceId() {
        return sourceId;
    }

    public byte sourceState() {
        return sourceState;
    }

    public short distance() {
        return distance;
    }

    @Override
    public void read(DataInput input) throws IOException {
        super.read(input);
        sourceId = PlasmoPacketUtil.readUuid(input);
        sourceState = input.readByte();
        distance = input.readShort();
    }

    @Override
    public void write(DataOutput output) throws IOException {
        super.write(output);
        PlasmoPacketUtil.writeUuid(output, sourceId);
        output.writeByte(sourceState);
        output.writeShort(distance);
    }
}
