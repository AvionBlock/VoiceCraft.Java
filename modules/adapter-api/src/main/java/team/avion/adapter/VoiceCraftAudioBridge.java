package team.avion.adapter;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VoiceCraftAudioBridge {
    void sendEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame);

    default void sendEntityAudioEnd(int entityId, long sequenceNumber) {
    }

    default void registerAudioSink(VoiceCraftAudioSink sink) {
    }

    default void unregisterAudioSink(VoiceCraftAudioSink sink) {
    }

    default void registerIdentityListener(VoiceCraftIdentityListener listener) {
    }

    default void unregisterIdentityListener(VoiceCraftIdentityListener listener) {
    }

    default void registerEntityIdentity(VoiceCraftEntityIdentity identity) {
    }

    default void unregisterEntityIdentity(int entityId) {
    }

    default Optional<VoiceCraftEntityIdentity> entityIdentity(int entityId) {
        return Optional.empty();
    }

    default Collection<VoiceCraftEntityIdentity> entityIdentities() {
        return List.of();
    }
}
