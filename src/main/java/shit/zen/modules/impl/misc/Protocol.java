package shit.zen.modules.impl.misc;

import java.nio.file.Path;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import shit.zen.ZenClient;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.PacketEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.heypixel.HeyPixelProtocolRuntime;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;

public final class Protocol extends Module {
    private final HeyPixelProtocolRuntime runtime = new HeyPixelProtocolRuntime(
        mc, Path.of(ZenClient.configDir));
    public final ModeSetting enabledHosts = new ModeSetting("Hosts", "pc.bjdmc.net,*.bjdmc.net");
    public final BooleanSetting traceLogger = new BooleanSetting("Trace Logger", false);
    public final BooleanSetting observeOnly = new BooleanSetting("Observe Only", true);
    public final BooleanSetting allowLiveSend = new BooleanSetting("Allow Live Send", false);
    public final BooleanSetting strictProviderGate = new BooleanSetting("Strict Provider Gate", true);

    public Protocol() {
        super("Protocol", Category.WORLD);
    }

    @Override
    public void onEnable() {
        updateRuntimeSettings();
        runtime.start();
    }

    @Override
    public void onDisable() {
        runtime.stop();
    }

    @Override
    public String getSuffix() {
        return runtime.isActiveForCurrentServer() ? "HeyPixel" : "Idle";
    }

    @EventTarget
    public void onTick(TickEvent event) {
        updateRuntimeSettings();
        runtime.tick();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!event.isIncoming()) return;
        if (event.getPacket() instanceof ClientboundCustomPayloadPacket payload) {
            runtime.handle(payload);
        }
    }

    public HeyPixelProtocolRuntime getRuntime() {
        return runtime;
    }

    private void updateRuntimeSettings() {
        runtime.configure(
            enabledHosts.getValue(),
            traceLogger.getValue(),
            observeOnly.getValue(),
            allowLiveSend.getValue(),
            strictProviderGate.getValue()
        );
    }
}
