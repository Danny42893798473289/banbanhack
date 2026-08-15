package shit.zen.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import shit.zen.utils.render.TextureUtil;

/**
 * 拦截 DisconnectedScreen (玩家连接超时时出现的界面) 的 render 方法，
 * 将背景改成 ~/.zen/configs/gb.png，拉伸填充整个屏幕。
 *
 * 实现原理：在 render 方法最开始执行时，先用我们的自定义背景图片填充屏幕，
 * 然后让原来的 render 逻辑继续执行（按钮、文字等会正常覆盖在背景上方）。
 */
@Patch(DisconnectedScreen.class)
public class DisconnectedScreenPatch {

    private static ResourceLocation customBackgroundTexture;

    @Inject(
            method = "render",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRender(DisconnectedScreen screen, GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo callbackInfo) {
        // 加载自定义背景图片
        Minecraft minecraft = Minecraft.getInstance();
        DynamicTexture bgTexture = TextureUtil.loadTexture("gb.png");
        if (bgTexture == null) {
            return;
        }

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        // 首次加载时注册纹理位置
        if (shit.zen.patch.DisconnectedScreenPatch.customBackgroundTexture == null) {
            TextureManager textureManager = screen.getMinecraft().getTextureManager();
            shit.zen.patch.DisconnectedScreenPatch.customBackgroundTexture = textureManager.register("zen_disconnect_bg", bgTexture);
        }

        // 绑定纹理并用 blit 拉伸填充整个屏幕
        RenderSystem.setShaderTexture(0, shit.zen.patch.DisconnectedScreenPatch.customBackgroundTexture);
        // blit(ResourceLocation texture, x, y, width, height, uOffset, vOffset, textureWidth, textureHeight, imageWidth, imageHeight)
        // 这里的参数意思是：从纹理(0,0)处取整个纹理，拉伸到屏幕(0,0)到(width,height)
        guiGraphics.blit(
                shit.zen.patch.DisconnectedScreenPatch.customBackgroundTexture,
                0, 0,
                screenWidth, screenHeight,
                0.0f, 0.0f,
                screenWidth, screenHeight,
                screenWidth, screenHeight
        );
    }
}
