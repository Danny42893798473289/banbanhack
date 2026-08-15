package shit.zen.modules.impl.world;

import shit.zen.modules.Category;
import shit.zen.modules.Module;

/**
 * 开启后，右边积分板（计分板侧边栏）里如果出现"布吉岛"这三个字，会被替换成"吉吉吉"。
 *
 * 原理：大多数服务器自定义计分板每一行文字，是用"假队伍的前缀/后缀"
 * （PlayerTeam 的 playerPrefix / playerSuffix）拼出来的，这里拦截的就是这两个
 * 前缀/后缀被读取、准备绘制到屏幕上的那一刻：检测到文本里包含"布吉岛"，就把
 * 整段文字替换成"吉吉吉"（这样做会丢失这段文字原本自带的颜色格式，替换后的部分
 * 统一变成默认白色，这是纯文本替换本身决定的取舍，没法在保留复杂颜色格式的同时
 * 做子串替换）。
 *
 * 如果你们服务器的计分板这几个字实际上不是通过队伍前缀/后缀实现的（而是别的机制，
 * 比如新版本一行一个独立的 displayName），这个方法可能拦截不到，把服务器计分板
 * 具体是怎么显示这几个字的（有没有队伍、有没有颜色渐变之类）告诉我，我再调整。
 */
public class BoardReplace extends Module {

    public static BoardReplace INSTANCE;

    public static final String TARGET_TEXT = "布吉岛";
    public static final String REPLACEMENT_TEXT = "吉吉岛";

    public BoardReplace() {
        super("BoardReplace", Category.WORLD);
        INSTANCE = this;
    }
}
