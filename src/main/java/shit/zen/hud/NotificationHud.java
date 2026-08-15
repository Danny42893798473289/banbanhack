package shit.zen.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.sounds.SoundEvents;
import shit.zen.event.impl.GlRenderEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.Module;
import shit.zen.modules.impl.misc.NotiSound;
import shit.zen.render.FontPresets;
import shit.zen.render.FontRenderer;
import shit.zen.render.GlHelper;
import shit.zen.render.Paint;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.render.ColorUtil;
import shit.zen.event.EventTarget;

/**
 * 和普通客户端一样的“模块开关提示”：某个模块被开启/关闭时，
 * 从右下角弹出一条 Toast，停留一小段时间后自动淡出。
 * 支持像其他 HUD 一样拖动位置；是否显示可以在模块面板（Render 分类）里单独开关。
 */
public class NotificationHud
extends HudElement {

    public static NotificationHud INSTANCE;

    private static final long DISPLAY_DURATION_MS = 1800L;
    private static final int MAX_VISIBLE_TOASTS = 5;

    private static final class Toast {
        final String moduleName;
        final boolean enabled;
        final long createdAt = System.currentTimeMillis();
        boolean removing = false;
        final SmoothAnimationTimer slideYAnim = new SmoothAnimationTimer();
        final SmoothAnimationTimer alphaAnim = new SmoothAnimationTimer();
        final SmoothAnimationTimer stackAnim = new SmoothAnimationTimer();
        float targetStackY = 0.0f;
        boolean stackTargetInitialized = false;

        Toast(String moduleName, boolean enabled) {
            this.moduleName = moduleName;
            this.enabled = enabled;
            this.slideYAnim.setCurrentValue(10.0);
            this.alphaAnim.setCurrentValue(0.0);
            this.stackAnim.setCurrentValue(0.0);
            this.slideYAnim.animate(0.0, 0.25, Easings.EASE_OUT_POW3);
            this.alphaAnim.animate(1.0, 0.25, Easings.EASE_OUT_POW3);
        }

        void startRemove() {
            if (this.removing) return;
            this.removing = true;
            this.slideYAnim.animate(12.0, 0.25, Easings.EASE_IN_POW3);
            this.alphaAnim.animate(0.0, 0.25, Easings.EASE_IN_POW3);
        }

        void tick() {
            this.slideYAnim.tick();
            this.alphaAnim.tick();
            this.stackAnim.tick();
        }

        boolean isDone() {
            return this.removing && this.alphaAnim.isDone();
        }
    }

    private final List<Toast> toasts = new CopyOnWriteArrayList<>();
    private final FontRenderer nameFont = FontPresets.pingfang(15.0f);
    private final FontRenderer stateFont = FontPresets.pingfang(12.0f);
    private final Paint bgPaint = new Paint();
    private final Paint accentPaint = new Paint();
    private final Paint textPaint = new Paint();
    private boolean positionInitialized = false;
    private float lastScreenWidth = 0.0f;
    private float lastScreenHeight = 0.0f;

    public NotificationHud() {
        super("Notification");
        INSTANCE = this;
        this.setEnabled(true);
        this.setWidth(150.0f);
        this.setHeight(24.0f);
        this.bgPaint.setAntialias(true);
        this.accentPaint.setAntialias(true);
    }

    /**
     * 由 Module#setEnabled 调用，弹出一条“XXX 已开启/已关闭”的提示。
     */
    public static void notify(Module module, boolean enabled) {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return;
        if (mc == null || mc.player == null) return;
        if (module == null || module.getName() == null || module.getName().isEmpty()) return;
        if (module instanceof NotificationHud) return;

        // 确保 NotiSound 实例已加载
        if (NotiSound.INSTANCE != null) {
            String mode = NotiSound.INSTANCE.getSoundMode();

            // 统一转小写进行判断，防止大小写写错导致匹配不到
            switch (mode.toLowerCase()) {
                case "chenxx" -> {
                    // 你原本的音效模式
                    if (enabled) {
                        mc.player.playSound(SoundEvents.NOTE_BLOCK_BIT.get(), 1.0F, 1.0F);
                    } else {
                        mc.player.playSound(SoundEvents.NOTE_BLOCK_BASS.get(), 1.0F, 1.0F);
                    }
                }
                case "button" -> {
                    // UI 按钮点击音效 (原版点按钮的声音)
                    if (enabled) {
                        mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.get(), 1.0F, 1.0F);
                    } else {
                        mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.get(), 1.0F, 0.8F); // 降调表示关闭
                    }
                }
                case "experience" -> {
                    // 🌟 新增：捡起经验球的清脆声音 (很多大牌 Client 都在用)
                    if (enabled) {
                        mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
                    } else {
                        mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 0.7F); // 明显降调表示关闭
                    }
                }
                case "none" -> {
                    // 无音效模式，什么都不做直接留空即可
                }
            }
        } else {
            // 兜底保护：如果游戏刚启动，NotiSound 还没初始化就被调用了，就播放默认声音防崩溃
            if (enabled) {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_BIT.get(), 1.0F, 1.0F);
            } else {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_BASS.get(), 1.0F, 1.0F);
            }
        }

        INSTANCE.toasts.add(new Toast(module.getName(), enabled));
    }

    /**
     * 默认显示在右下角、稍微往上一点的位置（不是死死贴在屏幕最边缘）。
     * 窗口大小改变时会自动重新计算位置。
     */
    private void initDefaultPosition() {
        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        
        // 检测窗口大小是否改变
        if (!positionInitialized || Math.abs(screenWidth - lastScreenWidth) > 0.1f || Math.abs(screenHeight - lastScreenHeight) > 0.1f) {
            positionInitialized = true;
            lastScreenWidth = screenWidth;
            lastScreenHeight = screenHeight;
            this.setX(screenWidth - 170.0f);
            this.setY(screenHeight - 28.0f);
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            this.toasts.clear();
            return;
        }
        long now = System.currentTimeMillis();
        for (Toast toast : this.toasts) {
            if (!toast.removing && now - toast.createdAt >= DISPLAY_DURATION_MS) {
                toast.startRemove();
            }
        }
        this.toasts.removeIf(Toast::isDone);
    }

    @Override
    public void onRender2D(Render2DEvent render2DEvent, float x, float y) {
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
        this.initDefaultPosition();

        if (this.toasts.isEmpty()) {
            this.setWidth(150.0f);
            this.setHeight(0.0f);
            return;
        }

        this.toasts.forEach(Toast::tick);

        // 只画最新的几条，太旧的直接丢弃，避免堆屏
        List<Toast> visibleToasts = new ArrayList<>(this.toasts);
        while (visibleToasts.size() > MAX_VISIBLE_TOASTS) {
            visibleToasts.remove(0);
        }

        float rowHeight = 22.0f;
        float rowGap = 4.0f;
        float rowWidth = 150.0f;

        for (int i = 0; i < visibleToasts.size(); i++) {
            Toast toast = visibleToasts.get(i);
            float alpha = toast.alphaAnim.getValueF();
            if (alpha <= 0.01f) {
                continue;
            }
            float targetY = y - (float) i * (rowHeight + rowGap);
            if (!toast.stackTargetInitialized || Math.abs(toast.targetStackY - targetY) > 0.1f) {
                toast.stackTargetInitialized = true;
                toast.targetStackY = targetY;
                toast.stackAnim.animate(targetY, 0.16, Easings.EASE_OUT_QUAD);
            }
            float slideY = toast.slideYAnim.getValueF();
            float rowY = toast.stackAnim.getValueF() + slideY;

            this.bgPaint.setColor(ColorUtil.fromARGB(0, 0, 0, (int) (170.0f * alpha)));
            GlHelper.drawRoundedRect(x, rowY, rowWidth, rowHeight, 5.0f, this.bgPaint);

            // 左侧一条小色块：开=绿色，关=灰红色
            int accentColor = toast.enabled
                    ? ColorUtil.fromARGB(80, 220, 120, (int) (255.0f * alpha))
                    : ColorUtil.fromARGB(220, 80, 80, (int) (255.0f * alpha));
            this.accentPaint.setColor(accentColor);
            GlHelper.drawRoundedRect(x + 4.0f, rowY + 4.0f, 3.0f, rowHeight - 8.0f, 1.5f, this.accentPaint);

            int nameColor = ColorUtil.fromARGB(255, 255, 255, (int) (255.0f * alpha));
            this.textPaint.setColor(nameColor);
            float nameY = rowY + (rowHeight - (float) GlHelper.getFontAscent(this.nameFont)) / 2.0f;
            GlHelper.drawTextWithShadow(toast.moduleName, x + 11.0f, nameY, this.nameFont, this.textPaint);

            String stateText = toast.enabled ? "已开启" : "已关闭";
            int stateColor = toast.enabled
                    ? ColorUtil.fromARGB(140, 235, 170, (int) (255.0f * alpha))
                    : ColorUtil.fromARGB(235, 150, 150, (int) (255.0f * alpha));
            this.textPaint.setColor(stateColor);
            float stateWidth = GlHelper.getStringWidth(stateText, this.stateFont);
            float stateY = rowY + (rowHeight - (float) GlHelper.getFontAscent(this.stateFont)) / 2.0f;
            GlHelper.drawTextWithShadow(stateText, x + rowWidth - stateWidth - 8.0f, stateY, this.stateFont, this.textPaint);
        }

        float totalHeight = Math.max(0.0f, visibleToasts.size() * rowHeight + Math.max(0, visibleToasts.size() - 1) * rowGap);
        this.setWidth(rowWidth);
        this.setHeight(totalHeight);
    }

    @Override
    public void onSettings() {
    }
}
