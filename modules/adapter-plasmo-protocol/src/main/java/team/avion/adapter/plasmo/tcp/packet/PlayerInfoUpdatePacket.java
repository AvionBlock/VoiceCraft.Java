package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.data.VoicePlayerInfo;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Clientbound Plasmo packet that updates one player's voice state and display metadata.
 */
public final class PlayerInfoUpdatePacket implements PlasmoPacket {
    private VoicePlayerInfo playerInfo;

    public PlayerInfoUpdatePacket() {
    }

    /**
     * Creates an update packet for the provided player info.
     *
     * @param playerInfo player metadata to publish
     */
    public PlayerInfoUpdatePacket(VoicePlayerInfo playerInfo) {
        this.playerInfo = playerInfo;
    }

    @Override
    public void read(DataInput input) throws IOException {
        throw new UnsupportedOperationException("PlayerInfoUpdatePacket decoding is not used by VoiceCraft.Java");
    }

    @Override
    public void write(DataOutput output) throws IOException {
        playerInfo.write(output);
    }
}
