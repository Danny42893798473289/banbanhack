package shit.zen;

import asm.patchify.loader.PatchAgent;
import asm.patchify.loader.PatchRegistry;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Mod;
import shit.zen.asm.Bootstrap;
import shit.zen.event.EventBus;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.TickEvent;
import shit.zen.gui.IntroAnimation;
import shit.zen.manager.*;
import shit.zen.patch.*;
import shit.zen.utils.rotation.RotationHandler;

import java.io.*;
import java.lang.reflect.Field;

@Mod(value = "hey")
@Getter
@Setter
public class ZenClient extends ClientBase {
    @Getter
    public static ZenClient instance;
    public static final String CLIENT_NAME = "dannyhack";
    public static final String VERSION = "1.0";
    public static float serverTickRate;
    public static boolean isReady;
    public static boolean isMCPMapped;
    public static String configDir = System.getProperty("user.home") + File.separator + ".dannyhack";
    public static String username = "";

    private static final String[] CLOUD_ASSET_NAMES = {
            "panel.png",
            "ptr.png",
            "lie.wav",
            "truth.wav",

            "kill.wav",
            "chenqiyuan.png",
            "gb.png"
    };

    private EventBus eventBus;
    private RotationHandler rotationHandler;
    private ModuleManager moduleManager;
    private CommandManager commandManager;
    private ConfigManager configManager;
    private HudManager hudManager;
    private LagManager lagManager;
    private TargetManager targetManager;
    private int reconnectAttempts;

    public ZenClient() {
        if (instance == null) {
            instance = this;
            this.init();
        }
    }

    private void init() {
        try {
            username = System.getProperty("user.name", "Player");
            File dir = new File(configDir);
            if (!dir.exists() && !dir.mkdirs()) {
                logger.warn("Failed to create config directory at {}", configDir);
            }
            mc = getMcInstance();
            this.eventBus = new EventBus();
            this.rotationHandler = new RotationHandler();
            this.eventBus.register(this.rotationHandler);
            this.moduleManager = new ModuleManager();
            this.hudManager = new HudManager();
            this.commandManager = new CommandManager();
            this.configManager = new ConfigManager();
            this.extractCloudAssets();
            this.lagManager = new LagManager();
            this.targetManager = new TargetManager();
            this.eventBus.register(this.hudManager);
            this.eventBus.register(this.lagManager);
            this.eventBus.register(this.targetManager);
            this.eventBus.register(this);
            this.commandManager.initCommands();
            this.eventBus.register(new IntroAnimation());
            Bootstrap.init();
            registerPatches();
            if (PatchAgent.getInstrumentation() != null) {
                PatchAgent.installPatchesAndRetransform();
            } else {
                logger.warn("agent not attached. Launch with `./gradlew runClient0` so the agent jvmArg is set.");
            }
            isReady = true;
            logger.info("{} v{} initialized.", CLIENT_NAME, VERSION);
        } catch (Throwable throwable) {
            logger.error(throwable.getMessage(), throwable);
        }
    }

    private boolean moduleInit = false;

    @EventTarget
    public void onTick(TickEvent e) {
        if (isReady() && !moduleInit) {
            moduleInit = true;
            this.moduleManager.initModules();
            this.configManager.loadAll();
        }
    }

    public static boolean isReady() {
        return instance != null
                && ZenClient.instance.eventBus != null
                && isReady
                && mc != null
                && mc.player != null
                && !username.isEmpty()
                && mc.player.tickCount > 5;
    }

    public void shutdown() {
        isReady = false;
        if (this.configManager != null) {
            this.configManager.saveAll();
        }
    }

    private void extractCloudAssets() {
        File targetDir = ConfigManager.CONFIG_DIR;
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            logger.warn("Failed to create config directory at {}", targetDir);
            return;
        }
        for (String name : CLOUD_ASSET_NAMES) {
            File outFile = new File(targetDir, name);
            if (outFile.exists()) continue;
            try (InputStream in = openCloudAsset(name)) {
                if (in == null) {
                    logger.warn("Cloud asset missing on classpath: {}", name);
                    continue;
                }
                try (OutputStream out = new FileOutputStream(outFile)) {
                    in.transferTo(out);
                }
            } catch (IOException ioException) {
                logger.error("Failed to extract cloud asset {}", name, ioException);
            }
        }
    }

    private static InputStream openCloudAsset(String name) {
        String classpath = "/assets/dannyhack/cloud_assets/" + name;
        InputStream is = ZenClient.class.getResourceAsStream(classpath);
        if (is != null) return is;
        String dir = System.getProperty("dannyhack.resources");
        if (dir != null) {
            File f = new File(dir, "assets/dannyhack/cloud_assets/" + name);
            if (f.isFile()) {
                try {
                    return new java.io.FileInputStream(f);
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    public static void registerPatches() {
        PatchRegistry.register(MinecraftPatch.class);
        PatchRegistry.register(LocalPlayerPatch.class);
        PatchRegistry.register(LivingEntityPatch.class);
        PatchRegistry.register(EntityPatch.class);
        PatchRegistry.register(PlayerPatch.class);
        PatchRegistry.register(ClientLevelPatch.class);
        PatchRegistry.register(ConnectionPatch.class);
        PatchRegistry.register(PacketUtilsPatch.class);
        PatchRegistry.register(DisconnectedScreenPatch.class);
        PatchRegistry.register(KeyboardHandlerPatch.class);
        PatchRegistry.register(KeyboardInputPatch.class);
        PatchRegistry.register(ChatScreenPatch.class);
        PatchRegistry.register(EntityRendererPatch.class);
        PatchRegistry.register(LevelRendererPatch.class);
        PatchRegistry.register(CameraPatch.class);
        PatchRegistry.register(BlockPatch.class);
        PatchRegistry.register(GameRendererPatch.class);
        PatchRegistry.register(ItemInHandRendererPatch.class);
        PatchRegistry.register(ItemInHandLayerPatch.class);
        PatchRegistry.register(HumanoidModelPatch.class);
        PatchRegistry.register(LivingEntityRendererPatch.class);
        PatchRegistry.register(ItemPatch.class);
        PatchRegistry.register(PlayerTabOverlayPatch.class);
        PatchRegistry.register(FriendlyByteBufPatch.class);
        PatchRegistry.register(GuiPatch.class);
        //PatchRegistry.register(DisconnectedScreenPatch.class);
        // Compatibility patch for Embeddium/Sodium's BlockOcclusionCache.
        // Always registered so the transformer can catch the class when it
        // first loads. We must NOT use Class.forName() here — that would
        // load the class before our transformer is installed, preventing
        // the patch from ever being applied.
        PatchRegistry.register(BlockOcclusionCachePatch.class);
    }

    public static Minecraft getMcInstance() {
        Minecraft minecraft = null;
        try {
            Class<?> clazz = Minecraft.class;
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getType() != clazz) continue;
                field.setAccessible(true);
                minecraft = (Minecraft) field.get(null);
                field.setAccessible(false);
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return minecraft != null ? minecraft : Minecraft.getInstance();
    }

}
