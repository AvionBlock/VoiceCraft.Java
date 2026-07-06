package team.avion.adapter;

/**
 * Consumer of audio frames produced by the shared VoiceCraft audio bridge.
 */
public interface VoiceCraftAudioSink {
    /**
     * Receives one bridged Opus frame for a VoiceCraft entity.
     *
     * @param entityId the VoiceCraft entity id that produced the frame
     * @param timestamp adapter-specific frame timestamp
     * @param loudness approximate frame loudness
     * @param opusFrame encoded Opus frame payload
     */
    void receiveEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame);

    /**
     * Receives the end of a speaking sequence for a VoiceCraft entity.
     *
     * @param entityId the VoiceCraft entity id that stopped speaking
     * @param sequenceNumber source protocol sequence number when available
     */
    default void receiveEntityAudioEnd(int entityId, long sequenceNumber) {
    }
}
