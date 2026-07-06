package team.avion.adapter;

/**
 * Listener for VoiceCraft entity identity lifecycle changes.
 */
public interface VoiceCraftIdentityListener {
    /**
     * Called after an entity identity becomes visible to adapters.
     *
     * @param identity registered entity identity
     */
    void entityIdentityRegistered(VoiceCraftEntityIdentity identity);

    /**
     * Called after an entity identity is removed from the bridge.
     *
     * @param identity removed entity identity
     */
    void entityIdentityUnregistered(VoiceCraftEntityIdentity identity);
}
