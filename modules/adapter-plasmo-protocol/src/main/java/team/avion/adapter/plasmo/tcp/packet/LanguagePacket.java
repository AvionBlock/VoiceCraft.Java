package team.avion.adapter.plasmo.tcp.packet;

import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.PlasmoPacketUtil;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Map;

public final class LanguagePacket implements PlasmoPacket {
    private String languageName = "en_us";
    private Map<String, String> language = Map.of();

    public LanguagePacket() {
    }

    public LanguagePacket(String languageName, Map<String, String> language) {
        this.languageName = languageName;
        this.language = language;
    }

    @Override
    public void read(DataInput input) throws IOException {
        throw new UnsupportedOperationException("LanguagePacket decoding is not used by VoiceCraft.Java");
    }

    @Override
    public void write(DataOutput output) throws IOException {
        PlasmoPacketUtil.writeString(output, languageName);
        output.writeInt(language.size());
        for (Map.Entry<String, String> entry : language.entrySet()) {
            PlasmoPacketUtil.writeString(output, entry.getKey());
            PlasmoPacketUtil.writeString(output, entry.getValue());
        }
    }
}
