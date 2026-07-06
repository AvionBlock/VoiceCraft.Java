package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class PlayerStatePacket implements PlasmoPacket {
    private boolean voiceDisabled;
    private boolean microphoneMuted;

    public boolean voiceDisabled() {
        return voiceDisabled;
    }

    public boolean microphoneMuted() {
        return microphoneMuted;
    }

    @Override
    public void read(DataInput input) throws IOException {
        voiceDisabled = input.readBoolean();
        microphoneMuted = input.readBoolean();
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeBoolean(voiceDisabled);
        output.writeBoolean(microphoneMuted);
    }
}
