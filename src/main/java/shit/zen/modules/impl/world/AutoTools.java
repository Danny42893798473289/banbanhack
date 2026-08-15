package shit.zen.modules.impl.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import shit.zen.event.impl.MotionEvent;
import shit.zen.event.impl.UpdateHeldItemEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.modules.impl.combat.KillAura;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.event.EventTarget;

public class AutoTools extends Module {

    private final BooleanSetting checkSword = new BooleanSetting("Check Sword", true);
    private final BooleanSetting autoWeapon = new BooleanSetting("Auto Weapon", true);
    private final BooleanSetting switchBack = new BooleanSetting("Switch Back", true);
    private final BooleanSetting silent = new BooleanSetting("Silent", true);

    public static String[] toolNames;
    private int previousSlot = -1;

    public AutoTools() {
        super("AutoTools", Category.WORLD);
    }

    @EventTarget
    public void onUpdateHeldItem(UpdateHeldItemEvent updateHeldItemEvent) {
        if (mc.player == null) return;

        // 静默模式下的视觉欺骗（保持渲染原来的物品）
        if (this.switchBack.getValue() && this.silent.getValue()
                && updateHeldItemEvent.getHand() == InteractionHand.MAIN_HAND && this.previousSlot != -1) {
            updateHeldItemEvent.setItemStack(mc.player.getInventory().getItem(this.previousSlot));
        }
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (mc.player == null || mc.level == null) return;

        if (motionEvent.isPre()) {
            boolean isManuallyActing = mc.options.keyAttack.isDown() && mc.hitResult != null;
            boolean isAuraAttacking = KillAura.INSTANCE != null
                    && KillAura.INSTANCE.isEnabled()
                    && KillAura.target != null;

            int bestSlot = -1;

            // 🌟 优先级 1：玩家正在手动指着方块挖矿（最高优先级，覆盖 Aura）
            if (isManuallyActing && mc.hitResult.getType() == HitResult.Type.BLOCK) {
                if (this.checkSword.getValue() && mc.player.getMainHandItem().getItem() instanceof SwordItem) {
                    // 防误触保护：拿剑时不切工具（若想随时随地切，请在界面关掉 Check Sword）
                } else {
                    BlockPos pos = ((BlockHitResult) mc.hitResult).getBlockPos();
                    bestSlot = this.getBestTool(pos);
                }
            }
            // 🌟 优先级 2：KillAura 正在自动打人
            else if (isAuraAttacking && this.autoWeapon.getValue()) {
                bestSlot = this.getBestWeapon();
            }
            // 🌟 优先级 3：玩家手动指着实体打人
            else if (isManuallyActing && mc.hitResult.getType() == HitResult.Type.ENTITY && this.autoWeapon.getValue()) {
                bestSlot = this.getBestWeapon();
            }

            // 执行核心切换与回弹逻辑
            if (bestSlot != -1) {
                if (bestSlot != mc.player.getInventory().selected) {
                    if (this.previousSlot == -1) {
                        this.previousSlot = mc.player.getInventory().selected;
                    }
                    mc.player.getInventory().selected = bestSlot;
                }
            } else {
                // 如果没有触发任何自动切槽需求（没在挖矿、也没在打人），执行回弹
                if (this.switchBack.getValue() && this.previousSlot != -1) {
                    mc.player.getInventory().selected = this.previousSlot;
                    this.previousSlot = -1;
                }
            }
        }
    }

    /**
     * 计算并获取最适合挖当前方块的工具
     */
    private int getBestTool(BlockPos blockPos) {
        BlockState blockState = mc.level.getBlockState(blockPos);
        Block block = blockState.getBlock();

        if (blockState.isAir()) return -1;

        int bestSlot = -1;
        float bestSpeed = 1.0f;

        for (int i = 0; i < 9; ++i) {
            ItemStack itemStack = mc.player.getInventory().getItem(i);
            if (itemStack.isEmpty()) continue;

            // 除了挖蜘蛛网，其他方块不考虑用剑挖
            if (itemStack.getItem() instanceof SwordItem && !(block instanceof WebBlock)) continue;

            // 🌟 修复：使用更符合 1.20 特性的底层 API 获取破坏速度
            float destroySpeed = itemStack.getDestroySpeed(blockState);

            if (destroySpeed > 1.0f) {
                int efficiencyLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, itemStack);
                if (efficiencyLevel > 0) {
                    destroySpeed += (float) (efficiencyLevel * efficiencyLevel + 1);
                }
            }

            if (destroySpeed > bestSpeed) {
                bestSlot = i;
                bestSpeed = destroySpeed;
            }
        }
        return bestSlot;
    }

    /**
     * 计算并获取快捷栏中伤害最高的武器（剑或斧头）
     */
    private int getBestWeapon() {
        int bestSlot = -1;
        float bestDamage = 1.0f; // 空手的基础伤害是 1.0f

        for (int i = 0; i < 9; ++i) {
            ItemStack itemStack = mc.player.getInventory().getItem(i);
            if (itemStack.isEmpty()) continue;

            float damage = 1.0f;

            var modifiers = itemStack.getAttributeModifiers(EquipmentSlot.MAINHAND);
            if (modifiers.containsKey(Attributes.ATTACK_DAMAGE)) {
                for (var modifier : modifiers.get(Attributes.ATTACK_DAMAGE)) {
                    damage += (float) modifier.getAmount();
                }
            }

            int sharpnessLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, itemStack);
            if (sharpnessLevel > 0) {
                damage += 0.5f * sharpnessLevel + 0.5f;
            }

            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = i;
            }
        }
        return bestSlot;
    }
}