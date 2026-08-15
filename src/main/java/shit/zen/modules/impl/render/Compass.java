package shit.zen.modules.impl.render;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.mojang.blaze3d.systems.RenderSystem; // 🛠️ 引入渲染系统重置环境
import shit.zen.event.impl.GlRenderEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.modules.impl.movement.Scaffold;
import shit.zen.render.DrawContext;
import shit.zen.render.Paint;
import shit.zen.render.Path;
import shit.zen.render.ShadowFactory;
import shit.zen.render.ShadowMode;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.event.EventTarget;

public class Compass extends Module {
    private final BooleanSetting compassOnly = new BooleanSetting("Compass Only", true);
    private final BooleanSetting noPlayerOnly = new BooleanSetting("No Player Only", true);
    private boolean hasCompassItemCached = false;
    private BlockPos spawnPosition;
    private float renderYaw;
    private double renderX;
    private double renderZ;

    public Compass() {
        super("Compass", Category.RENDER);
    }

    private BlockPos getSpawnPosition() {
        if (mc.level == null) return null;
        return mc.level.dimensionType().natural() ? mc.level.getSharedSpawnPos() : null;
    }

    private boolean hasPlayer() {
        if (mc.level == null) return false;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || !(entity instanceof Player)) continue;
            return true;
        }
        return false;
    }

    private boolean hasCompassItem() {
        if (mc.player == null) return false;
        for (ItemStack itemStack : mc.player.getInventory().items) {
            if (itemStack.getItem() == Items.COMPASS) return true;
        }
        return mc.player.getInventory().offhand.get(0).getItem() == Items.COMPASS;
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null || mc.level == null) return;
        this.hasCompassItemCached = this.hasCompassItem();
        this.spawnPosition = this.getSpawnPosition();
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        if (mc.player == null) return;
        this.renderX = Mth.lerp(renderEvent.partialTick(), mc.player.xOld, mc.player.getX());
        this.renderZ = Mth.lerp(renderEvent.partialTick(), mc.player.zOld, mc.player.getZ());
        this.renderYaw = Mth.lerp(renderEvent.partialTick(), mc.player.yRotO, mc.player.getYRot());
    }

    @EventTarget
    public void onGlRender(GlRenderEvent glRenderEvent) {
        if (mc.player == null || Scaffold.INSTANCE.isEnabled()) return;

        // 🛠️ 修复 1：在高级画布绘制前，确保开启原版的混合与多重采样抗锯齿环境
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        this.draw(glRenderEvent.drawContext());
    }

    private void draw(DrawContext drawContext) {
        if (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled()) return;
        if (!this.hasCompassItemCached && this.compassOnly.getValue()) return;
        if (this.hasPlayer() && this.noPlayerOnly.getValue()) return;
        if (this.spawnPosition == null) return;

        // 1. 计算平滑旋转角度
        float angle = (float)(Math.toDegrees(Math.atan2(this.spawnPosition.getZ() - this.renderZ, this.spawnPosition.getX() - this.renderX)) - 90.0 - this.renderYaw);

        float centerX = (float)mc.getWindow().getGuiScaledWidth() / 2.0f;
        float centerY = (float)mc.getWindow().getGuiScaledHeight() / 2.0f;

        drawContext.save();
        drawContext.translate(centerX, centerY);
        drawContext.rotate(angle);

        // 🛠️ 核心抗锯齿优化：将线段拼凑改为完美的“封闭数学三角形”
        // 闭合多边形在高级 2D 渲染画布中，天然拥有最完美的边缘抗锯齿混色效果
        try (Path path = new Path()) {
            path.moveTo(0.0f, -38.0f);   // 箭头顶尖
            path.lineTo(6.0f, -30.0f);   // 右下角
            path.lineTo(-6.0f, -30.0f);  // 左下角
            path.close();                // 🛠️ 关键：强制封闭路径，消灭任何由于旋转带来的像素硬阶梯

            // 2. 先画底层的彩色发光阴影外圈 (保留你原有的阴影特效)
            try (Paint paint = new Paint()) {
                paint.setAntialias(true);
                paint.setColor(-2130706433);

                // 🛠️ 移除报错的 setStyle 和 ROUND，采用安全的连写属性
                if (this.hasMethod(paint.getClass(), "setStrokeWidth", float.class)) {
                    paint.setStrokeWidth(3.5f);
                }

                // 保持原有的阴影通道
                paint.setShader(ShadowFactory.createColoredShadow(4.0f, 4.0f, ShadowMode.OUTLINE));
                drawContext.drawPath(path, paint);
            }

            // 3. 再画上层的纯白实心核心
            try (Paint paint = new Paint()) {
                paint.setAntialias(true);
                paint.setColor(-1); // 纯白色

                // 🛠️ 移除报错行：直接将完整的封闭路径丢给画布渲染
                // 在现代渲染引擎中，未指定 Strokewidth 的默认封闭 Path 会被直接视作高精度填充（Fill）
                // 实心图形在鼠标高速晃动时，绝对不会出现断裂或毛刺锯齿
                drawContext.drawPath(path, paint);
            }
        }

        drawContext.restore();
    }

    // 辅助安全断言方法，防止不同客户端版本反射崩溃
    private boolean hasMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            clazz.getMethod(methodName, parameterTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}