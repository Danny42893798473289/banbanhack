package shit.zen.modules.impl.render;

import net.minecraft.world.phys.Vec3;
import shit.zen.ClientBase;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.MotionEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.utils.misc.ChatUtil;
import net.minecraft.client.CameraType;

/**
 * CameraView: 第三人称相机高度固定模块
 *
 * 功能：
 *   - 按 F5 切换到第三人称视角时，固定相机的 Y 坐标
 *   - 玩家跳跃、移动时，相机 Y 坐标始终保持不变
 *   - 切换回第一人称或再次切换到第三人称时，重新记录新的相机 Y 坐标
 *
 * 原理：
 *   - 启用模块或切换第三人称时，记录当前第三人称相机的 Y 坐标到 baselineY
 *   - CameraPatch 直接使用 baselineY 替换相机的 Y 坐标
 *   - onMotion 监测第一人称→第三人称的切换，自动更新 baselineY
 */
public class CameraView extends Module {

    public static CameraView INSTANCE;

    public final BooleanSetting view = new BooleanSetting("View", true);

    private Double baselineY = null;  // 固定的相机 Y 坐标
    private CameraType lastCameraType = null;  // 上一帧的相机类型，用于检测切换
    private long lastChatTime = 0L;
    private static final long CHAT_THROTTLE_MS = 1500L;
    private int debugTickCounter = 0;

    public CameraView() {
        super("CameraView", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        if (mc.player != null && mc.options != null) {
            // 获取当前相机类型
            CameraType currentType = mc.options.getCameraType();
            lastCameraType = currentType;
            
            // 如果已经是第三人称，记录当前相机高度
            if (currentType.ordinal() != 0) {
                Vec3 eyePos = mc.player.getEyePosition();
                baselineY = eyePos.y;
                chatMessage("§7CameraView §b已启用 §7| 固定高度: §b" + String.format("%.2f", baselineY));
            } else {
                chatMessage("§7CameraView §b已启用 §7| 请按 §cF5 §b切换到第三人称");
                baselineY = null;
            }
        }
    }

    @Override
    public void onDisable() {
        baselineY = null;
        lastCameraType = null;
        chatMessage("§7CameraView §c已禁用");
    }

    private void chatMessage(String message) {
        try {
            ChatUtil.print(false, message);
        } catch (Exception e) {
            ClientBase.logger.error("[CameraView] Failed to send chat message: " + e.getMessage());
        }
    }

    private void chatMessageThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastChatTime >= CHAT_THROTTLE_MS) {
            lastChatTime = now;
            chatMessage(message);
        }
    }

    /**
     * 获取固定的相机 Y 坐标。
     */
    public Double getBaselineY() {
        return baselineY;
    }

    /**
     * 判断当前是否应该应用相机固定。
     */
    public boolean shouldApplyView() {
        if (!view.getValue()) {
            return false;
        }
        if (mc.player == null || mc.options == null) {
            return false;
        }
        // 仅在第三人称模式下应用（0=第一人称, 1/2=第三人称）
        return mc.options.getCameraType().ordinal() != 0;
    }

    /**
     * 检测第一人称→第三人称的切换，自动更新 baselineY。
     */
    @EventTarget
    public void onMotion(MotionEvent event) {
        if (!view.getValue() || mc.player == null || mc.options == null) {
            return;
        }

        try {
            CameraType currentType = mc.options.getCameraType();
            
            // 检测从第一人称切换到第三人称
            if (lastCameraType != null && lastCameraType.ordinal() == 0 && currentType.ordinal() != 0) {
                // 从第一人称切换到第三人称
                Vec3 eyePos = mc.player.getEyePosition();
                baselineY = eyePos.y;
                chatMessageThrottled("§7相机已固定 §7| 高度: §b" + String.format("%.2f", baselineY));
            } else if (lastCameraType != null && lastCameraType.ordinal() != 0 && currentType.ordinal() == 0) {
                // 从第三人称切换回第一人称
                baselineY = null;
                chatMessageThrottled("§7相机已取消固定");
            }
            
            lastCameraType = currentType;

            // 调试日志
            this.debugTickCounter++;
            if (this.debugTickCounter >= 20) {
                this.debugTickCounter = 0;
                ClientBase.logger.info("[CameraView] baselineY={} cameraType={} shouldApply={}", 
                    baselineY, currentType, shouldApplyView());
            }

        } catch (Throwable t) {
            ClientBase.logger.error("[CameraView] Exception in onMotion: {} {}", 
                t.getClass().getName(), t.getMessage());
            t.printStackTrace();
        }
    }
}

