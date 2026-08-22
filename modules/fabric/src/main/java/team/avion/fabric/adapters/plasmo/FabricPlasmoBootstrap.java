package team.avion.fabric.adapters.plasmo;

import su.plo.voice.api.server.PlasmoVoiceServer;

public final class FabricPlasmoBootstrap {
    private FabricPlasmoBootstrap() { }

    public static void load() {
        PlasmoVoiceServer.getAddonsLoader().load(new FabricPlasmoVoiceBridge());
    }
}
