package shit.zen.modules.impl.render;

import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.ReceivePacketEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.ModeSetting;

/**
 * 本地修改游戏时间（白天 / 中午 / 夜晚），纯客户端视觉效果，不影响服务器。
 *
 * v2 修复：之前的版本只在每个客户端 tick 里"把时间强制改回去"，但服务器大概每秒都会
 * 主动发一次 ClientboundSetTimePacket 来同步世界时间，这个包和我们的 tick 逻辑是各自独立
 * 触发的，中间有时间差——服务器包一到，会先把真实时间写进 level，等到下一次我们的 tick
 * 逻辑执行才纠正回来，这中间刚好会渲染出一两帧"真实时间"，看起来就是每隔几秒闪烁一下。
 *
 * 现在直接在收到这个包的时候把它取消掉（event.setCancelled(true)），根本不让真实时间
 * 有机会写进 level，从源头上解决闪烁，而不是写进去以后再纠正。tick 里的强制写入依然保留，
 * 作为兜底（比如模块刚开启的那一刻、或者其他任何来源改了时间时都能纠正回来）。
 */
public class FakeTime extends Module {

    public final ModeSetting mode = new ModeSetting("Time", "Day", "Noon", "Night").withDefault("Day");

    private static final long TICKS_PER_DAY = 24000L;
    private static final long DAY_TIME = 1000L;    // 白天（刚日出不久，亮度已经拉满）
    private static final long NOON_TIME = 6000L;   // 正午，太阳在正上方
    private static final long NIGHT_TIME = 14000L; // 夜晚（天已经全黑，怪物会正常生成）

    public FakeTime() {
        super("FakeTime", Category.RENDER);
    }

    private long getTargetTimeOfDay() {
        if (this.mode.is("Noon")) {
            return NOON_TIME;
        }
        if (this.mode.is("Night")) {
            return NIGHT_TIME;
        }
        return DAY_TIME;
    }

    /**
     * 关键修复：服务器同步时间的包，直接拦截掉，不让它有机会覆盖我们的假时间。
     */
    @EventTarget
    public void onReceivePacket(ReceivePacketEvent event) {
        if (event.getPacket() instanceof ClientboundSetTimePacket) {
            event.setCancelled(true);
        }
    }

    /**
     * 兜底：每 tick 强制写一次，覆盖任何非"服务器同步包"来源的时间变化（比如床上睡觉跳过夜晚等）。
     */
    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.level == null) {
            return;
        }

        long targetTimeOfDay = this.getTargetTimeOfDay();
        long currentDayTime = mc.level.getDayTime();
        long currentDayIndex = currentDayTime / TICKS_PER_DAY;
        long fixedDayTime = currentDayIndex * TICKS_PER_DAY + targetTimeOfDay;

        if (currentDayTime != fixedDayTime) {
            mc.level.setDayTime(fixedDayTime);
        }
    }
}
