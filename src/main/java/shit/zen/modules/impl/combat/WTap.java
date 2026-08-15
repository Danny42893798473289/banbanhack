package shit.zen.modules.impl.combat;

import shit.zen.event.EventTarget;
import shit.zen.event.impl.PreMotionEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;

public class WTap extends Module {

    public static WTap INSTANCE;

    private int pTicks = 0;
    private boolean needReset = false;

    public WTap() {
        super("WTap", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.needReset = false;
        this.pTicks = 0;
        super.onEnable();
    }

    public void onAttackTarget() {
        if (!this.isEnabled() || mc.player == null) return;

        this.needReset = true;
        this.pTicks = 0;
    }

    // ======= 核心添加：将 needReset 变量公开给外部模块（如 Sprint）查看 =======
    public boolean isNeedReset() {
        return this.needReset;
    }
    // ====================================================================

    @EventTarget
    public void onPreMotion(PreMotionEvent event) {
        if (mc.player == null) return; // 只拦截 null

        if (this.needReset) {
            this.pTicks++;

            switch (this.pTicks) {
                case 1 -> {
                    // 只有在真的处于冲刺时，才去断冲刺
                    if (mc.player.isSprinting()) {
                        mc.player.setSprinting(false);
                    }
                }
                case 2 -> {
                    mc.player.setSprinting(true);
                    this.needReset = false;
                    this.pTicks = 0;
                }
            }
        }
    }
}