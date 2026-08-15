package shit.zen.modules.impl.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import shit.zen.ClientBase;
import shit.zen.ZenClient;
import shit.zen.event.impl.PreMotionEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.event.impl.SprintEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.event.impl.WorldChangeEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;

// 导入你的 WTap 模块，假定它在同包或者 combat 包下
import shit.zen.modules.impl.combat.WTap;
import shit.zen.modules.impl.combat.antikb.NoXZMode;
import shit.zen.modules.impl.player.AntiTNT;
import shit.zen.modules.impl.player.AntiWeb;
import shit.zen.modules.impl.player.AutoWebPlace;
import shit.zen.modules.impl.player.Helper;
import shit.zen.modules.impl.player.MidPearl;
import shit.zen.modules.impl.player.Stuck;
import shit.zen.modules.impl.world.Teams;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.game.EntityUtil;
import shit.zen.utils.game.ItemUtil;
import shit.zen.utils.game.RotationUtil;
import shit.zen.utils.math.MathUtil;
import shit.zen.utils.misc.ChatUtil;
import shit.zen.utils.render.RenderUtil;
import shit.zen.utils.rotation.Rotation;
import shit.zen.utils.rotation.RotationHandler;
import shit.zen.event.EventTarget;

public class KillAura extends Module {
    public static KillAura INSTANCE;
    public static Entity target;
    public static Entity aimingTarget;
    public static List<Entity> targetList = new ArrayList<>();

    // 基础配置
    public final BooleanSetting attackPlayer    = new BooleanSetting("Attack Player", true);
    public final BooleanSetting attackInvisible = new BooleanSetting("Attack Invisible", false);
    public final BooleanSetting attackAnimals   = new BooleanSetting("Attack Animals", false);
    public final BooleanSetting attackMobs      = new BooleanSetting("Attack Mobs", true);
    public final BooleanSetting multiAttack     = new BooleanSetting("Multi Attack", true);
    public final BooleanSetting infSwitch       = new BooleanSetting("Infinity Switch", false);
    public final BooleanSetting preferBaby      = new BooleanSetting("Prefer Baby", false);
    public final BooleanSetting morePart        = new BooleanSetting("More Particles", false);
    public final BooleanSetting keepSprint      = new BooleanSetting("Keep Sprint", true);
    public final BooleanSetting ignoreSkipTicks = new BooleanSetting("Ignore skip ticks", false);
    public final BooleanSetting fakeAutoBlock   = new BooleanSetting("Fake AutoBlock", true);
    public final BooleanSetting test            = new BooleanSetting("Test", false);

    // 穿墙配置
    public final BooleanSetting throughWalls    = new BooleanSetting("Through Walls", true);

    // 数值滑杆
    public final NumberSetting aimRange    = new NumberSetting("Aim Range", 4.0, 1.0, 6.0, 0.1);
    public final NumberSetting wallRange   = new NumberSetting("Wall Range", 3.5, 1.0, 6.0, 0.1, () -> (Boolean) this.throughWalls.getValue());

    public final NumberSetting maxAps      = new NumberSetting("Max APS", 12.0, 1.0, 20.0, 1.0);
    public final NumberSetting minAps      = new NumberSetting("Min APS", 9.0, 1.0, 20.0, 1.0);
    public final NumberSetting switchSize  = new NumberSetting("Switch Size", 1.0, 1.0, 5.0, 1.0,
            () -> !(Boolean) this.infSwitch.getValue());
    public final NumberSetting switchDelay = new NumberSetting("Switch Delay (Attack Times)", 1.0, 1.0, 10.0, 1.0);
    public final NumberSetting fov         = new NumberSetting("FoV", 360.0, 10.0, 360.0, 1.0);
    public final NumberSetting hurtTime    = new NumberSetting("Hurt Time", 10.0, 0.0, 10.0, 1.0);

    public final ModeSetting delayMode    = new ModeSetting("Delay Mode", "1.8", "1.9").withDefault("1.8");
    public final ModeSetting priorityMode = new ModeSetting("Priority", "Distance", "FoV", "Health", "None").withDefault("FoV");
    public final ModeSetting targetEsp    = new ModeSetting("Target ESP", "None", "Spiral", "Box", "Tab").withDefault("None");

    private RotationUtil.BestHitInfo currentBestHit;
    private RotationUtil.BestHitInfo prevBestHit;
    private int attackTimes;
    private float attacks;
    private int targetIndex;
    public int sprintTickCounter;
    private int sprintCounter;
    public Rotation rotation;

    public KillAura() {
        super("KillAura", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.rotation = null;
        this.targetIndex = 0;
        this.attacks = 0.0f;
        target = null;
        aimingTarget = null;
        targetList.clear();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.attacks = 0.0f;
        target = null;
        aimingTarget = null;
        this.sprintTickCounter = 0;
        this.sprintCounter = 0;
        this.attackTimes = 0;
        super.onDisable();
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        target = null;
        aimingTarget = null;
        this.attacks = 0.0f;
        this.setEnabled(false);
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (this.targetEsp.is("None")) return;
        Entity entity = aimingTarget;
        if (entity == null || mc.gameRenderer == null) return;
        PoseStack poseStack = event.poseStack();
        poseStack.pushPose();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        poseStack.translate(-cameraPos.x(), -cameraPos.y(), -cameraPos.z());

        double dx = entity.getX() - entity.xOld;
        double dy = entity.getY() - entity.yOld;
        double dz = entity.getZ() - entity.zOld;
        Vec3 playerDelta = mc.player.getDeltaMovement();
        Vec3 offset = new Vec3(
                dx + playerDelta.x + 0.005,
                dy + playerDelta.y - 0.002,
                dz + playerDelta.z + 0.005);

        String mode = this.targetEsp.getValue();
        switch (mode) {
            case "Spiral" -> RenderUtil.drawSpiralEffect(poseStack, entity, event.partialTick());
            case "Box" -> {
                int hurtTime = entity instanceof LivingEntity le ? le.hurtTime : 0;
                Color color;
                if (hurtTime == 0) {
                    color = new Color(0, 0, 0, 130);
                } else if (hurtTime >= 9 && hurtTime <= 10) {
                    color = new Color(0, 255, 255, 200);
                } else {
                    color = new Color(255, 0, 0, 200);
                }
                AABB base = EntityUtil.getInterpolatedAABB(entity, event.partialTick()).move(offset);
                AABB padded = new AABB(
                        base.minX - 0.175, base.minY - 0.125, base.minZ - 0.175,
                        base.maxX + 0.175, base.maxY + 0.225, base.maxZ + 0.175);
                RenderUtil.drawFilledColoredBox(padded, poseStack, color, color);
            }
            case "Tab" -> {
                int hurtTime = entity instanceof LivingEntity le ? le.hurtTime : 0;
                Color color;
                if (hurtTime == 0) {
                    color = new Color(0, 0, 0, 130);
                } else if (hurtTime == 3) {
                    color = new Color(255, 255, 255, 200);
                } else {
                    color = new Color(255, 0, 0, 200);
                }
                AABB base = EntityUtil.getInterpolatedAABB(entity, event.partialTick()).move(offset);
                AABB band = new AABB(
                        base.minX, base.minY + entity.getEyeHeight() + 0.11, base.minZ,
                        base.maxX, base.maxY - 0.13, base.maxZ);
                RenderUtil.drawFilledColoredBox(band, poseStack, color, color);
            }
            default -> {
            }
        }
        poseStack.popPose();
    }

    @EventTarget
    public void onSprint(SprintEvent event) {
        if (this.keepSprint.getValue()) {
            ++this.sprintTickCounter;
            if (this.sprintTickCounter % 2 == 0 && mc.player != null) {
                mc.player.setSprinting(false);
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!ZenClient.isReady()) {
            return;
        }
        if (mc.screen instanceof AbstractContainerScreen
                || ItemUtil.hasServerItem()
                || (Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled())
                || (Helper.INSTANCE != null && Helper.INSTANCE.isEnabled() && Helper.targetRotation != null)
                || AntiWeb.targetRotation != null
                || AntiTNT.targetRotation != null
                || MidPearl.targetRotation != null
                || this.isWebPlacing()) {
            target = null;
            aimingTarget = null;
            this.currentBestHit = null;
            this.rotation = null;
            this.prevBestHit = null;
            targetList.clear();
            this.sprintTickCounter = 0;
            this.attacks = 0.0f;
            this.sprintCounter = 0;
            return;
        }
        boolean isSwitch = this.switchSize.getValue().intValue() > 1
                || this.infSwitch.getValue()
                || this.multiAttack.getValue();
        this.updateTargets();
        aimingTarget = this.getTarget();
        this.prevBestHit = this.currentBestHit;
        this.currentBestHit = null;
        if (aimingTarget != null) {
            this.currentBestHit = RotationUtil.getBestHit(aimingTarget);
            // 🌟 修复渲染和模块视角记录，应用 GrimAC 算法
            this.rotation = this.currentBestHit != null ? this.applyGrimBypass(this.currentBestHit.rotation()) : null;
        } else {
            this.rotation = null;
        }
        if (targetList.isEmpty()) {
            target = null;
            return;
        }
        if (this.targetIndex > targetList.size() - 1) {
            this.targetIndex = 0;
        }
        if (targetList.size() > 1
                && (this.attackTimes >= this.switchDelay.getValue().intValue()
                || (this.currentBestHit != null && this.currentBestHit.distance() > 3.0))) {
            this.attackTimes = 0;
            for (int i = 0; i < targetList.size(); ++i) {
                ++this.targetIndex;
                if (this.targetIndex > targetList.size() - 1) {
                    this.targetIndex = 0;
                }
                Entity nextTarget = targetList.get(this.targetIndex);
                RotationUtil.BestHitInfo nextHit = RotationUtil.getBestHit(nextTarget);
                if (nextHit != null && nextHit.distance() < 3.0) {
                    break;
                }
            }
        }
        if (this.targetIndex > targetList.size() - 1 || !isSwitch) {
            this.targetIndex = 0;
        }
        target = targetList.get(this.targetIndex);
        if (this.delayMode.is("1.8")) {
            float apsValue;
            float minApsValue;
            if (NoXZMode.isAttacking) {
                int kbAttackAmount = AntiKB.INSTANCE != null
                        ? AntiKB.INSTANCE.attackAmount.getValue().intValue()
                        : 0;
                apsValue = this.maxAps.getValue().floatValue() - kbAttackAmount;
                minApsValue = this.minAps.getValue().floatValue() - kbAttackAmount;
            } else {
                apsValue = this.maxAps.getValue().floatValue();
                minApsValue = this.minAps.getValue().floatValue();
            }
            this.attacks += (float)(MathUtil.randomDouble(minApsValue, apsValue) / 20.0);
        } else if (this.sprintCounter > 0) {
            this.sprintCounter--;
        } else if (mc.player.getAttackStrengthScale(0.0f) >= 0.9f) {
            this.doAttack();
        }
    }

    @EventTarget
    public void onPreMotion(PreMotionEvent event) {
        if (mc.player == null) return;
        if (this.isWebPlacing()) {
            this.attacks = 0.0f;
            return;
        }
        if (mc.player.getUseItem().isEmpty()
                && mc.screen == null
                && (this.ignoreSkipTicks.getValue() || ClientBase.delayPackets.isEmpty())) {
            while (this.attacks >= 1.0f) {
                this.doAttack();
                this.attacks -= 1.0f;
            }
        } else {
            this.attacks = 0.0f;
        }
    }

    public void doAttack() {
        if (this.isWebPlacing()) {
            this.attacks = 0.0f;
            return;
        }
        if (targetList.isEmpty()) return;

        HitResult hitResult = mc.hitResult;
        if (hitResult != null && hitResult.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((EntityHitResult) hitResult).getEntity();
            if (AntiBots.isBot(hitEntity)) {
                ChatUtil.print("Skipped attack on suspected bot");
                return;
            }
        }
        if (this.multiAttack.getValue()) {
            int attacked = 0;
            for (Entity entity : targetList) {
                if (mc.player == null) break;
                RotationUtil.BestHitInfo subHit = RotationUtil.getBestHit(entity);
                if (subHit == null) continue;

                Rotation subRot = subHit.rotation();
                if (RotationUtil.getHitDistance(entity, mc.player.getEyePosition(), subRot) >= this.aimRange.getValue().floatValue()) continue;

                // 🌟 【核心修改点 1】：在分发静默视角包前，强行将视角进行 GCD 对齐和噪声去重
                subRot = this.applyGrimBypass(subRot);

                Rotation prevRot = RotationHandler.targetRotation;
                RotationHandler.targetRotation = subRot;

                this.attackEntity(entity);
                RotationHandler.targetRotation = prevRot;

                if (++attacked >= 2) break;
            }
        } else if (hitResult != null && hitResult.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((EntityHitResult) hitResult).getEntity();
            this.attackEntity(hitEntity);
        }
    }

    public Entity getTarget() {
        Entity entity = target;
        if (entity == null) {
            List<Entity> list = this.getTargets();
            if (!list.isEmpty()) {
                entity = list.get(0);
            }
        }
        if (entity != null) {
            AntiBots antiBots = AntiBots.INSTANCE;
            if (antiBots != null && antiBots.isEnabled() && AntiBots.isBot(entity)) {
                return null;
            }
        }
        return entity;
    }

    public void updateTargets() {
        List<Entity> next = this.getTargets();
        targetList = next != null ? next : new ArrayList<>();
    }

    public boolean isValidTarget(Entity entity) {
        if (!ZenClient.isReady()) return false;
        if (entity == mc.player) return false;
        if (entity instanceof LivingEntity livingEntity) {
            AntiBots antiBots = AntiBots.INSTANCE;
            if (antiBots != null && antiBots.isEnabled() && (AntiBots.isBot(entity) || AntiBots.isBedWarsBot(entity))) {
                return false;
            }
            if (livingEntity.isDeadOrDying() || livingEntity.getHealth() <= 0.0f) return false;
            if (entity instanceof ArmorStand) return false;
            if (entity.isInvisible() && !(Boolean) this.attackInvisible.getValue()) return false;
            if (entity instanceof Player player) {
                if (this.test.getValue() && player.getY() >= mc.player.getY() + 0.05f) {
                    return true;
                }
            }
            if (Teams.isSameTeam(entity)) return false;
            if (entity instanceof Player && !(Boolean) this.attackPlayer.getValue()) return false;
            if (entity instanceof Player && (entity.getBbWidth() < 0.5 || livingEntity.isSleeping())) return false;
            if ((entity instanceof Mob || entity instanceof Slime || entity instanceof Bat || entity instanceof AbstractGolem)
                    && !(Boolean) this.attackMobs.getValue()) {
                return false;
            }
            if ((entity instanceof Animal || entity instanceof Squid) && !(Boolean) this.attackAnimals.getValue()) {
                return false;
            }
            if (entity instanceof Villager && !(Boolean) this.attackAnimals.getValue()) return false;
            return !(entity instanceof Player) || !entity.isSpectator();
        }
        return false;
    }

    public boolean isValidAttack(Entity entity) {
        if (mc.player == null) return false;
        if (!this.isValidTarget(entity)) return false;
        if (entity instanceof LivingEntity le && le.hurtTime > this.hurtTime.getValue().intValue()) {
            return false;
        }

        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 closestPoint = RotationUtil.closestPoint(eyePos, entity.getBoundingBox());
        double distance = closestPoint.distanceTo(eyePos);

        boolean isVisible = mc.level.clip(new ClipContext(
                eyePos,
                closestPoint,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mc.player
        )).getType() == HitResult.Type.MISS;

        if (!isVisible) {
            if (!(Boolean) this.throughWalls.getValue()) {
                return false;
            }
            if (distance > this.wallRange.getValue().floatValue()) {
                return false;
            }
        } else {
            if (distance > this.aimRange.getValue().floatValue()) {
                return false;
            }
        }

        return RotationUtil.isEntityInFov(entity, this.fov.getValue().floatValue() / 2.0f);
    }

    public void attackEntity(Entity entity) {
        if (mc.player == null || mc.gameMode == null) return;
        if (this.isWebPlacing()) return;

        ++this.attackTimes;
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();
        if (RotationHandler.targetRotation != null) {
            // 🌟 【核心修改点 2】：双保险，在强行修改玩家实体视角字段前再次进行网格修复和加噪
            Rotation fixedRot = this.applyGrimBypass(RotationHandler.targetRotation);
            mc.player.setYRot(fixedRot.getYaw());
            mc.player.setXRot(fixedRot.getPitch());
        }

        int attackKey = mc.options.keyAttack.getKey().getValue();

        // 执行伤害包交互
        mc.gameMode.attack(mc.player, entity);
        ForgeHooksClient.onMouseButtonPre(attackKey, 1, 0);
        mc.player.swing(InteractionHand.MAIN_HAND);
        ForgeHooksClient.onMouseButtonPost(attackKey, 1, 0);

        // ================= W-TAP 联动核心 =================
        if (WTap.INSTANCE != null && WTap.INSTANCE.isEnabled()) {
            WTap.INSTANCE.onAttackTarget();
        }
        // =================================================
        if (FakeLag.INSTANCE != null && FakeLag.INSTANCE.isEnabled()) {
            FakeLag.INSTANCE.notifyAttack();
        }

        if (this.morePart.getValue()) {
            mc.player.magicCrit(entity);
            mc.player.crit(entity);
        }

        mc.player.setYRot(currentYaw);
        mc.player.setXRot(currentPitch);

        if (this.delayMode.is("1.9")) {
            this.sprintCounter = (int) mc.player.getCurrentItemAttackStrengthDelay();
        }
    }

    // 🌟 【核心修改点 3】：新增内置的绕过算法函数
    private Rotation applyGrimBypass(Rotation targetRot) {
        if (targetRot == null || mc.player == null) return targetRot;

        float targetYaw = targetRot.getYaw();
        float targetPitch = targetRot.getPitch();
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();

        // 1. 读取客户端当前的真实鼠标灵敏度配置
        double sensitivity = mc.options.sensitivity().get();
        double f = sensitivity * 0.6F + 0.2F;
        double f3 = f * f * f;

        // 2. 还原原版计算公式，推导当前帧的真实鼠标最大公约数 (GCD)
        double gcd = f3 * 8.0D * 0.15D;

        float yawDiff = targetYaw - currentYaw;
        float pitchDiff = targetPitch - currentPitch;

        // 3. 注入高频微小离散噪声 (0.01格点抖动)。
        // 即使多次连击导致目标没动，由于每次的随机噪值不同，网格化取整 (Math.round) 出来的数据也会截然不同，
        // 这样可以彻底打破相同发包特征，完美破除 ACA 的 equalrotation。
        yawDiff += (float) ((Math.random() - 0.5) * 0.025);
        pitchDiff += (float) ((Math.random() - 0.5) * 0.025);

        // 4. 将数学角度代入鼠标移动网格步长，求出真实的步数，并对齐网格
        int roundedYawSteps = (int) Math.round(yawDiff / gcd);
        int roundedPitchSteps = (int) Math.round(pitchDiff / gcd);

        float fixedYaw = currentYaw + (float) (roundedYawSteps * gcd);
        float fixedPitch = currentPitch + (float) (roundedPitchSteps * gcd);

        // 5. 限制抬头低头极限角度
        fixedPitch = Math.max(-90.0f, Math.min(90.0f, fixedPitch));

        return new Rotation(fixedYaw, fixedPitch);
    }

    private boolean isWebPlacing() {
        return AutoWebPlace.INSTANCE != null && AutoWebPlace.INSTANCE.isEnabled() && AutoWebPlace.targetRotation != null;
    }

    private List<Entity> getTargets() {
        if (mc.player == null || mc.level == null) {
            return new ArrayList<>();
        }
        Stream<Entity> stream = StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), true)
                .filter(this::isValidAttack);
        List<Entity> possibleTargets = stream.collect(Collectors.toList());
        if (this.priorityMode.is("Distance")) {
            possibleTargets.sort(Comparator.comparingDouble(KillAura::getDistanceToPlayer));
        } else if (this.priorityMode.is("FoV")) {
            possibleTargets.sort(Comparator.comparingDouble(KillAura::getAngleDiffToTarget));
        } else if (this.priorityMode.is("Health")) {
            possibleTargets.sort(Comparator.comparingDouble(KillAura::getEntityHealth));
        }
        if (this.preferBaby.getValue()
                && possibleTargets.stream().anyMatch(KillAura::isBaby)) {
            possibleTargets.removeIf(KillAura::isNotBaby);
        }
        possibleTargets.sort(Comparator.comparing(KillAura::getCrystalPriority));

        if (target != null && possibleTargets.contains(target)) {
            possibleTargets.remove(target);
            possibleTargets.add(0, target);
        }

        if (this.infSwitch.getValue()) {
            return possibleTargets;
        }
        int limit = (int) Math.min(possibleTargets.size(), this.switchSize.getValue().intValue());
        return new ArrayList<>(possibleTargets.subList(0, limit));
    }

    private static Integer getCrystalPriority(Entity entity) {
        return entity instanceof EndCrystal ? 0 : 1;
    }

    private static boolean isNotBaby(Entity entity) {
        return !(entity instanceof LivingEntity) || !((LivingEntity) entity).isBaby();
    }

    private static boolean isBaby(Entity entity) {
        return entity instanceof LivingEntity && ((LivingEntity) entity).isBaby();
    }

    private static double getEntityHealth(Entity entity) {
        if (entity instanceof LivingEntity le) {
            return le.getHealth();
        }
        return 0.0;
    }

    private static double getAngleDiffToTarget(Entity entity) {
        if (RotationHandler.targetRotation == null) return 360.0;
        return RotationUtil.angleDiff(RotationHandler.targetRotation.getYaw(), RotationUtil.entityRotation(entity).getYaw());
    }

    private static double getDistanceToPlayer(Entity entity) {
        return entity.distanceTo(mc.player);
    }

    private static boolean isLivingEntity(Entity entity) {
        return entity instanceof LivingEntity;
    }
}