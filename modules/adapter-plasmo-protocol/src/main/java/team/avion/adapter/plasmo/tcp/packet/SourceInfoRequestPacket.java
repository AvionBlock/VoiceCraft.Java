package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.PlasmoPacketUtil;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

public final class SourceInfoRequestPacket implements PlasmoPacket {
    private UUID sourceId;

    public UUID sourceId() {
        return sourceId;
    }

    @Override
    public void read(DataInput input) throws IOException {
        sourceId = PlasmoPacketUtil.readUuid(input);
    }

    @Override
    public void write(DataOutput output) throws IOException {
        PlasmoPacketUtil.writeUuid(output, sourceId);
    }
}
