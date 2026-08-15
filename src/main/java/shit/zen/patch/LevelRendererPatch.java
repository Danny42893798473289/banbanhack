package shit.zen.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.ZenClient;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.impl.render.FreeCam;

import java.lang.reflect.Field;

@Patch(LevelRenderer.class)
public class LevelRendererPatch {
    @Inject(
            method = "renderLevel",
            desc = "(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            at = @At(At.Type.TAIL)
    )
    public static void onRenderLevel(
            LevelRenderer renderer,
            PoseStack poseStack,
            float partialTick,
            long finishNanoTime,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f projection,
            CallbackInfo callbackInfo) {
        if (ZenClient.isReady()) {
            ZenClient.getInstance().getEventBus().call(new RenderEvent(poseStack, partialTick));
        }

        // 【新增：FreeCam 镜头覆盖逻辑】
        // 这里拿到的 camera 就是游戏本帧要使用的相机，直接改它的内部字段
        if (FreeCam.INSTANCE != null && FreeCam.INSTANCE.isEnabled()) {
            Vec3 freeCamPosition = FreeCam.INSTANCE.getPosition();
            if (freeCamPosition != null) {
                try {
                    BlockPos blockPos = BlockPos.containing(freeCamPosition.x, freeCamPosition.y, freeCamPosition.z);
                    setCameraField(camera, "position", freeCamPosition);
                    setCameraField(camera, "blockPosition", blockPos);
                } catch (Exception ignored) {}
            }
        }
    }

    private static void setCameraField(Camera camera, String fieldName, Object value) throws Exception {
        Field field = null;
        try {
            field = Camera.class.getDeclaredField(fieldName);
        } catch (NoSuchFieldException ignored) {
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
}