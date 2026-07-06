package team.avion.adapter.plasmo.data;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;

public record VoiceSourceLine(String name, String translation, String icon, double defaultVolume, int weight,
                              Collection<McGameProfile> players) {
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
