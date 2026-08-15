package shit.zen.modules.impl.player;

import shit.zen.event.EventTarget;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;

/**
 * 玩家 Y 坐标 <= 30 时自动发送 /hub 指令给服务器（比如掉进虚空/落空的时候自救）。
 *
 * 只在"从高于 30 掉到 30 及以下"这个瞬间触发一次（边沿触发），不会每 tick 都发；
 * 想再次触发，必须先回到高于 30 的地方，再掉下来一次才行。
 */
public class VoidHub extends Module {

    private static final double THRESHOLD_Y = 30.0;

    // 记录上一次检测时是否已经在阈值以下，用来判断"是不是刚跨过阈值的那一瞬间"
    private boolean wasBelowThreshold = false;

    public VoidHub() {
        super("VoidHub", Category.WORLD);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        boolean isBelowThreshold = mc.player.getY() <= THRESHOLD_Y;

        // 只有"上一次还在阈值以上，这一次已经在阈值及以下"才算真正触发一次
        if (isBelowThreshold && !this.wasBelowThreshold) {
            mc.player.connection.sendCommand("hub");
        }

        this.wasBelowThreshold = isBelowThreshold;
    }

    @Override
    public void onEnable() {
        // 开启的时候按当前实际位置初始化状态，避免"开启瞬间人已经在30以下"导致误判成刚跨过阈值
        this.wasBelowThreshold = mc.player != null && mc.player.getY() <= THRESHOLD_Y;
    }
}
