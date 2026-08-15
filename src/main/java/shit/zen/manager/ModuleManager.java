package shit.zen.manager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import shit.zen.ClientBase;
import shit.zen.ZenClient;
import shit.zen.event.impl.KeyEvent;
import shit.zen.exception.ModuleNotFoundException;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.modules.impl.combat.*;
import shit.zen.modules.impl.exploit.Disabler;
import shit.zen.modules.impl.exploit.FastPlace;
import shit.zen.modules.impl.misc.*;
import shit.zen.modules.impl.movement.*;
import shit.zen.modules.impl.player.*;
import shit.zen.modules.impl.render.*;
import shit.zen.modules.impl.world.*;
import shit.zen.event.EventTarget;

public class ModuleManager extends ClientBase {
    private final Map<String, Module> moduleMap = new ConcurrentHashMap<>();

    public ModuleManager() {
        ZenClient.getInstance().getEventBus().register(this);
    }

    public void initModules() {
        // Combat
        this.register(new AntiBots());
        this.register(new AntiFireball());
        this.register(new AntiKB());
        this.register(new AutoOffHand());
        this.register(new AutoSoup());
        this.register(new AutoThrow());
        this.register(new Backtrack());
        this.register(new Critical());
        this.register(new CrystalAura());
        this.register(new KillAura());
        this.register(new FakeLag());
        this.register(new NotiSound());

        // Exploit
        this.register(new FreeCam());
        this.register(new Disabler());
        this.register(new FastPlace());

        // Misc
        this.register(new AimAssist());
        this.register(new AutoClicker());
        this.register(new AutoRod());
        this.register(new SafeWalk());
        //this.register(new Protocol());
        this.register(new MusicPlayer());

        // Movement
        this.register(new CollisionSpeed());
        this.register(new NoSlow());
        this.register(new FastWeb());
        this.register(new FireballBlink());
        this.register(new Fly());
        this.register(new GuiMove());
        this.register(new HighJump());
        this.register(new NoDelay());
        this.register(new NoPush());
        this.register(new Scaffold());
        this.register(new Sprint());
        this.register(new TargetStrafe());

        // Player
        this.register(new AntiTNT());
        this.register(new AntiVoid());
        this.register(new AntiWeb());
        this.register(new AutoMLG());
        this.register(new AutoWebPlace());
        this.register(new ChestStealer());
        this.register(new GhostHand());
        this.register(new Helper());
        this.register(new InventoryManager());
        this.register(new MidPearl());
        this.register(new NoFall());
        this.register(new Stuck());
        this.register(new VoidHub()); // 操你妈Gemini做的还没claude牛逼

        // Render
        this.register(new AspectRatio());
        this.register(new LyricsModule());
        this.register(new CameraView());
        this.register(new ChestESP());
        this.register(new ClickGuiModule());
        this.register(new Compass());
        this.register(new DamageGlow());
        this.register(new BoardReplace());
        this.register(new ESP());

        this.register(new FullBright());
        this.register(new Interface());
        this.register(new ItemTags());
        this.register(new NameProtect());
        this.register(new NameTags());
        this.register(new NoHurtCam());
        this.register(new IRC());
        this.register(new OldHitting());
        this.register(new Projectiles());
        this.register(new Watermark());
        this.register(new XRay());
        this.register(new SwordNotifier());
        this.register(new KillEffect());
        this.register(new FakeTime());
        this.register(new WTap());

        // World
        this.register(new AntiStaff());
        this.register(new AutoPlay());
        this.register(new AutoTools());
        this.register(new Debugger());
        this.register(new Teams());
        this.register(new WebUI());
    }

    public void register(Module module) {
        this.moduleMap.put(module.getClass().getSimpleName(), module);
        module.registerSettings();
    }

    public Module getModule(String string) {
        Module module = null;
        for (Module module2 : this.moduleMap.values()) {
            if (!StringUtils.replace(module2.getName(), " ", "").equalsIgnoreCase(string)) continue;
            module = module2;
        }
        if (module == null) {
            throw new ModuleNotFoundException();
        }
        return module;
    }

    public <T extends Module> T getModule(Class<T> clazz) {
        Module module = clazz.cast(this.moduleMap.get(clazz.getSimpleName()));
        if (module == null) {
            throw new ModuleNotFoundException();
        }
        return (T) module;
    }

    public List<Module> getModules() {
        return this.moduleMap.values().stream().toList();
    }

    public List<Module> getModulesByCategory(Category category) {
        return this.moduleMap.values().stream()
                .filter(module -> module.getCategory().equals(category))
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .collect(Collectors.toList());
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        if (mc.screen == null) {
            for (Module module : this.moduleMap.values()) {
                if (module.getKey() != 0 && module.getKey() == event.getKeyCode() && event.isPressed()) {
                    module.toggle();
                }
            }
        }
    }
}