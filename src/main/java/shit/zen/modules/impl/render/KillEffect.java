package shit.zen.modules.impl.render;

import java.util.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.player.Player;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.TickEvent;
import shit.zen.hud.TargetHud;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.modules.impl.combat.KillAura;
import shit.zen.utils.misc.SoundUtil;

public class KillEffect extends Module {

    private static final double RISE_SPEED = 0.1;
    private static final double PERCENT_STEP = 0.02;

    private static class SquidEffect {
        final Squid squid;
        double percent;
        SquidEffect(Squid squid) { this.squid = squid; }
    }

    private final List<SquidEffect> activeSquids = new ArrayList<>();
    private final Set<UUID> triggeredUUIDs = new HashSet<>();

    // 缓存攻击过的实体的最后坐标（UUID -> 坐标），用于 isRemoved 时仍然能生成鱿鱼
    private final Map<UUID, double[]> lastPositionMap = new HashMap<>();

    public KillEffect() {
        super("KillEffect", Category.RENDER);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.level == null || mc.player == null) {
            clearEffects();
            return;
        }

        // 1. 检查 KillAura 当前目标
        if (KillAura.aimingTarget instanceof LivingEntity target) {
            UUID uuid = target.getUUID();

            // 缓存当前坐标（只要还在攻击就会不断更新）
            lastPositionMap.put(uuid, new double[]{target.getX(), target.getY(), target.getZ()});

            if (!triggeredUUIDs.contains(uuid)) {
                boolean dead = false;

                if (target instanceof Player player) {
                    // ① 计分板真实血量（服务器数据，最优先）
                    String name = player.getName().getString();
                    if (TargetHud.playerHealthMap.containsKey(name)) {
                        int score = TargetHud.playerHealthMap.get(name).get();
                        if (score <= 0) dead = true;
                    }
                    // ② 原版死亡动画/血量归零
                    if (!dead && (player.isDeadOrDying() || player.getHealth() <= 0.0f)) {
                        dead = true;
                    }
                    // ③ 实体被移除（死亡或离线后客户端移除），此时需要用到缓存的坐标
                    if (!dead && player.isRemoved()) {
                        dead = true;
                    }
                } else {
                    // 动物/怪物：同样多级判断
                    if (target.isDeadOrDying() || target.getHealth() <= 0.0f) {
                        dead = true;
                    }
                    if (!dead && target.isRemoved()) {
                        dead = true;
                    }
                }

                if (dead) {
                    // 优先用缓存的坐标（防止实体已移除导致 getX() 错误）
                    double[] pos = lastPositionMap.getOrDefault(uuid,
                            new double[]{target.getX(), target.getY(), target.getZ()});
                    spawnSquid(pos[0], pos[1] + 1.0, pos[2]);
                    triggeredUUIDs.add(uuid);
                }
            }
        }

        // 2. 驱动鱿鱼上升 + 爆炸（逻辑不变）
        Iterator<SquidEffect> squidIter = activeSquids.iterator();
        while (squidIter.hasNext()) {
            SquidEffect effect = squidIter.next();
            Squid squid = effect.squid;

            if (squid.isRemoved()) {
                squidIter.remove();
                continue;
            }

            effect.percent += PERCENT_STEP;
            if (effect.percent >= 1.0) {
                double ey = squid.getY() + 0.5;
                mc.level.addParticle(ParticleTypes.EXPLOSION, squid.getX(), ey, squid.getZ(), 0, 0, 0);
                for (int i = 0; i < 40; i++) {
                    double sx = (Math.random() - 0.5) * 0.8;
                    double sy = (Math.random() - 0.5) * 0.8;
                    double sz = (Math.random() - 0.5) * 0.8;
                    mc.level.addParticle(ParticleTypes.FIREWORK, squid.getX(), ey, squid.getZ(), sx, sy, sz);
                    mc.level.addParticle(ParticleTypes.FLAME, squid.getX(), ey, squid.getZ(), sx, sy, sz);
                }
                squid.discard();
                squidIter.remove();
            } else {
                squid.setPos(squid.getX(), squid.getY() + RISE_SPEED, squid.getZ());
                squid.setDeltaMovement(0.0, RISE_SPEED, 0.0);
            }
        }
    }

    private void spawnSquid(double x, double y, double z) {
        SoundUtil.playSound("kill.wav", 1.0f);
        Squid squid = new Squid(EntityType.SQUID, mc.level);
        int fakeId = -114514 - (int)(Math.random() * 100000);
        squid.setId(fakeId);
        squid.setPos(x, y, z);
        squid.setNoGravity(true);
        squid.setInvulnerable(true);
        squid.setYRot(mc.player.getYRot());
        mc.level.putNonPlayerEntity(squid.getId(), squid);
        activeSquids.add(new SquidEffect(squid));
    }

    private void clearEffects() {
        if (mc.level != null) {
            for (SquidEffect e : activeSquids) e.squid.discard();
        }
        activeSquids.clear();
        triggeredUUIDs.clear();
        lastPositionMap.clear();
    }

    @Override
    public void onDisable() {
        clearEffects();
    }
}