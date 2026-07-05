package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.PlasmoPacketUtil;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ConfigPacket implements PlasmoPacket {
    public static final String PROXIMITY_NAME = "proximity";
    public static final UUID PROXIMITY_SOURCE_LINE_ID =
            UUID.nameUUIDFromBytes((PROXIMITY_NAME + "_line").getBytes(StandardCharsets.UTF_8));
    public static final UUID PROXIMITY_ACTIVATION_ID =
            UUID.nameUUIDFromBytes((PROXIMITY_NAME + "_activation").getBytes(StandardCharsets.UTF_8));

    private UUID serverId = UUID.randomUUID();
    private int sampleRate = 48_000;
    private int mtuSize = 1024;
    private int proximityDistance = 30;

    public ConfigPacket() {
    }

    public ConfigPacket(UUID serverId, int proximityDistance) {
        this.serverId = serverId;
        this.proximityDistance = proximityDistance;
    }

    @Override
    public void read(DataInput input) throws IOException {
        throw new UnsupportedOperationException("ConfigPacket decoding is not used by VoiceCraft.Java");
    }

    @Override
    public void write(DataOutput output) throws IOException {
        PlasmoPacketUtil.writeUuid(output, serverId);
        writeCaptureInfo(output);

        output.writeBoolean(false); // no Plasmo AES; VoiceCraft side handles its own transport

        output.writeInt(1);
        writeProximitySourceLine(output);

        output.writeInt(1);
        writeProximityActivation(output);

        writePermissions(output, Map.of(
                "pv.activation.proximity", true,
                "pv.activation.proximity.stereo", true
        ));

        writePlayerIconConfig(output);
    }

    private void writeCaptureInfo(DataOutput output) throws IOException {
        output.writeInt(sampleRate);
        output.writeInt(mtuSize);
        output.writeBoolean(true);
        writeOpusCodec(output);
    }

    private void writeOpusCodec(DataOutput output) throws IOException {
        output.writeUTF("opus");
        output.writeInt(2);
        output.writeUTF("mode");
        output.writeUTF("VOIP");
        output.writeUTF("bitrate");
        output.writeUTF("48000");
    }

    private void writeProximitySourceLine(DataOutput output) throws IOException {
        output.writeUTF(PROXIMITY_NAME);
        output.writeUTF("message.plasmovoice.source_line.proximity");
        output.writeUTF("plasmovoice:textures/icons/speaker.png");
        output.writeDouble(1.0D);
        output.writeInt(0);
        output.writeBoolean(false);
    }

    private void writeProximityActivation(DataOutput output) throws IOException {
        output.writeUTF(PROXIMITY_NAME);
        output.writeUTF("message.plasmovoice.activation.proximity");
        output.writeUTF("plasmovoice:textures/icons/microphone.png");
        writeIntList(output, List.of(-1, proximityDistance));
        output.writeInt(proximityDistance);
        output.writeBoolean(true);
        output.writeBoolean(false);
        output.writeBoolean(true);
        output.writeBoolean(true);
        writeOpusCodec(output);
        output.writeInt(0);
    }

    private void writePermissions(DataOutput output, Map<String, Boolean> permissions) throws IOException {
        output.writeInt(permissions.size());
        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
            output.writeUTF(entry.getKey());
            output.writeBoolean(entry.getValue());
        }
    }

    private void writePlayerIconConfig(DataOutput output) throws IOException {
        output.writeInt(0);
        output.writeDouble(0.0D);
        output.writeDouble(0.0D);
        output.writeDouble(0.0D);
    }

    private void writeIntList(DataOutput output, List<Integer> values) throws IOException {
        output.writeInt(values.size());
        for (Integer value : values) {
            output.writeInt(value);
        }
    }
}
