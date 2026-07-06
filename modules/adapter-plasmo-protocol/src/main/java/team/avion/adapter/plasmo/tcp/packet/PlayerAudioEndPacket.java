package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.PlasmoPacketUtil;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

public final class PlayerAudioEndPacket implements PlasmoPacket {
    private long sequenceNumber;
    private UUID activationId;
    private short distance;

    public long sequenceNumber() {
        return sequenceNumber;
    }

    public UUID activationId() {
        return activationId;
    }

    public short distance() {
        return distance;
    }

    @Override
    public void read(DataInput input) throws IOException {
        sequenceNumber = input.readLong();
        activationId = PlasmoPacketUtil.readUuid(input);
        distance = input.readShort();
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeLong(sequenceNumber);
        PlasmoPacketUtil.writeUuid(output, activationId);
        output.writeShort(distance);
    }
}
