package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.data.VoiceSourceLine;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Clientbound Plasmo packet that registers a source line in the client source manager.
 */
public final class SourceLineRegisterPacket implements PlasmoPacket {
    private VoiceSourceLine sourceLine;

    public SourceLineRegisterPacket() {
    }

    /**
     * Creates a registration packet for the provided source line.
     *
     * @param sourceLine source line definition
     */
    public SourceLineRegisterPacket(VoiceSourceLine sourceLine) {
        this.sourceLine = sourceLine;
    }

    @Override
    public void read(DataInput input) throws IOException {
        throw new UnsupportedOperationException("SourceLineRegisterPacket decoding is not used by VoiceCraft.Java");
    }

    @Override
    public void write(DataOutput output) throws IOException {
        sourceLine.write(output);
    }
}
