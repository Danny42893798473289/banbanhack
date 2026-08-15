package shit.zen.modules.impl.combat;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import shit.zen.event.impl.EntityRemoveEvent;
import shit.zen.event.impl.SprintEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.event.EventTarget;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;

public class Critical
        extends Module {
    public static Critical INSTANCE;

    public final ModeSetting mode = new ModeSetting("Mode", "Vanilla", "Grim").withDefault("Vanilla");
    public final NumberSetting range = new NumberSetting("Range", 3.0, 1.0, 3.2, 0.1);
    public final BooleanSetting autoJump = new BooleanSetting("Auto Jump", true);

    public Critical() {
        super("Critical", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        super.onEnable();
    }

    @Override
    public void onDisable() {
        super.onDisable();
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            return;
        }
        if (!autoJump.getValue()) {
            return;
        }
        if (!KillAura.INSTANCE.isEnabled() || KillAura.INSTANCE.getTarget() == null) {
            return;
        }
        if (mc.player.onGround()) {
            mc.player.jumpFromGround();
        }
    }

    @EventTarget
    public void onSprint(SprintEvent event) {
        if (mc.player == null || !mode.is("Grim")) {
            return;
        }
        if (canCrit()) {
            mc.player.setSprinting(false);
        }
    }

    @EventTarget
    public void onEntityRemove(EntityRemoveEvent entityRemoveEvent) {
        if (mc.player == null || !mode.is("Vanilla")) {
            return;
        }

        // ===== Vanilla mode (original behaviour) =====
        boolean canCrit = mc.player.fallDistance > 0.0f && !mc.player.onGround() && !mc.player.onClimbable() && !mc.player.isInWater() && !mc.player.hasEffect(MobEffects.BLINDNESS) && !mc.player.isPassenger() && entityRemoveEvent.entity() instanceof LivingEntity;
        boolean wasSprinting = mc.player.isSprinting();
        if (canCrit && !entityRemoveEvent.dead()) {
            mc.player.resetAttackStrengthTicker();
        }
        if (canCrit && wasSprinting && entityRemoveEvent.dead()) {
            mc.options.keySprint.setDown(false);
        }
    }

    private boolean canCrit() {
        if (KillAura.INSTANCE == null || !KillAura.INSTANCE.isEnabled() || KillAura.INSTANCE.getTarget() == null) {
            return false;
        }
        return mc.player.fallDistance > 0.0f
                && !mc.player.onGround()
                && !mc.player.onClimbable()
                && !mc.player.isInWater()
                && !mc.player.hasEffect(MobEffects.BLINDNESS)
                && !mc.player.isPassenger();
    }
}