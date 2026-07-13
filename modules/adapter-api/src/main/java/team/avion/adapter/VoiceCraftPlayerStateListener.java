package team.avion.adapter;

/**
 * Listener for voice control changes shared between adapters.
 */
@FunctionalInterface
public interface VoiceCraftPlayerStateListener {
    /**
     * Called after a client reports a new voice state.
     *
     * @param state current player voice state
     */
    void playerVoiceStateUpdated(VoiceCraftPlayerState state);
}
