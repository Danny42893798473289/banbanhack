package shit.zen.gui;

import java.awt.Color;
import shit.zen.ClientBase;
import shit.zen.ZenClient;
import shit.zen.event.impl.GlRenderEvent;
import shit.zen.render.FontPresets;
import shit.zen.render.FontRenderer;
import shit.zen.render.GlHelper;
import shit.zen.render.Paint;
import shit.zen.event.EventTarget;

public class IntroAnimation
extends ClientBase {
    private static volatile boolean isActive = false;
    private long startTime = -1L;
    private boolean finished = false;

    private static final String TITLE_TEXT = "dannyhack";
    private static final String SUBTITLE_TEXT = "v1.0";

    // 字体只创建一次并缓存，避免每帧重复生成字形纹理导致卡顿（卡顿会把真实耗时算进动画时间轴，
    // 表现出来就是"动画还没播完就已经结束了"）。
    private final FontRenderer titleFont = FontPresets.axiformaBold(64.0f);
    private final FontRenderer subtitleFont = FontPresets.axiformaBold(16.0f);

    public IntroAnimation() {
        isActive = true;
    }

    public static boolean isRunning() {
        return isActive;
    }

    @EventTarget(value=4)
    public void onRender(GlRenderEvent glRenderEvent) {
        float bgAlpha;
        long elapsed;
        if (this.finished) {
            return;
        }
        if (this.startTime < 0L) {
            this.startTime = System.currentTimeMillis();
        }
        elapsed = System.currentTimeMillis() - this.startTime;
        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        float centerX = screenWidth / 2.0f;
        float centerY = screenHeight / 2.0f;

        // 时间轴：主标题(dannyhack) 淡入 -> 延迟一小段 -> 副标题 淡入上滑 -> 停留 -> 整体淡出
        long titleAppearStart = 600L;
        long titleAppearDuration = 500L;
        long subtitleDelay = 300L;
        long subtitleAppearDuration = 500L;
        long holdDuration = 1800L;
        long fadeOutDuration = 600L;

        long subtitleAppearStart = titleAppearStart + titleAppearDuration + subtitleDelay;
        long fadeOutStart = subtitleAppearStart + subtitleAppearDuration + holdDuration;

        if (elapsed <= 400L) {
            float fadeIn = IntroAnimation.easeOutCubic(IntroAnimation.clamp01((float)elapsed / 400.0f));
            bgAlpha = 0.6f * fadeIn;
        } else if (elapsed <= fadeOutStart) {
            bgAlpha = 0.6f;
        } else if (elapsed <= fadeOutStart + fadeOutDuration) {
            float fadeOut = 1.0f - IntroAnimation.easeInCubic(IntroAnimation.clamp01((float)(elapsed - fadeOutStart) / (float) fadeOutDuration));
            bgAlpha = 0.6f * fadeOut;
        } else {
            this.finish();
            return;
        }

        Paint paint = GlHelper.toPaint(new Color(0, 0, 0, (int)(bgAlpha * 255.0f)));
        GlHelper.drawRect(0.0f, 0.0f, screenWidth, screenHeight, paint);

        // 整体淡出系数，最后 fadeOutDuration 内让标题和副标题一起消失
        float fadeFactor = 1.0f;
        if (elapsed > fadeOutStart) {
            fadeFactor = 1.0f - IntroAnimation.clamp01((float)(elapsed - fadeOutStart) / (float) fadeOutDuration);
        }

        // 主标题：dannyhack，纯淡入（不再做连续缩放，避免逐帧重建字体纹理）
        float titleAlpha = 0.0f;
        if (elapsed >= titleAppearStart) {
            long sinceTitle = elapsed - titleAppearStart;
            titleAlpha = sinceTitle >= titleAppearDuration
                    ? 1.0f
                    : IntroAnimation.easeOutCubic(IntroAnimation.clamp01((float)sinceTitle / (float) titleAppearDuration));
        }

        if (titleAlpha > 0.0f) {
            float titleWidth = GlHelper.getStringWidth(TITLE_TEXT, this.titleFont);
            float titleRenderX = centerX - titleWidth / 2.0f;
            float titleRenderY = centerY - this.titleFont.getMetrics().capHeight() / 2.0f;
            int titleColor = new Color(1.0f, 1.0f, 1.0f, IntroAnimation.clamp01(titleAlpha * fadeFactor)).getRGB();
            GlHelper.drawText(TITLE_TEXT, titleRenderX, titleRenderY, this.titleFont, titleColor);
        }

        // 副标题：淡入 + 轻微上滑，位置在主标题正下方
        float subtitleAlpha = 0.0f;
        float subtitleOffsetY = 8.0f;
        if (elapsed > subtitleAppearStart && elapsed <= subtitleAppearStart + subtitleAppearDuration) {
            float subtitleProgress = IntroAnimation.easeOutCubic((float)(elapsed - subtitleAppearStart) / (float) subtitleAppearDuration);
            subtitleAlpha = subtitleProgress;
            subtitleOffsetY = (1.0f - subtitleProgress) * 8.0f;
        } else if (elapsed > subtitleAppearStart + subtitleAppearDuration) {
            subtitleAlpha = 1.0f;
            subtitleOffsetY = 0.0f;
        }

        if (subtitleAlpha > 0.0f) {
            float subtitleWidth = GlHelper.getStringWidth(SUBTITLE_TEXT, this.subtitleFont);
            float subtitleRenderX = centerX - subtitleWidth / 2.0f;
            float baseTitleY = centerY - this.titleFont.getMetrics().capHeight() / 2.0f;
            float subtitleRenderY = baseTitleY + this.titleFont.getMetrics().capHeight() + 10.0f + subtitleOffsetY;
            int subtitleColor = new Color(1.0f, 1.0f, 1.0f, IntroAnimation.clamp01(subtitleAlpha * fadeFactor * 0.75f)).getRGB();
            GlHelper.drawText(SUBTITLE_TEXT, subtitleRenderX, subtitleRenderY, this.subtitleFont, subtitleColor);
        }
    }

    private void finish() {
        if (!this.finished) {
            this.finished = true;
            try {
                ZenClient.instance.getEventBus().unregister(this);
            } catch (Throwable throwable) {
                // empty catch block
            }
            isActive = false;
        }
    }

    private static float clamp01(float value) {
        return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }

    private static float easeOutCubic(float t) {
        float clamped = IntroAnimation.clamp01(t);
        clamped = (float)(1.0 - Math.pow(1.0f - clamped, 3.0));
        return clamped;
    }

    private static float easeInCubic(float t) {
        float clamped = IntroAnimation.clamp01(t);
        clamped = clamped * clamped * clamped;
        return clamped;
    }
}
