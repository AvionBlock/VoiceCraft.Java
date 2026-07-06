package team.avion.adapter.plasmo.data;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;

/**
 * Plasmo source line definition shown in the client source volume UI.
 *
 * @param name stable source line name
 * @param translation client translation key
 * @param icon source line icon resource location
 * @param defaultVolume default line volume
 * @param weight display ordering weight
 * @param players optional players assigned to this line
 */
public record VoiceSourceLine(String name, String translation, String icon, double defaultVolume, int weight,
                              Collection<McGameProfile> players) {
    /**
     * Writes the source line in Plasmo's clientbound wire format.
     *
     * @param output destination stream
     * @throws IOException if writing fails
     */
    public void write(DataOutput output) throws IOException {
        output.writeUTF(name);
        output.writeUTF(translation);
        output.writeUTF(icon);
        output.writeDouble(defaultVolume);
        output.writeInt(weight);
        output.writeBoolean(players != null);
        if (players != null) {
            output.writeInt(players.size());
            for (McGameProfile player : players) {
                player.write(output);
            }
        }
    }
}
