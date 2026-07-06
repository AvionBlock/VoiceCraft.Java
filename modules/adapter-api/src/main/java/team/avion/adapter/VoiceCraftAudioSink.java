package team.avion.adapter;

public interface VoiceCraftAudioSink {
    void receiveEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame);

    default void receiveEntityAudioEnd(int entityId, long sequenceNumber) {
    }
}
