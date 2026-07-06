package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.PlasmoPacketUtil;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

/**
 * Clientbound Plasmo packet that marks the end of an audio sequence for a source.
 */
public final class SourceAudioEndPacket implements PlasmoPacket {
    private UUID sourceId;
    private long sequenceNumber;

    public SourceAudioEndPacket() {
    }

    /**
     * Creates an audio-end packet for a source.
     *
     * @param sourceId Plasmo source UUID
     * @param sequenceNumber final source sequence number
     */
    public SourceAudioEndPacket(UUID sourceId, long sequenceNumber) {
        this.sourceId = sourceId;
        this.sequenceNumber = sequenceNumber;
    }

    @Override
    public void read(DataInput input) throws IOException {
        throw new UnsupportedOperationException("SourceAudioEndPacket decoding is not used by VoiceCraft.Java");
    }

    @Override
    public void write(DataOutput output) throws IOException {
        PlasmoPacketUtil.writeUuid(output, sourceId);
        output.writeLong(sequenceNumber);
    }
}
