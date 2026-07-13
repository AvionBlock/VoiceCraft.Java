package team.avion.common.utils;

import net.kyori.adventure.text.Component;
import team.avion.common.localization.Language;

public abstract class BaseLogger {

    protected String language() {
        return null;
    }

    public String localized(String key, String... replacements) {
        return Language.getMessage(language(), key, replacements);
    }

    public void infoLocalized(String key, String... replacements) {
        info(localized(key, replacements));
    }

    public void warnLocalized(String key, String... replacements) {
        warn(localized(key, replacements));
    }

    public void errorLocalized(String key, String... replacements) {
        error(localized(key, replacements));
    }

    public void debugLocalized(String key, String... replacements) {
        debug(localized(key, replacements));
    }

    public void log(Component msg) {
    }

    public void info(String msg) {
    }

    public void warn(String msg) {
    }

    public void error(String msg) {
    }

    public void debug(String msg) {
    }

}
