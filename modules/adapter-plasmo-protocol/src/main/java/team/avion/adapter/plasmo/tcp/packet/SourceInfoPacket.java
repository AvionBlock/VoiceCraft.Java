package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.data.PlayerSourceInfo;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class SourceInfoPacket implements PlasmoPacket {
    private PlayerSourceInfo sourceInfo;

    public SourceInfoPacket() {
    }

    public SourceInfoPacket(PlayerSourceInfo sourceInfo) {
        this.sourceInfo = sourceInfo;
    }

    public PlayerSourceInfo sourceInfo() {
        return sourceInfo;
    }

    @Override
    public void read(DataInput input) throws IOException {
        throw new UnsupportedOperationException("SourceInfoPacket decoding is not used by VoiceCraft.Java");
    }

    @Override
    public void write(DataOutput output) throws IOException {
        sourceInfo.write(output);
    }
}
