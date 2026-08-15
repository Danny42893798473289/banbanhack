package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.texture.OverlayTexture;
import shit.zen.event.impl.EntityHurtEvent;
import shit.zen.event.impl.EntityRemoveEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.event.EventTarget;

public class DamageGlow extends Module {

    public record EntitySnapshot(long startTime, long expireTime, double snapshotX, double snapshotY, double snapshotZ,
                                 double xo, double yo, double zo, float yRot, float xRot, float yHeadRot,
                                 float xRotCopy, float yBodyRot, float yBodyRotO, float yHeadRotO, float xRotO,
                                 float walkAnimPos, float walkAnimSpeed, float attackAnim, float tickCount,
                                 float headYawDelta, float pitch, int hurtTime) {
    }

    public static DamageGlow INSTANCE;

    private final Map<Integer, List<EntitySnapshot>> glowingEntities = new ConcurrentHashMap<>();
    private final NumberSetting colorRSetting = new NumberSetting("Color R", 255, 0, 255, 1); // 🛠️默认红色，方便测试
    private final NumberSetting colorGSetting = new NumberSetting("Color G", 0, 0, 255, 1);
    private final NumberSetting colorBSetting = new NumberSetting("Color B", 0, 0, 255, 1);
    private final NumberSetting alphaSetting = new NumberSetting("Alpha", 150.0, 0.0, 255.0, 1.0); // 🛠️调高初始透明度

    public DamageGlow() {
        super("DamageGlow", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.glowingEntities.clear();
    }

    private void addGlowEffect(LivingEntity entity) {
        List<EntitySnapshot> list = this.glowingEntities.computeIfAbsent(entity.getId(), k -> new CopyOnWriteArrayList<>());
        this.cleanExpiredGlows(list);
        EntitySnapshot last = list.isEmpty() ? null : list.get(list.size() - 1);
        int hurt = entity.hurtTime;
        long now = System.currentTimeMillis();

        // 🛠️如果实体没受伤但是濒死/移除，也强制生成一层残影
        if (hurt <= 0 && entity.getHealth() > 0) return;

        if (last != null) {
            int elapsed = (int) ((now - last.startTime) / 50L);
            int remainder = Math.max(0, last.hurtTime - elapsed);
            if (hurt <= remainder && entity.getHealth() > 0) return;
        }

        float headYawDelta = entity.getYHeadRot() - entity.yBodyRot;
        float pitch = entity.getXRot();
        float tickCount = entity.tickCount + mc.getFrameTime();

        EntitySnapshot snapshot = new EntitySnapshot(now, now + 1200L, // 🛠️略微缩短残影留存时间(1.2秒)，视觉体验更佳
                entity.getX(), entity.getY(), entity.getZ(),
                entity.xo, entity.yo, entity.zo,
                entity.getYRot(), entity.getXRot(), entity.getYHeadRot(), entity.getXRot(),
                entity.yBodyRot, entity.yBodyRotO, entity.yHeadRotO, entity.xRotO,
                entity.walkAnimation.position(), entity.walkAnimation.speed(), entity.attackAnim,
                tickCount, headYawDelta, pitch, hurt);
        list.add(snapshot);

        if (list.size() > 4) { // 🛠️最大残影数控在 4 个，防止过多导致严重掉帧
            list.remove(0);
        }
    }

    private void cleanExpiredGlows(List<EntitySnapshot> list) {
        long now = System.currentTimeMillis();
        list.removeIf(s -> now > s.expireTime);
    }

    @EventTarget
    public void onEntityHurt(EntityHurtEvent event) {
        if (event.entity() instanceof Player && event.entity() == mc.player) return;
        this.addGlowEffect(event.entity());
    }

    @EventTarget
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.entity() instanceof Player && event.entity() == mc.player) return;
        if (event.entity() instanceof LivingEntity entity) {
            this.addGlowEffect(entity);
        }
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        if (mc.level == null || mc.gameRenderer.getMainCamera() == null) return;

        // 🛠️提取 BufferSource 到循环外部
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();

        for (Map.Entry<Integer, List<EntitySnapshot>> entry : this.glowingEntities.entrySet()) {
            Integer id = entry.getKey();
            List<EntitySnapshot> list = entry.getValue();
            this.cleanExpiredGlows(list);
            if (list.isEmpty()) continue;

            Entity entity = mc.level.getEntity(id);
            if (entity instanceof LivingEntity le) {
                this.renderEntityGlow(le, list, renderEvent, bufferSource, camera);
            }
        }
        this.glowingEntities.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void renderEntityGlow(LivingEntity entity, List<EntitySnapshot> list, RenderEvent renderEvent, MultiBufferSource.BufferSource bufferSource, Vec3 camera) {
        EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof LivingEntityRenderer livingRenderer)) return;
        EntityModel model = livingRenderer.getModel();

        // 🛠️获取发光渲染类型：使用 translucent 允许透明度通道，或使用 glint(附魔外壳) 增强视觉
        RenderType type = RenderType.entityTranslucent(renderer.getTextureLocation(entity));
        VertexConsumer consumer = bufferSource.getBuffer(type);

        for (EntitySnapshot snapshot : list) {
            float alphaFactor = this.calcGlowAlpha(snapshot);
            if (alphaFactor <= 0.0f) continue;

            PoseStack poseStack = renderEvent.poseStack();
            poseStack.pushPose();

            // 🛠️根据相机相对坐标精准定位残影位置
            double renderX = snapshot.snapshotX - camera.x;
            double renderY = snapshot.snapshotY - camera.y;
            double renderZ = snapshot.snapshotZ - camera.z;
            poseStack.translate(renderX, renderY, renderZ);

            // 🛠️还原实体当时的朝向旋转
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - snapshot.yRot));
            poseStack.mulPose(Axis.XP.rotationDegrees(snapshot.xRot));

            // 🛠️Minecraft 实体模型标准反置缩放与高度修正
            poseStack.scale(-1.0f, -1.0f, 1.0f);
            poseStack.translate(0.0f, -1.501f, 0.0f);

            // 🛠️同步当时的肢体骨骼动画
            model.prepareMobModel(entity, snapshot.walkAnimPos, snapshot.walkAnimSpeed, 0.0f);
            model.attackTime = snapshot.attackAnim;
            model.setupAnim(entity, snapshot.walkAnimPos, snapshot.walkAnimSpeed, snapshot.tickCount, snapshot.headYawDelta, snapshot.pitch);

            // 🛠️颜色与透明度计算
            float r = this.colorRSetting.getValue().floatValue() / 255.0f;
            float g = this.colorGSetting.getValue().floatValue() / 255.0f;
            float b = this.colorBSetting.getValue().floatValue() / 255.0f;
            float a = this.alphaSetting.getValue().floatValue() / 255.0f * alphaFactor;

            // 🛠️不破坏原有实体属性，OverlayTexture.NO_OVERLAY 确保残影本身不会附带原生的红色受伤覆盖层
            model.renderToBuffer(poseStack, consumer, 0xF000F0, OverlayTexture.NO_OVERLAY, r, g, b, a);

            poseStack.popPose();
        }

        // 🛠️关键修复：将结束批处理移出循环，在当前实体所有残影顶点数据提交完成后，强行 Flush 进显卡管道
        bufferSource.endBatch(type);
    }

    private float calcGlowAlpha(EntitySnapshot snapshot) {
        long remaining = snapshot.expireTime - System.currentTimeMillis();
        if (remaining <= 0L) return 0.0f;
        return Math.min(1.0f, remaining / 1200.0f);
    }
}