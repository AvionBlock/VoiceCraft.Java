package team.avion.adapter.plasmo.data;

import team.avion.adapter.plasmo.PlasmoPacketUtil;

import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

/**
 * Plasmo player source descriptor used by clients to create an audio source for a player.
 *
 * @param addonId Plasmo addon id that owns the source
 * @param sourceId source UUID, matching the player UUID for player sources
 * @param lineId source line UUID
 * @param sourceName visible source name
 * @param state source state byte used by Plasmo clients to reject stale packets
 * @param stereo whether the source uses stereo audio
 * @param iconVisible whether the source icon should be displayed
 * @param angle directional source angle
 * @param playerInfo player metadata attached to the source
 */
public record PlayerSourceInfo(String addonId, UUID sourceId, UUID lineId, String sourceName, byte state,
                               boolean stereo, boolean iconVisible, int angle, VoicePlayerInfo playerInfo) {
    /**
     * Writes a Plasmo {@code PLAYER} source info payload.
     *
     * @param output destination stream
     * @throws IOException if writing fails
     */
    public void write(DataOutput output) throws IOException {
        output.writeUTF("PLAYER");
        output.writeUTF(addonId);
        PlasmoPacketUtil.writeUuid(output, sourceId);
        output.writeBoolean(sourceName != null);
        if (sourceName != null) {
            output.writeUTF(sourceName);
        }
        output.writeByte(state);
        // Plasmo clients need decoder info here; otherwise Opus frames are treated as raw PCM.
        output.writeBoolean(true);
        writeOpusCodec(output);
        output.writeBoolean(stereo);
        PlasmoPacketUtil.writeUuid(output, lineId);
        output.writeBoolean(iconVisible);
        output.writeInt(angle);
        playerInfo.write(output);
    }

    private void writeOpusCodec(DataOutput output) throws IOException {
        output.writeUTF("opus");
        output.writeInt(2);
        output.writeUTF("mode");
        output.writeUTF("VOIP");
        output.writeUTF("bitrate");
        output.writeUTF("48000");
    }
}
