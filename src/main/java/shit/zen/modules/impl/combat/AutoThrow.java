package shit.zen.modules.impl.combat;

import java.util.Comparator;
import java.util.Optional;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import shit.zen.event.impl.SprintEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.modules.impl.movement.Scaffold;
import shit.zen.modules.impl.player.Stuck;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.animation.Timer;
import shit.zen.utils.game.PlayerUtil;
import shit.zen.utils.game.RotationUtil;
import shit.zen.utils.rotation.Rotation;
import shit.zen.utils.rotation.RotationHandler;
import shit.zen.event.EventTarget;

public class AutoThrow extends Module {
    public static AutoThrow INSTANCE;
    private final NumberSetting minDistance = new NumberSetting("Min Distance", 5, 3, 30, 1);
    private final NumberSetting maxDistance = new NumberSetting("Max Distance", 10, 3, 30, 1);
    private final NumberSetting throwDelay = new NumberSetting("Delay", 500, 50, 2000, 50);
    private final Timer throwTimer = new Timer();
    public Rotation targetRotation;
    public int ticksUntilThrow;
    private int savedSlot = -1;

    public AutoThrow() {
        super("AutoThrow", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.targetRotation = null;
        this.ticksUntilThrow = 0;
        this.savedSlot = -1;
        this.throwTimer.reset();
    }

    @Override
    public void onDisable() {
        this.targetRotation = null;
        this.ticksUntilThrow = 0;
        this.savedSlot = -1;
    }

    @EventTarget
    public void onSprint(SprintEvent sprintEvent) {
        int slot;
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            return;
        }
        if (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled() || Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled() || mc.player.isUsingItem() || mc.screen != null) {
            this.ticksUntilThrow = 0;
            this.targetRotation = null;
            return;
        }
        if (this.ticksUntilThrow <= 0) {
            this.targetRotation = null;
        }
        int projectileSlot = -1;
        for (slot = 0; slot < 9; ++slot) {
            ItemStack itemStack = mc.player.getInventory().getItem(slot);
            if (itemStack.isEmpty() || !(itemStack.getItem() instanceof EggItem) && !(itemStack.getItem() instanceof SnowballItem)) continue;
            projectileSlot = slot;
            break;
        }
        if (mc.player.isUsingItem() || mc.player.getMainHandItem().getItem() instanceof BowItem || mc.player.getMainHandItem().getItem() instanceof CrossbowItem) {
            return;
        }
        if (projectileSlot == -1) return;

        if (--this.ticksUntilThrow == 0) {
            slot = mc.player.getInventory().selected;
            boolean shouldSwap = slot != projectileSlot;
            if (shouldSwap) {
                mc.player.getInventory().selected = projectileSlot;
                PlayerUtil.sendCarriedItem();
                this.savedSlot = slot;
            }
            float prevYaw = mc.player.getYRot();
            float prevPitch = mc.player.getXRot();
            if (RotationHandler.targetRotation != null && RotationHandler.isRotating) {
                mc.player.setYRot(RotationHandler.targetRotation.getYaw());
                mc.player.setXRot(RotationHandler.targetRotation.getPitch());
            }
            try {
                if (!(mc.player.getMainHandItem().getItem() instanceof EggItem) && !(mc.player.getMainHandItem().getItem() instanceof SnowballItem)) return;
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            } finally {
                mc.player.setYRot(prevYaw);
                mc.player.setXRot(prevPitch);
            }
        } else {
            if (!this.findTarget().isPresent() || !this.throwTimer.hasPassed((float)((long) this.throwDelay.getValue().doubleValue())) || Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled()) return;

            // 计算精准物理抛物线角度
            this.targetRotation = this.calculatePreciseThrow(this.findTarget().get());
            if (this.targetRotation != null) {
                RotationHandler.setTargetRotation(this.targetRotation);
                RotationHandler.isRotating = true;
                this.ticksUntilThrow = 2;
            }
            this.throwTimer.reset();
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.savedSlot != -1) {
            mc.player.getInventory().selected = this.savedSlot;
            this.savedSlot = -1;
            RotationHandler.isRotating = false;
        }
    }

    /**
     * 完美模拟原版投掷物物理特性的解算算法（带阻力与运动预判）
     */
    private Rotation calculatePreciseThrow(Entity entity) {
        // 原版雪球/鸡蛋的物理常数
        double velocity = 1.5;
        double gravity = 0.03;
        double drag = 0.99;

        // 获取敌人的运动向量
        double velX = entity.getX() - entity.xOld;
        double velY = entity.getY() - entity.yOld;
        double velZ = entity.getZ() - entity.zOld;

        Vec3 myEyePos = new Vec3(mc.player.getX(), mc.player.getY() + mc.player.getEyeHeight(), mc.player.getZ());

        // 目标基准点设定为敌人身高 75% 处（胸口到头部位置，命中率最高）
        double targetX = entity.getX();
        double targetY = entity.getY() + entity.getBbHeight() * 0.75;
        double targetZ = entity.getZ();

        // 第一次估算飞行时间
        double dist = myEyePos.distanceTo(new Vec3(targetX, targetY, targetZ));
        int estimatedTicks = (int) Math.ceil(dist / velocity);

        // 进行 3 次预判迭代，修正因为敌人位移造成的飞行时间偏差
        for (int i = 0; i < 3; i++) {
            targetX = entity.getX() + velX * estimatedTicks;
            targetY = entity.getY() + entity.getBbHeight() * 0.75 + velY * estimatedTicks;
            targetZ = entity.getZ() + velZ * estimatedTicks;

            dist = myEyePos.distanceTo(new Vec3(targetX, targetY, targetZ));
            estimatedTicks = (int) Math.ceil(dist / velocity);
        }

        // 相对坐标差
        double dx = targetX - myEyePos.x;
        double dz = targetZ - myEyePos.z;
        double dy = targetY - myEyePos.y;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // 计算水平 Yaw 角度
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;

        // 核心解算：通过模拟步进迭代寻找能够命中目标的真实开火 Pitch 角度
        float bestPitch = Float.NaN;
        double closestDiff = Double.MAX_VALUE;

        // 在 -80 到 80 度之间搜索最适合的抛物线仰角
        for (float pitch = -80.0f; pitch <= 80.0f; pitch += 0.5f) {
            double radYaw = Math.toRadians(yaw + 90.0f);
            double radPitch = Math.toRadians(-pitch);

            // 完美还原原版投掷物生成时的初始动量公式
            double simVelX = Math.cos(radPitch) * Math.cos(radYaw) * velocity;
            double simVelY = Math.sin(radPitch) * velocity;
            double simVelZ = Math.cos(radPitch) * Math.sin(radYaw) * velocity;

            // 如果玩家本身有速度，原版会继承一部分玩家动量
            Vec3 myMotion = mc.player.getDeltaMovement();
            simVelX += myMotion.x;
            simVelY += mc.player.onGround() ? 0 : myMotion.y; // 简化处理避免起跳干扰
            simVelZ += myMotion.z;

            double currentX = 0;
            double currentY = 0;
            double currentZ = 0;
            boolean hitFound = false;

            // 最大模拟 60 Tick (3秒钟) 的飞行轨迹
            for (int tick = 0; tick < 60; tick++) {
                currentX += simVelX;
                currentY += simVelY;
                currentZ += simVelZ;

                // 应用原版空气阻力和重力
                simVelX *= drag;
                simVelY *= drag;
                simVelZ *= drag;
                simVelY -= gravity;

                double simHorizDist = Math.sqrt(currentX * currentX + currentZ * currentZ);

                // 当水平距离匹配时，检查 Y 轴高度差
                if (simHorizDist >= horizontalDist) {
                    double yDiff = Math.abs(currentY - dy);
                    if (yDiff < closestDiff) {
                        closestDiff = yDiff;
                        bestPitch = pitch;
                    }
                    // 容错率：如果垂直高度和目标差距小于 0.25 格，说明这颗抛物线能砸中
                    if (yDiff <= 0.25) {
                        hitFound = true;
                    }
                    break;
                }
            }
            if (hitFound) break; // 找到完美抛物线，提前结束搜索
        }

        if (Double.isNaN(bestPitch)) {
            // 如果没找到完美抛物线，fallback 到原版的简单仰角
            return new Rotation(yaw, (float) (-Math.atan2(dy, horizontalDist) * 180.0 / Math.PI));
        }

        return new Rotation(yaw, bestPitch);
    }

    private Optional<? extends Player> findTarget() {
        if (mc.player == null || mc.level == null) {
            return Optional.empty();
        }
        return mc.level.players().stream().filter(player -> player != mc.player).filter(player -> KillAura.INSTANCE.isValidTarget(player)).filter(player -> {
            double dist = this.getDistanceTo(player);
            return dist >= this.minDistance.getValue().doubleValue() && dist <= this.maxDistance.getValue().doubleValue();
        }).filter(this::hasLineOfSight).filter(player -> !this.isInvisibleAlly(player)).min(Comparator.comparingDouble(player -> mc.player.distanceTo(player)));
    }

    private double getDistanceTo(Entity entity) {
        double dx = mc.player.getX() - entity.getX();
        double dz = mc.player.getZ() - entity.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean hasLineOfSight(Entity entity) {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        Vec3 eyePos = new Vec3(mc.player.getX(), mc.player.getY() + (double)mc.player.getEyeHeight(), mc.player.getZ());
        Vec3 targetPos = new Vec3(entity.getX(), entity.getY() + (double)entity.getEyeHeight() * 0.75, entity.getZ());
        BlockHitResult hit = mc.level.clip(new ClipContext(eyePos, targetPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean isInvisibleAlly(Entity entity) {
        if (!entity.isInvisible()) {
            return false;
        }
        if (mc.player.isSpectator()) {
            return false;
        }
        Team team = entity.getTeam();
        return team == null || mc.player.getTeam() != team || !team.isAlliedTo(mc.player.getTeam());
    }
}