package shit.zen.modules.impl.render;

import net.minecraft.network.chat.Component;
import net.minecraft.client.CameraType;
import shit.zen.ClientBase;
import net.minecraft.world.phys.Vec3;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.StrafeEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.NumberSetting;

/**
 * 自由视角（Freecam）：开启后镜头可以脱离身体自由飞行，方便你在原地按按钮/拉杆的同时
 * 飞到别的角度去看红石线路的运行状态，人物本体不会跟着移动。
 */
public class FreeCam extends Module {

    public static FreeCam INSTANCE;

    public final NumberSetting speed = new NumberSetting("Speed", 0.5, 0.05, 3.0, 0.05);

    private Vec3 position;
    private Vec3 prevPosition;
    private int debugTickCounter = 0;

    public FreeCam() {
        super("FreeCam", Category.RENDER);
        INSTANCE = this;
    }

    private void debugChat(String message) {
        ClientBase.logger.info("[FreeCam] {}", message);
    }

    @Override
    public void onEnable() {
        this.debugChat("onEnable called, player=" + (mc.player != null));
        if (mc.player != null) {
            this.position = mc.player.getEyePosition();
            this.prevPosition = this.position;
            this.debugChat("initial position=" + this.position);
        }
        // 删除了自动设置 THIRD_PERSON_BACK 的逻辑
    }

    @Override
    public void onDisable() {
        this.debugChat("onDisable called");
        this.position = null;
        this.prevPosition = null;
        // 删除了恢复原视角的逻辑
    }

    public Vec3 getPosition() {
        return this.position;
    }

    public Vec3 getInterpolatedPosition(float partialTick) {
        if (this.position == null) return null;
        if (this.prevPosition == null) return this.position;
        return this.prevPosition.lerp(this.position, partialTick);
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        try {
            if (this.position != null) this.prevPosition = this.position;
            this.doStrafe(event);
        } catch (Throwable t) {
            this.debugChat("REAL EXCEPTION: " + t.getClass().getName() + ": " + t.getMessage());
            StackTraceElement[] trace = t.getStackTrace();
            for (int i = 0; i < Math.min(3, trace.length); i++) {
                ClientBase.logger.info("[FreeCam]   at {}", trace[i].toString());
            }
            t.printStackTrace();
        }
    }

    private void doStrafe(StrafeEvent event) {
        if (mc.player == null) {
            return;
        }
        if (this.position == null) {
            this.position = mc.player.getEyePosition();
        }

        float forward = event.getForward();
        float strafe = event.getStrafe();
        boolean flyUp = event.isSprinting();
        boolean flyDown = mc.options.keyShift.isDown();

        double moveSpeed = ((Number) this.speed.getValue()).doubleValue();
        double yawRad = Math.toRadians(mc.player.getYRot());

        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double strafeX = Math.cos(yawRad);
        double strafeZ = Math.sin(yawRad);

        double moveX = (forwardX * forward + strafeX * strafe) * moveSpeed;
        double moveZ = (forwardZ * forward + strafeZ * strafe) * moveSpeed;
        double moveY = 0.0;
        if (flyUp) {
            moveY += moveSpeed;
        }
        if (flyDown) {
            moveY -= moveSpeed;
        }

        this.position = this.position.add(moveX, moveY, moveZ);

        this.debugTickCounter++;
        if (this.debugTickCounter >= 20) {
            this.debugTickCounter = 0;
            this.debugChat("position=" + this.position + " forward=" + forward + " strafe=" + strafe + " flyUp=" + flyUp + " flyDown=" + flyDown);
        }

        event.setForward(0.0f);
        event.setStrafe(0.0f);
        event.setSprinting(false);
    }
}