package team.avion.adapter;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared audio and identity bus used by voice adapters to exchange VoiceCraft entity audio.
 *
 * <p>Adapters publish decoded Opus frames with a VoiceCraft entity id and subscribe as sinks to
 * receive frames produced by other adapters or by the VoiceCraft backend.</p>
 */
public interface VoiceCraftAudioBridge {
    /**
     * Publishes a single Opus frame for a VoiceCraft entity.
     *
     * @param entityId the VoiceCraft entity id that produced the frame
     * @param timestamp adapter-specific frame timestamp
     * @param loudness approximate frame loudness
     * @param opusFrame encoded Opus frame payload
     */
    void sendEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame);

    /**
     * Publishes the end of a speaking sequence for a VoiceCraft entity.
     *
     * @param entityId the VoiceCraft entity id that stopped speaking
     * @param sequenceNumber source protocol sequence number when available
     */
    default void sendEntityAudioEnd(int entityId, long sequenceNumber) {
    }

    /**
     * Publishes explicit activity state for a VoiceCraft entity.
     *
     * @param entityId the VoiceCraft entity id whose activity changed
     * @param state new voice activity state
     */
    default void sendEntityVoiceActivity(int entityId, VoiceActivityState state) {
    }

    /**
     * Registers an adapter that should receive bridged entity audio.
     *
     * @param sink audio sink to register
     */
    default void registerAudioSink(VoiceCraftAudioSink sink) {
    }

    /**
     * Unregisters a previously registered audio sink.
     *
     * @param sink audio sink to unregister
     */
    default void unregisterAudioSink(VoiceCraftAudioSink sink) {
    }

    /**
     * Registers a listener for entity identity lifecycle changes.
     *
     * @param listener listener to register
     */
    default void registerIdentityListener(VoiceCraftIdentityListener listener) {
    }

    /**
     * Unregisters a previously registered identity listener.
     *
     * @param listener listener to unregister
     */
    default void unregisterIdentityListener(VoiceCraftIdentityListener listener) {
    }

    /**
     * Registers a listener for client voice control changes.
     *
     * @param listener listener to register
     */
    default void registerPlayerStateListener(VoiceCraftPlayerStateListener listener) {
    }

    /**
     * Unregisters a client voice control listener.
     *
     * @param listener listener to unregister
     */
    default void unregisterPlayerStateListener(VoiceCraftPlayerStateListener listener) {
    }

    /**
     * Publishes voice controls explicitly reported by an adapter client.
     *
     * @param state current player voice state
     */
    default void updatePlayerVoiceState(VoiceCraftPlayerState state) {
    }

    /**
     * Looks up the last voice controls reported for a player.
     *
     * @param playerId Minecraft player UUID
     * @return reported state, if known
     */
    default Optional<VoiceCraftPlayerState> playerVoiceState(UUID playerId) {
        return Optional.empty();
    }

    /**
     * Associates a VoiceCraft entity id with a Minecraft player identity.
     *
     * @param identity entity identity to register
     */
    default void registerEntityIdentity(VoiceCraftEntityIdentity identity) {
    }

    /**
     * Removes a VoiceCraft entity identity.
     *
     * @param entityId VoiceCraft entity id to remove
     */
    default void unregisterEntityIdentity(int entityId) {
    }

    /**
     * Looks up the player identity associated with a VoiceCraft entity id.
     *
     * @param entityId VoiceCraft entity id
     * @return registered identity, if known
     */
    default Optional<VoiceCraftEntityIdentity> entityIdentity(int entityId) {
        return Optional.empty();
    }

    /**
     * Returns all currently known VoiceCraft entity identities.
     *
     * @return immutable collection of registered identities
     */
    default Collection<VoiceCraftEntityIdentity> entityIdentities() {
        return List.of();
    }
}
