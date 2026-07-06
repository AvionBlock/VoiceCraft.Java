package team.avion.adapter.plasmo.data;

import team.avion.adapter.plasmo.PlasmoPacketUtil;

import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

public record McGameProfile(UUID id, String name) {
    public void write(DataOutput output) throws IOException {
        PlasmoPacketUtil.writeUuid(output, id);
        output.writeUTF(name);
        output.writeInt(0);
    }
}
