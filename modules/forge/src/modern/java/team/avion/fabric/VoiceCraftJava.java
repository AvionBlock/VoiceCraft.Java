package team.avion.fabric;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import team.avion.fabric.tasks.FabricPositionsTask;
import team.avion.fabric.utils.FabricLogger;
import team.avion.fabric.voicecraft.FabricVoiceCraftProcessManager;
import team.avion.fabric.voicecraft.FabricVoiceCraftSessionManager;
import team.avion.fabric.voicecraft.FabricVoiceEndpointRegistry;
import team.avion.mod.AbstractVoiceCraftMod;
import team.avion.mod.ManagedModDependencyInstaller;
import team.avion.mod.ModCommandRegistrar;
import team.avion.mod.ModPermissions;

import java.lang.reflect.Method;

@Mod("voicecraft_java")
public final class VoiceCraftJava extends AbstractVoiceCraftMod {
    private static VoiceCraftJava instance;
    private MinecraftServer minecraftServer;

    public VoiceCraftJava() {
        super(FMLPaths.CONFIGDIR.get().resolve("voicecraft-java"), new FabricLogger());
        instance = this;
        initializeRuntime();
        ServerStartedEvent.BUS.addListener(event -> {
            minecraftServer = event.getServer();
            serverStarted(minecraftServer);
        });
        ServerStoppingEvent.BUS.addListener(event -> {
            serverStopping();
            minecraftServer = null;
        });
        RegisterCommandsEvent.BUS.addListener(event -> ModCommandRegistrar.register(event.getDispatcher(), this,
                (source, permission, level) -> ModPermissions.isOperator(source, level)));
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> {
            MinecraftServer server = minecraftServer;
            if (server != null) {
                serverTick(server);
            }
        });
    }

    public static VoiceCraftJava getInstance() { return instance; }
    @Override protected boolean isModLoaded(String modId) { return modLoaded(modId); }
    @Override protected void installMissingDependencies() {
        new ManagedModDependencyInstaller(this, "forge", FMLPaths.GAMEDIR.get().resolve("mods"),
                VoiceCraftJava::modLoaded).installMissingEnabledDependencies();
    }
    @Override protected FabricVoiceCraftSessionManager createSessionManager() { return new FabricVoiceCraftSessionManager(this); }
    @Override protected FabricVoiceEndpointRegistry createVoiceEndpointRegistry() { return new FabricVoiceEndpointRegistry(this); }
    @Override protected FabricVoiceCraftProcessManager createProcessManager() { return new FabricVoiceCraftProcessManager(this); }
    @Override protected FabricPositionsTask createPositionsTask() { return new FabricPositionsTask(this); }

    private static boolean modLoaded(String modId) {
        try {
            Method current = ModList.class.getMethod("isLoaded", String.class);
            return (boolean) current.invoke(null, modId);
        } catch (NoSuchMethodException ignored) {
            try {
                Object list = ModList.class.getMethod("get").invoke(null);
                return (boolean) list.getClass().getMethod("isLoaded", String.class).invoke(list, modId);
            } catch (ReflectiveOperationException exception) {
                return false;
            }
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }
}
