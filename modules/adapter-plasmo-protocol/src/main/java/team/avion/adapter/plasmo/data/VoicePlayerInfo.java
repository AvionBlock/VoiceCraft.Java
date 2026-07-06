package team.avion.adapter.plasmo.data;

import team.avion.adapter.plasmo.PlasmoPacketUtil;

import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

public record VoicePlayerInfo(UUID playerId, String playerNick, boolean muted, boolean voiceDisabled,
                              boolean microphoneMuted) {
    public void write(DataOutput output) throws IOException {
        PlasmoPacketUtil.writeUuid(output, playerId);
        output.writeUTF(playerNick);
        output.writeBoolean(muted);
        output.writeBoolean(voiceDisabled);
        output.writeBoolean(microphoneMuted);
    }
}
