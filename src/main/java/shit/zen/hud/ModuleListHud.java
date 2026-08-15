package shit.zen.hud;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.util.Mth;
import shit.zen.ZenClient;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GlRenderEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.Module;
import shit.zen.modules.impl.render.Interface;
import shit.zen.render.FontPresets;
import shit.zen.render.FontRenderer;
import shit.zen.render.GlHelper;
import shit.zen.render.Paint;
import shit.zen.settings.Setting;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.render.ColorUtil;

public class ModuleListHud
        extends HudElement {

    private enum Alignment {
        LEFT,
        RIGHT
    }

    private static final class AnimatedRow {
        private final Module module;
        private final SmoothAnimationTimer progressAnim = new SmoothAnimationTimer();
        private String name;
        private float textWidth;
        private float rowWidth;
        private boolean targetVisible;

        private AnimatedRow(Module module) {
            this.module = module;
            this.name = module.getName();
            this.progressAnim.setCurrentValue(0.0f);
            this.progressAnim.setToValue(0.0f);
        }

        private void updateMetrics(String displayName, float textWidth, float rowWidth) {
            this.name = displayName;
            this.textWidth = textWidth;
            this.rowWidth = rowWidth;
        }

        private void setTargetVisible(boolean visible) {
            if (this.targetVisible == visible) {
                return;
            }
            this.targetVisible = visible;
            float current = this.progressAnim.getValueF();
            this.progressAnim.setCurrentValue(current);
            this.progressAnim.setFromValue(current);
            this.progressAnim.setToValue(visible ? 1.0f : 0.0f);
            this.progressAnim.setStartTime(System.currentTimeMillis());
            this.progressAnim.setDuration(visible ? 220.0 : 180.0);
            this.progressAnim.setEasing(visible ? Easings.EASE_OUT_POW3 : Easings.EASE_IN_POW3);
        }

        private void tick() {
            this.progressAnim.tick();
        }

        private float progress() {
            return Mth.clamp(this.progressAnim.getValueF(), 0.0f, 1.0f);
        }

        private boolean isFinishedRemoving() {
            return !this.targetVisible && this.progress() <= 0.01f && this.progressAnim.isDone();
        }
    }

    private static final class RowRenderLayout {
        private final AnimatedRow row;
        private final float x;
        private final float y;
        private final float width;
        private final float height;
        private final float progress;

        private RowRenderLayout(AnimatedRow row, float x, float y, float width, float height, float progress) {
            this.row = row;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.progress = progress;
        }
    }

    private static final float MIN_VISIBLE_EDGE = 4.0f;
    private static final float DEFAULT_ROW_HEIGHT = 15.0f;
    private static final float DEFAULT_PADDING_X = 3.0f;
    private static final float DEFAULT_PADDING_Y = 3.5f;
    private static final float DEFAULT_ROW_SPACING = 0.0f;
    private static final float DEFAULT_RADIUS = 2.5f;
    private static final float SLIDE_DISTANCE = 18.0f;

    private ModeSetting sideMode;
    private BooleanSetting breakEnabled;
    private BooleanSetting showSuffix;
    private BooleanSetting suffixColorEnabled;
    private BooleanSetting suffixLowercaseEnabled;
    private BooleanSetting important;
    private NumberSetting paddingX;
    private NumberSetting paddingY;
    private NumberSetting rowHeight;
    private NumberSetting rowSpacing;
    private BooleanSetting backgroundEnabled;
    private NumberSetting backgroundRadius;
    private NumberSetting backgroundAlpha;
    private BooleanSetting glowEnabled;
    private NumberSetting glowRadius;
    private NumberSetting glowAlpha;
    private BooleanSetting sideLineEnabled;
    private ModeSetting sideLineMode;
    private NumberSetting sideLineWidth;
    private BooleanSetting useClientColor;
    private ModeSetting textColorMode;
    private ModeSetting gradientTheme;
    private NumberSetting red1;
    private NumberSetting green1;
    private NumberSetting blue1;
    private NumberSetting red2;
    private NumberSetting green2;
    private NumberSetting blue2;
    private NumberSetting rainbowSpeed;
    private NumberSetting rainbowSaturation;
    private NumberSetting rainbowBrightness;
    private NumberSetting rainbowOffset;

    private final SmoothAnimationTimer widthAnim = new SmoothAnimationTimer();
    private final SmoothAnimationTimer heightAnim = new SmoothAnimationTimer();
    private final Map<Module, AnimatedRow> rowStates = new IdentityHashMap<>();
    private final FontRenderer titleFont = FontPresets.pingfang(16.0f);
    private final FontRenderer entryFont = FontPresets.pingfang(16.0f);
    private boolean animationReady;

    public ModuleListHud() {
        super("ModuleList");
        this.setX(4.0f);
        this.setY(18.0f);
        this.setWidth(0.0f);
        this.setHeight(0.0f);
        this.setEnabled(true);
    }

    @Override
    public void registerSettings() {
        this.sideMode = new ModeSetting("Side Mode", "Auto", "Auto", "Left", "Right").withDefault("Auto");
        this.breakEnabled = new BooleanSetting("Break", false);
        this.showSuffix = new BooleanSetting("Show Suffix", true);
        this.suffixColorEnabled = new BooleanSetting("Suffix Color", true);
        this.suffixLowercaseEnabled = new BooleanSetting("Suffix Lowercase", false);
        this.important = new BooleanSetting("Important", false);
        this.paddingX = new NumberSetting("Padding X", DEFAULT_PADDING_X, 0.0f, 12.0f, 0.25f);
        this.paddingY = new NumberSetting("Padding Y", DEFAULT_PADDING_Y, 0.0f, 8.0f, 0.25f);
        this.rowHeight = new NumberSetting("Row Height", DEFAULT_ROW_HEIGHT, 9.0f, 24.0f, 0.25f);
        this.rowSpacing = new NumberSetting("Row Spacing", DEFAULT_ROW_SPACING, 0.0f, 8.0f, 0.25f);

        this.backgroundEnabled = new BooleanSetting("Background", true);
        this.backgroundRadius = new NumberSetting("Background Radius", DEFAULT_RADIUS, 0.0f, 10.0f, 0.25f);
        this.backgroundAlpha = new NumberSetting("Background Alpha", 80.0f, 0.0f, 255.0f, 1.0f);

        this.glowEnabled = new BooleanSetting("Glow", false);
        this.glowRadius = new NumberSetting("Glow Radius", 12.0f, 4.0f, 40.0f, 1.0f);
        this.glowAlpha = new NumberSetting("Glow Alpha", 120.0f, 0.0f, 255.0f, 1.0f);

        this.sideLineEnabled = new BooleanSetting("Side Line", false);
        this.sideLineMode = new ModeSetting("Side Line Mode", "Auto", "Auto", "Left", "Right").withDefault("Auto");
        this.sideLineWidth = new NumberSetting("Side Line Width", 0.8f, 0.5f, 5.0f, 0.25f);

        this.useClientColor = new BooleanSetting("Use Client Color", false);
        this.textColorMode = new ModeSetting("Text Color Mode", "Gradient", "Solid", "Gradient").withDefault("Gradient");
        this.gradientTheme = new ModeSetting("Gradient Theme", "Rainbow", "Rainbow", "Aurora", "Sunset", "Ocean", "Cotton Candy", "Lavender", "Peach", "Mint", "Cyberpunk", "Drift").withDefault("Cotton Candy");
        this.red1 = new NumberSetting("R1", 255.0f, 0.0f, 255.0f, 1.0f);
        this.green1 = new NumberSetting("G1", 100.0f, 0.0f, 255.0f, 1.0f);
        this.blue1 = new NumberSetting("B1", 170.0f, 0.0f, 255.0f, 1.0f);
        this.red2 = new NumberSetting("R2", 255.0f, 0.0f, 255.0f, 1.0f);
        this.green2 = new NumberSetting("G2", 50.0f, 0.0f, 255.0f, 1.0f);
        this.blue2 = new NumberSetting("B2", 140.0f, 0.0f, 255.0f, 1.0f);
        this.rainbowSpeed = new NumberSetting("Rainbow Speed", 5.0f, 1.0f, 240.0f, 1.0f);
        this.rainbowSaturation = new NumberSetting("Rainbow Saturation", 90.0f, 0.0f, 100.0f, 1.0f);
        this.rainbowBrightness = new NumberSetting("Rainbow Brightness", 100.0f, 10.0f, 100.0f, 1.0f);
        this.rainbowOffset = new NumberSetting("Rainbow Offset", 85.0f, 0.0f, 90.0f, 1.0f);

        this.registerSetting(sideMode, breakEnabled, showSuffix, suffixColorEnabled, suffixLowercaseEnabled,
                important, paddingX, paddingY, rowHeight, rowSpacing, backgroundEnabled, backgroundRadius,
                backgroundAlpha, glowEnabled, glowRadius, glowAlpha, sideLineEnabled, sideLineMode,
                sideLineWidth, useClientColor, textColorMode, gradientTheme, red1, green1, blue1,
                red2, green2, blue2, rainbowSpeed, rainbowSaturation, rainbowBrightness, rainbowOffset);
    }

    public void registerSetting(Setting<?>... settings) {
        for (Setting<?> setting : settings) {
            this.addSetting(setting);
        }
    }

    private List<AnimatedRow> updateRows() {
        boolean importantOnly = this.important.getValue();
        for (Module module : ZenClient.getInstance().getModuleManager().getModules()) {
            if (module == this || module.getName().isEmpty() || module instanceof Interface) {
                this.rowStates.remove(module);
                continue;
            }
            if (importantOnly && module.getCategory() == shit.zen.modules.Category.RENDER) {
                this.rowStates.remove(module);
                continue;
            }
            AnimatedRow row = this.rowStates.get(module);
            if (module.isEnabled()) {
                if (row == null) {
                    row = new AnimatedRow(module);
                    this.rowStates.put(module, row);
                }
                String displayName = this.displayName(module);
                float textWidth = GlHelper.getStringWidth(displayName, this.entryFont);
                row.updateMetrics(displayName, textWidth, this.rowWidth(textWidth));
                row.setTargetVisible(true);
            } else if (row != null) {
                row.setTargetVisible(false);
            }
        }
        this.rowStates.values().forEach(AnimatedRow::tick);
        this.rowStates.values().removeIf(AnimatedRow::isFinishedRemoving);

        List<AnimatedRow> rows = new ArrayList<>(this.rowStates.values());
        rows.sort((a, b) -> Float.compare(b.textWidth, a.textWidth));
        return rows;
    }


    private String displayName(Module module) {
        String name = module.getName();
        if (!this.showSuffix.getValue()) {
            return name;
        }
        String suffix = module.getSuffix();
        if (suffix == null || suffix.isBlank()) {
            return name;
        }
        suffix = suffix.trim();
        if (this.suffixLowercaseEnabled.getValue()) {
            suffix = suffix.toLowerCase(Locale.ROOT);
        }
        return name + " " + suffix;
    }

    @Override
    public void onRender2D(Render2DEvent render2DEvent, float x, float y) {
    }

    private boolean shouldRender() {
        if (!this.isEnabled()) {
            return false;
        }
        List<Module> interfaceModules = ZenClient.getInstance().getModuleManager().getModules().stream()
                .filter(module -> module instanceof Interface)
                .toList();
        if (interfaceModules.isEmpty()) {
            return true;
        }
        return interfaceModules.stream().anyMatch(Module::isEnabled);
    }

    private float rowWidth(float textWidth) {
        float lineReserve = this.sideLineEnabled.getValue() ? this.sideLineWidth.getValue().floatValue() : 0.0f;
        return textWidth + this.paddingX.getValue().floatValue() * 2.0f + lineReserve;
    }

    private float measureWidth(List<AnimatedRow> rows) {
        float maxWidth = 0.0f;
        for (AnimatedRow row : rows) {
            maxWidth = Math.max(maxWidth, row.rowWidth);
        }
        return maxWidth;
    }

    private float measureHeight(List<AnimatedRow> rows) {
        float rowHeightValue = this.rowHeight.getValue().floatValue();
        float spacing = this.rowSpacing.getValue().floatValue();
        float height = 0.0f;
        boolean hasVisibleRow = false;
        for (AnimatedRow row : rows) {
            float progress = row.progress();
            if (progress <= 0.01f) {
                continue;
            }
            if (hasVisibleRow) {
                height += spacing * progress;
            }
            height += rowHeightValue * progress;
            hasVisibleRow = true;
        }
        return height;
    }

    private void updateSizeAnimation(float targetWidth, float targetHeight) {
        if (!this.animationReady) {
            this.widthAnim.setCurrentValue(targetWidth);
            this.widthAnim.setToValue(targetWidth);
            this.heightAnim.setCurrentValue(targetHeight);
            this.heightAnim.setToValue(targetHeight);
            this.animationReady = true;
            return;
        }
        this.widthAnim.animate(targetWidth, 0.18, Easings.EASE_OUT_SINE);
        this.heightAnim.animate(targetHeight, 0.18, Easings.EASE_OUT_SINE);
        this.widthAnim.tick();
        this.heightAnim.tick();
    }

    private void renderRows(List<AnimatedRow> rows, float x, float y, float width, Alignment alignment) {
        List<RowRenderLayout> layouts = this.computeRowLayouts(rows, x, y, width, alignment);
        for (int i = 0; i < layouts.size(); i++) {
            RowRenderLayout layout = layouts.get(i);
            this.renderRow(layout, rows.size(), alignment, i, layouts.size());
        }
    }

    private void renderRow(RowRenderLayout layout, int rowCount, Alignment alignment, int rowIndex, int rowCountTotal) {
        float paddingX = this.paddingX.getValue().floatValue();
        float rowHeightValue = this.rowHeight.getValue().floatValue();
        float radius = this.backgroundRadius.getValue().floatValue();

        if (this.backgroundEnabled.getValue()) {
            try (Paint paint = new Paint()) {
                int alpha = Math.round(this.backgroundAlpha.getValue().floatValue() * layout.progress);
                paint.setColor((alpha << 24) | 0x000000);
                if (radius <= 0.0f) {
                    GlHelper.drawRect(layout.x, layout.y, layout.width, layout.height, paint);
                } else {
                    GlHelper.drawRoundedRect(layout.x, layout.y, layout.width, layout.height, radius, paint);
                }
            }
        }

        if (this.glowEnabled.getValue()) {
            float gRadius = this.glowRadius.getValue().floatValue();
            int gAlpha = Math.round(this.glowAlpha.getValue().floatValue() * layout.progress);
            if (gAlpha > 0 && gRadius > 0.0f) {
                try (Paint paint = new Paint()) {
                    paint.setColor(ColorUtil.fromARGB(0, 0, 0, gAlpha));
                    GlHelper.drawRoundedRect(layout.x - gRadius * 0.25f, layout.y - gRadius * 0.25f,
                            layout.width + gRadius * 0.5f, layout.height + gRadius * 0.5f, radius + gRadius * 0.25f, paint);
                }
            }
        }

        if (this.sideLineEnabled.getValue()) {
            float lineWidth = this.sideLineWidth.getValue().floatValue();
            Alignment lineAlignment = this.resolveLineAlignment(alignment);
            float lineX = lineAlignment == Alignment.RIGHT ? layout.x + layout.width - lineWidth : layout.x;
            try (Paint paint = new Paint()) {
                paint.setColor(ColorUtil.withAlpha(this.colorForPosition(rowIndex, 0.8f), layout.progress));
                GlHelper.drawRoundedRect(lineX, layout.y, lineWidth, layout.height, Math.min(radius, lineWidth), paint);
            }
        }

        float textX = alignment == Alignment.RIGHT
                ? layout.x + layout.width - paddingX - layout.row.textWidth - (this.resolveLineAlignment(alignment) == Alignment.RIGHT ? this.sideLineWidth.getValue().floatValue() : 0.0f)
                : layout.x + paddingX + (this.resolveLineAlignment(alignment) == Alignment.LEFT ? this.sideLineWidth.getValue().floatValue() : 0.0f);
        float textY = layout.y + (rowHeightValue - (float) GlHelper.getFontAscent(this.entryFont)) / 2.0f + this.paddingY.getValue().floatValue() * 0.25f;
        int color = this.colorForPosition(rowIndex, layout.progress);
        GlHelper.drawTextShadowLegacy(layout.row.name, textX, textY, this.entryFont, color);
    }

    private List<RowRenderLayout> computeRowLayouts(List<AnimatedRow> rows, float x, float y, float width, Alignment alignment) {
        List<RowRenderLayout> layouts = new ArrayList<>();
        float cursorY = y;
        float rowHeightValue = this.rowHeight.getValue().floatValue();
        float spacing = this.rowSpacing.getValue().floatValue();
        boolean hasRenderedRow = false;
        for (AnimatedRow row : rows) {
            float progress = row.progress();
            if (progress <= 0.01f) {
                continue;
            }
            if (hasRenderedRow) {
                cursorY += spacing * progress;
            }
            float animatedWidth = Math.max(0.1f, row.rowWidth * progress);
            float animatedHeight = Math.max(0.1f, rowHeightValue * progress);
            float rowX = alignment == Alignment.RIGHT ? x + width - animatedWidth : x;
            float slideOffset = (alignment == Alignment.RIGHT ? SLIDE_DISTANCE : -SLIDE_DISTANCE) * (1.0f - progress);
            layouts.add(new RowRenderLayout(row, rowX + slideOffset, cursorY, animatedWidth, animatedHeight, progress));
            cursorY += rowHeightValue * progress;
            hasRenderedRow = true;
        }
        return layouts;
    }

    private int colorForPosition(int rowIndex, float alpha) {
        if (this.useClientColor.getValue()) {
            return ColorUtil.withAlpha(ColorUtil.fromRGB(255, 255, 255), alpha);
        }
        String mode = this.textColorMode.getValue();
        int speed = this.rainbowSpeed.getValue().intValue();
        float saturation = this.rainbowSaturation.getValue().floatValue() / 100.0f;
        float brightness = this.rainbowBrightness.getValue().floatValue() / 100.0f;
        float timeOffset = (float) ((System.currentTimeMillis() % (long)(speed * 200)) / (double)(speed * 200));

        if ("Solid".equals(mode)) {
            int solidColor = ColorUtil.fromRGB(this.red1.getValue().intValue(), this.green1.getValue().intValue(), this.blue1.getValue().intValue());
            return ColorUtil.withAlpha(solidColor, alpha);
        }
        if ("Gradient".equals(mode)) {
            float rowFraction = this.rowFractionForIndex(rowIndex);
            return ColorUtil.withAlpha(this.gradientBetweenColors(rowFraction, timeOffset, saturation, brightness), alpha);
        }
        return ColorUtil.withAlpha(ColorUtil.getRainbowColor(speed, 0).getRGB(), alpha);
    }

    private float rowFractionForIndex(int rowIndex) {
        int visibleRows = this.rowStates.size();
        if (visibleRows <= 1) {
            return 0.0f;
        }
        return Math.min(1.0f, Math.max(0.0f, rowIndex / (float)(visibleRows - 1)));
    }

    private int gradientBetweenColors(float rowFraction, float timeOffset, float saturation, float brightness) {
        int r1 = this.red1.getValue().intValue();
        int g1 = this.green1.getValue().intValue();
        int b1 = this.blue1.getValue().intValue();
        int r2 = this.red2.getValue().intValue();
        int g2 = this.green2.getValue().intValue();
        int b2 = this.blue2.getValue().intValue();

        float mix = rowFraction;
        int red = Math.round(r1 * (1.0f - mix) + r2 * mix);
        int green = Math.round(g1 * (1.0f - mix) + g2 * mix);
        int blue = Math.round(b1 * (1.0f - mix) + b2 * mix);

        int baseColor = ColorUtil.fromRGB(red, green, blue);
        float hueShift = (timeOffset - 0.5f) * 0.1f;
        return ColorUtil.withAlpha(baseColor, 1.0f); 
    }

    private Alignment resolveAlignment(float x, float width) {
        if ("Left".equals(this.sideMode.getValue())) {
            return Alignment.LEFT;
        }
        if ("Right".equals(this.sideMode.getValue())) {
            return Alignment.RIGHT;
        }
        return x + width / 2.0f < (float) mc.getWindow().getGuiScaledWidth() / 2.0f
                ? Alignment.LEFT
                : Alignment.RIGHT;
    }

    private Alignment resolveLineAlignment(Alignment rowAlignment) {
        if ("Left".equals(this.sideLineMode.getValue())) {
            return Alignment.LEFT;
        }
        if ("Right".equals(this.sideLineMode.getValue())) {
            return Alignment.RIGHT;
        }
        return rowAlignment;
    }

    private void clampToScreen(float width, float height) {
        if (mc == null || mc.getWindow() == null) {
            return;
        }
        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        float maxX = Math.max(MIN_VISIBLE_EDGE, screenWidth - Math.min(width, screenWidth) - MIN_VISIBLE_EDGE);
        float maxY = Math.max(MIN_VISIBLE_EDGE, screenHeight - Math.min(height, screenHeight) - MIN_VISIBLE_EDGE);
        this.setX(Mth.clamp(this.getX(), MIN_VISIBLE_EDGE, maxX));
        this.setY(Mth.clamp(this.getY(), MIN_VISIBLE_EDGE, maxY));
    }

    @Override
    public void mouseDragged(int mouseX, int mouseY) {
        this.setX((float) mouseX - this.getDragOffsetX());
        this.setY((float) mouseY - this.getDragOffsetY());
        this.clampToScreen(Math.max(this.getWidth(), 1.0f), Math.max(this.getHeight(), 1.0f));
    }

    @Override
    public void stopDragging() {
        boolean wasDragging = this.isDragging();
        super.stopDragging();
        if (wasDragging) {
            ZenClient.getInstance().getConfigManager().saveAll();
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            this.rowStates.clear();
            return;
        }
        this.updateRows();
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
        if (!this.shouldRender()) {
            return;
        }
        List<AnimatedRow> rows = this.updateRows();
        if (rows.isEmpty()) {
            this.setWidth(0.0f);
            this.setHeight(0.0f);
            return;
        }

        float targetWidth = this.measureWidth(rows);
        float targetHeight = this.measureHeight(rows);
        float previousWidth = this.getWidth();
        Alignment anchorBeforeResize = this.resolveAlignment(x, Math.max(previousWidth, targetWidth));
        this.updateSizeAnimation(targetWidth, targetHeight);

        float width = this.widthAnim.getValueF();
        float height = this.heightAnim.getValueF();
        if (anchorBeforeResize == Alignment.RIGHT && !this.isDragging() && previousWidth > 0.0f) {
            this.setX(this.getX() + previousWidth - width);
        }
        this.clampToScreen(width, height);
        this.setWidth(width);
        this.setHeight(height);

        float drawX = this.getX();
        float drawY = this.getY();
        Alignment alignment = this.resolveAlignment(drawX, width);
        this.renderRows(rows, drawX, drawY, width, alignment);
    }

    @Override
    public void onSettings() {
    }
}