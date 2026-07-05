package team.avion.common;

// import lombok.Getter;
import java.util.Map;
import java.util.HashMap;

public interface BaseVoiceCraftJava {
    public boolean isConnected = false;
    public String host = "";
    public int port = 0;
    public String loginToken = "";
    public Map<String, Boolean> playerBinds = new HashMap<>();
    public String token = "";
    public String lang = "";

    /**
     * Reloads the plugin configuration and initializes connections.
     */
    abstract public void reload();

    /**
     * Connects to a new server.
     *
     * @param host The host to connect to.
     * @param port The port to connect to.
     * @param loginToken The VoiceCraft login token.
     * @return True if connected successfully, otherwise false.
     */
    abstract public Boolean connect(String host, int port, String loginToken);

    /**
     * Reconnects to the server.
     *
     * @param force Indicates whether to force a connection.
     * @return True if connected successfully, otherwise false.
     */
    abstract public Boolean reconnect(Boolean force);
    
    /**
     * Disconnects from the server.
     *
     * @param reason The reason why we disconnected
     */
    abstract public void disconnect(String reason);
    
    /**
     * Disconnects from the server.
     */
    abstract public void disconnect();

    /**
     * Bind a fake player
     * @param bindKey
     * @param name
     * @param tries
     * @return
     */
    abstract public Boolean bindFake(int bindKey, String name, int tries);
    
    /**
     * Bind a fake player
     * @param bindKey
     * @param name
     * @return
     */
    abstract public Boolean bindFake(int bindKey, String name);

    /**
     * Updates the voice chat settings.
     *
     * @param proximityDistance Proximity distance setting.
     * @param proximityToggle   Proximity toggle setting.
     * @param voiceEffects      Voice effects setting.
     * @return True if settings were updated successfully, otherwise false.
     */
    abstract public Boolean updateSettings(int proximityDistance, Boolean proximityToggle, Boolean voiceEffects);

    /**
     * Allows the TaskRunner to set the connected state to false
     */
    abstract public void setNotConnected();

    abstract public void saveResource(String resourcePath);

    abstract public void saveConfig();
    abstract public void reloadConfig();
}
