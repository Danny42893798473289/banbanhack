package shit.zen.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import shit.zen.ClientBase;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import shit.zen.ZenClient;
import shit.zen.modules.impl.render.FreeCam;
import shit.zen.modules.impl.render.CameraView;

import java.lang.reflect.Field;

@Patch(Camera.class)
public class CameraPatch {

    private static long lastDebugTime = 0L;
    private static final long DEBUG_INTERVAL_MS = 1000L;

    private static void debugChat(String message) {
        ClientBase.logger.info("[CameraPatch] {}", message);
    }

    private static void debugChatThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastDebugTime >= DEBUG_INTERVAL_MS) {
            lastDebugTime = now;
            debugChat(message);
        }
    }

    @Inject(
            method = "setup",
            desc = "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At(At.Type.TAIL)
    )
    public static void onSetup(
            Camera camera,
            BlockGetter blockGetter,
            Entity entity,
            boolean detached,
            boolean thirdPerson,
            float partialTick,
            CallbackInfo callbackInfo) {

        debugChatThrottled("onSetup injected & called, thirdPerson=" + thirdPerson);

        if (!ZenClient.isReady()) {
            return;
        }

        // 优先检查 CameraView 模块（相机高度固定）
        if (CameraView.INSTANCE != null && CameraView.INSTANCE.isEnabled() && CameraView.INSTANCE.shouldApplyView()) {
            Double baselineY = CameraView.INSTANCE.getBaselineY();
            if (baselineY != null) {
                try {
                    Vec3 currentPos = (Vec3) getFieldValue(camera, "position");
                    if (currentPos != null) {
                        // 只修改 Y 坐标，保留原生的 X/Z
                        Vec3 newPosition = new Vec3(currentPos.x, baselineY, currentPos.z);
                        BlockPos blockPos = BlockPos.containing(newPosition.x, newPosition.y, newPosition.z);
                        setCameraField(camera, "position", newPosition);
                        setCameraField(camera, "blockPosition", blockPos);
                        debugChatThrottled("CameraView fixed Y: " + String.format("%.2f", baselineY) + " | orig=" + String.format("%.2f", currentPos.y));
                    }
                } catch (Exception e) {
                    debugChat("CameraView update FAILED: " + e.getClass().getSimpleName());
                }
            }
            return;
        }

        // 否则检查 FreeCam 模块
        if (FreeCam.INSTANCE == null || !FreeCam.INSTANCE.isEnabled()) {
            return;
        }

        Vec3 freeCamPosition = FreeCam.INSTANCE.getInterpolatedPosition(partialTick);
        if (freeCamPosition == null) {
            return;
        }

        try {
            BlockPos blockPos = BlockPos.containing(freeCamPosition.x, freeCamPosition.y, freeCamPosition.z);
            setCameraField(camera, "position", freeCamPosition);
            setCameraField(camera, "blockPosition", blockPos);
            debugChatThrottled("FreeCam applied");
        } catch (Exception e) {
            debugChat("FreeCam update FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 额外的注入点：尝试在其他地方也进行拦截
     */
    @Inject(
            method = "setPosition",
            desc = "(DDD)V",
            at = @At(At.Type.HEAD)
    )
    public static void onSetPosition(Camera camera, double x, double y, double z, CallbackInfo callbackInfo) {
        if (!ZenClient.isReady()) {
            return;
        }

        // 如果 CameraView 启用，覆盖 Y 坐标
        if (CameraView.INSTANCE != null && CameraView.INSTANCE.isEnabled() && CameraView.INSTANCE.shouldApplyView()) {
            Double baselineY = CameraView.INSTANCE.getBaselineY();
            if (baselineY != null) {
                try {
                    // 获取反射字段并直接修改
                    setCameraField(camera, "position", new Vec3(x, baselineY, z));
                    debugChatThrottled("CameraView intercepted setPosition, fixed Y: " + String.format("%.2f", baselineY));
                    callbackInfo.cancel();  // 取消原方法
                } catch (Exception e) {
                    // 如果出错就让原方法继续执行
                    debugChat("CameraView setPosition interception FAILED: " + e.getClass().getSimpleName());
                }
            }
        }
    }

    private static void setCameraField(Camera camera, String fieldName, Object value) throws Exception {
        Field field = null;
        try {
            field = Camera.class.getDeclaredField(fieldName);
        } catch (NoSuchFieldException ignored) {
            // try fallback: find first field with compatible type
            for (Field f : Camera.class.getDeclaredFields()) {
                if (value != null && f.getType().isAssignableFrom(value.getClass())) {
                    field = f;
                    break;
                }
            }
            if (field == null) {
                throw new NoSuchFieldException(fieldName);
            }
        }
        field.setAccessible(true);
        field.set(camera, value);
    }

    private static Object getFieldValue(Camera camera, String fieldName) throws Exception {
        Field field = null;
        try {
            field = Camera.class.getDeclaredField(fieldName);
        } catch (NoSuchFieldException ignored) {
            for (Field f : Camera.class.getDeclaredFields()) {
                if (f.getName().equals(fieldName)) {
                    field = f;
                    break;
                }
            }
            if (field == null) {
                throw new NoSuchFieldException(fieldName);
            }
        }
        field.setAccessible(true);
        return field.get(camera);
    }
}