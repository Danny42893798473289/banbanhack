package shit.zen.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.util.thread.BlockableEventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import shit.zen.ZenClient;
import shit.zen.gui.CustomDisconnectedScreen;
import shit.zen.network.PacketHandlerUtil;

@Patch(PacketUtils.class)
public class PacketUtilsPatch {
    private static final Logger LOGGER = LoggerFactory.getLogger(PacketUtils.class);

    @Inject(
            method = "ensureRunningOnSameThread",
            desc = "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            at = @At(At.Type.HEAD)
    )
    public static <T extends PacketListener> void onEnsureRunningOnSameThread(Packet<T> packet, T listener, BlockableEventLoop<?> loop, CallbackInfo callbackInfo) throws RunningOnDifferentThreadException {
        if (!ZenClient.isReady()) {
            return;
        }
        if (packet instanceof ClientboundDisconnectPacket) {
            ClientboundDisconnectPacket disconnectPacket = (ClientboundDisconnectPacket) packet;
            Component reason = disconnectPacket.getReason();
            String reasonText = reason.getString().toLowerCase();
            boolean isBan = reasonText.contains("封") || reasonText.contains("ban") || reasonText.contains("kick") || reasonText.contains("踢");
            if (isBan) {
                Minecraft mc = Minecraft.getInstance();
                Screen parent = mc.screen;
                mc.setScreen(new CustomDisconnectedScreen(parent, Component.literal("Disconnected"), reason));
                callbackInfo.cancel();
                return;
            }
        }
        callbackInfo.cancel();
        PacketHandlerUtil.processPacket(LOGGER, packet, listener, loop);
    }
}
