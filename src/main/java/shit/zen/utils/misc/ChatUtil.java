package shit.zen.utils.misc;

import lombok.Generated;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import shit.zen.ClientBase;
import shit.zen.modules.impl.misc.NotiSound;
import shit.zen.modules.impl.world.Debugger;

public final class ChatUtil
extends ClientBase {
    public static void addMessage(Component component) {
        ChatComponent chatComponent = mc.gui.getChat();
        chatComponent.addMessage(component);
    }

    public static void print(String message) {
        if (Debugger.INSTANCE != null) {

            if(Debugger.debugqwq.getValue()){
                ChatUtil.print(true, message);
            }
        }

    }

    public static void print(boolean withPrefix, String message) {
        if (Debugger.INSTANCE != null) {

            if(Debugger.debugqwq.getValue()){
                ChatUtil.addMessage(Component.nullToEmpty((withPrefix ? "§7[§b§7] " : "") + message));
            }
        }

    }

    @Generated
    private ChatUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}