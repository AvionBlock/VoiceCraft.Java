package team.avion.adapter.plasmo.data;

import team.avion.adapter.plasmo.PlasmoPacketUtil;

import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

public record PlayerSourceInfo(String addonId, UUID sourceId, UUID lineId, String sourceName, byte state,
                               boolean stereo, boolean iconVisible, int angle, VoicePlayerInfo playerInfo) {
    public void write(DataOutput output) throws IOException {
        output.writeUTF("PLAYER");
        output.writeUTF(addonId);
        PlasmoPacketUtil.writeUuid(output, sourceId);
        output.writeBoolean(sourceName != null);
        if (sourceName != null) {
            output.writeUTF(sourceName);
        }
        output.writeByte(state);
        output.writeBoolean(false);
        output.writeBoolean(stereo);
        PlasmoPacketUtil.writeUuid(output, lineId);
        output.writeBoolean(iconVisible);
        output.writeInt(angle);
        playerInfo.write(output);
    }
}
