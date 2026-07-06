package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.PlasmoPacketUtil;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

public final class PlayerDisconnectPacket implements PlasmoPacket {
    private UUID playerId;

    public PlayerDisconnectPacket() {
    }

    public PlayerDisconnectPacket(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public void read(DataInput input) throws IOException {
        throw new UnsupportedOperationException("PlayerDisconnectPacket decoding is not used by VoiceCraft.Java");
    }

    @Override
    public void write(DataOutput output) throws IOException {
        PlasmoPacketUtil.writeUuid(output, playerId);
    }
}
