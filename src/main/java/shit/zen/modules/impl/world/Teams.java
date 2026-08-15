package shit.zen.modules.impl.world;

import java.util.Objects;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.ModeSetting;

public class Teams extends Module {
    public static Teams instance;
    public static ModeSetting mode;

    public Teams() {
        super("Teams", Category.WORLD);
        instance = this;
    }

    public static boolean isSameTeam(Entity entity) {
        // 🛠️ 关键修复 1：安全熔断。防止游戏刚启动或未进房间时由于本地玩家实例为空导致后续崩溃
        if (mc.player == null || instance == null || !instance.isEnabled()) {
            return false;
        }

        // 只有当目标是玩家时才进行队伍判定
        if (entity instanceof Player) {
            // 模式 1: 按队伍名字颜色判定 (Color)
            if (mode.is("Color")) {
                Integer n = entity.getTeamColor();
                Integer n2 = mc.player.getTeamColor();

                // 🛠️ 关键修复 2：包装类 Integer 进行 equals 比较前必须进行非空断言，防止 null.equals 导致闪退
                return n != null && n2 != null && n.equals(n2);
            }

            // 模式 2: 按计分板真实队伍名判定 (Scoreboard)
            String string = Teams.getTeam(entity);
            String string2 = Teams.getTeam(mc.player);

            // 🛠️ 关键修复 3：修正“无队伍散人”误伤判定。
            // 当两个玩家都没队伍时（string 和 string2 均为 null），原本会返回 true。
            // 必须限定双方的队伍名都不能为 null，才能确认为真正的“同一个队伍”。
            return string != null && string2 != null && Objects.equals(string, string2);
        }
        return false;
    }

    public static String getTeam(Entity entity) {
        // 🛠️ 关键修复 4：防止跨服、进退房间或断开连接的瞬间 mc.getConnection() 产生空指针
        if (mc.getConnection() == null || entity == null) {
            return null;
        }

        PlayerInfo playerInfo = mc.getConnection().getPlayerInfo(entity.getUUID());
        if (playerInfo == null) {
            return null;
        }

        if (playerInfo.getTeam() != null) {
            return playerInfo.getTeam().getName();
        }
        return null;
    }

    static {
        mode = new ModeSetting("Mode", "Color", "Scoreboard").withDefault("Scoreboard");
    }
}