package team.avion.fabric.utils;

import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import team.avion.common.utils.BaseLogger;
import team.avion.fabric.VoiceCraftJava;

public final class FabricLogger extends BaseLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoiceCraft.Java");

    @Override
    protected String language() {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        return plugin == null ? null : plugin.getLanguage();
    }

    @Override
    public void log(Component msg) {
        info(msg.toString());
    }

    @Override
    public void info(String msg) {
        LOGGER.info(msg);
    }

    @Override
    public void warn(String msg) {
        LOGGER.warn(msg);
    }

    @Override
    public void error(String msg) {
        LOGGER.error(msg);
    }

    @Override
    public void debug(String msg) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (plugin != null && plugin.getConfig().getBoolean("config.debug", false)) {
            LOGGER.debug(msg);
        }
    }
}
