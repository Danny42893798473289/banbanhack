package shit.zen.command.impl;

import net.minecraft.network.chat.Component;
import shit.zen.command.Command;
import shit.zen.modules.impl.misc.IRC;
import shit.zen.utils.misc.ChatUtil;

/**
 * ".irc <文本>" —— 把消息发给所有当前在线、开启了 IRC 模块的用户，不分服务器。
 */
public class IrcCommand extends Command {

    public IrcCommand() {
        super("irc", new String[]{});
    }

    @Override
    public void onCommand(String[] args) {
        if (args.length == 0) {
            ChatUtil.addMessage(Component.literal("§7用法: .irc <消息内容>"));
            return;
        }
        if (IRC.INSTANCE == null || !IRC.INSTANCE.isEnabled()) {
            ChatUtil.addMessage(Component.literal("§c请先用 .t IRC 开启 IRC 模块。"));
            return;
        }
        String text = String.join(" ", args);
        IRC.INSTANCE.sendChat(text);
    }

    @Override
    public String[] onTab(String[] args) {
        return new String[0];
    }
}
