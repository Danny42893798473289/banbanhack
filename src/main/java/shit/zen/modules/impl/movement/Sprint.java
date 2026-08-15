package shit.zen.modules.impl.movement;

import shit.zen.event.EventTarget;
import shit.zen.event.impl.RotationEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.modules.impl.player.InventoryManager;
// 导入 WTap 模块
import shit.zen.modules.impl.combat.WTap;

public class Sprint extends Module {

    public Sprint() {
        super("Sprint", Category.MOVEMENT);
        this.setEnabled(true);
    }

    @EventTarget
    public void onRotation(RotationEvent event) {
        if (mc.player == null) return;

        // 1. 如果箱子内移动正在执行动作，松开疾跑
        if (GuiMove.INSTANCE.isEnabled() && InventoryManager.isPerformingAction) {
            mc.options.keySprint.setDown(false);
            return;
        }

        // ======= 2. 修复内鬼冲突核心 =======
        // 如果 WTap 模块开启了，并且它正在执行断冲刺重置 (needReset 为 true)
        // 自动疾跑在这个瞬间必须强制松开，否则会顶掉 WTap 的大击退判定
        if (WTap.INSTANCE != null && WTap.INSTANCE.isEnabled() && WTap.INSTANCE.isNeedReset()) {
            mc.options.keySprint.setDown(false);
            return;
        }
        // ===================================

        // 正常情况下，死锁疾跑按键
        mc.options.keySprint.setDown(true);
    }

    @Override
    public void onDisable() {
        if (mc.player != null && mc.options != null) {
            mc.options.keySprint.setDown(false);
        }
        super.onDisable();
    }
}