package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

import team.avion.adapter.plasmo.data.VoicePlayerInfo;

public final class PlayerListPacket implements PlasmoPacket {
    private Collection<VoicePlayerInfo> players = List.of();

    public PlayerListPacket() {
    }

    public PlayerListPacket(Collection<VoicePlayerInfo> players) {
        this.players = players;
    }

    @Override
    public void read(DataInput input) throws IOException {
        throw new UnsupportedOperationException("PlayerListPacket decoding is not used by VoiceCraft.Java");
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeInt(players.size());
        for (VoicePlayerInfo player : players) {
            player.write(output);
        }
    }
}
