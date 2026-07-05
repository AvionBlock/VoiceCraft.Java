package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class PlayerListPacket implements PlasmoPacket {
    @Override
    public void read(DataInput input) throws IOException {
        throw new UnsupportedOperationException("PlayerListPacket decoding is not used by VoiceCraft.Java");
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeInt(0);
    }
}
