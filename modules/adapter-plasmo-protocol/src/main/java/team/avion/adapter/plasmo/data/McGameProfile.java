package team.avion.adapter.plasmo.data;

import team.avion.adapter.plasmo.PlasmoPacketUtil;

import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

/**
 * Minimal Minecraft game profile serialized in Plasmo source-line player lists.
 *
 * @param id Minecraft player UUID
 * @param name Minecraft player name
 */
public record McGameProfile(UUID id, String name) {
    /**
     * Writes the profile using Plasmo's game profile wire format.
     *
     * @param output destination stream
     * @throws IOException if writing fails
     */
    public void write(DataOutput output) throws IOException {
        PlasmoPacketUtil.writeUuid(output, id);
        output.writeUTF(name);
        output.writeInt(0);
    }
}
