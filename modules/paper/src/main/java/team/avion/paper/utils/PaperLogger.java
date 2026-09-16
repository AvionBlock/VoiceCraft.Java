package team.avion.paper.utils;

import team.avion.common.utils.BaseLogger;
import team.avion.paper.VoiceCraftJava;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PaperLogger extends BaseLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoiceCraft.Java");

    @Override
    protected String language() {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        return plugin == null ? null : plugin.getLanguage();
    }

    public void log(Component msg) {
        info(msg.toString());
    }

    public void info(String msg) {
        LOGGER.info(msg);
    }

    public void warn(String msg) {
        LOGGER.warn(msg);
    }

    public void error(String msg) {
        LOGGER.error(msg);
    }

    public void debug(String msg) {
        Boolean isDebug = VoiceCraftJava.getInstance().getConfig().getBoolean("config.debug");
        if (isDebug) {
            LOGGER.debug(msg);
        }
    }
}
