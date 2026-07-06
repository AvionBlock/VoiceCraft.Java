package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.PlasmoPacketUtil;
import team.avion.adapter.plasmo.data.McGameProfile;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;
import java.util.UUID;

/**
 * Clientbound Plasmo packet that replaces the player list attached to a source line.
 */
public final class SourceLinePlayersListPacket implements PlasmoPacket {
    private UUID lineId;
    private Collection<McGameProfile> players;

    public SourceLinePlayersListPacket() {
    }

    /**
     * Creates a source-line player list update.
     *
     * @param lineId Plasmo source line UUID
     * @param players players visible on that line
     */
    public SourceLinePlayersListPacket(UUID lineId, Collection<McGameProfile> players) {
        this.lineId = lineId;
        this.players = players;
    }

    @Override
    public void read(DataInput input) throws IOException {
        throw new UnsupportedOperationException("SourceLinePlayersListPacket decoding is not used by VoiceCraft.Java");
    }

    @Override
    public void write(DataOutput output) throws IOException {
        PlasmoPacketUtil.writeUuid(output, lineId);
        output.writeInt(players.size());
        for (McGameProfile player : players) {
            player.write(output);
        }
    }
}
