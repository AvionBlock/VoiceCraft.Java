package team.avion.paper.voicecraft;

import team.avion.adapter.VoiceCraftPlayerState;

/**
 * Status rendered by the vanilla voice overlay.
 */
enum PlayerVoiceStatus {
    DISCONNECTED,
    CONNECTED,
    SPEAKING,
    MUTED,
    DEAFENED;

    static PlayerVoiceStatus resolve(boolean connected, boolean speaking, VoiceCraftPlayerState state) {
        if (!connected) {
            return DISCONNECTED;
        }
        if (state != null && state.voiceDisabled()) {
            return DEAFENED;
        }
        if (state != null && state.microphoneMuted()) {
            return MUTED;
        }
        return speaking ? SPEAKING : CONNECTED;
    }
}
