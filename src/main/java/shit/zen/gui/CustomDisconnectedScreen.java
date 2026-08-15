package shit.zen.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class CustomDisconnectedScreen extends Screen {
    private final Screen parent;
    private final Component title;
    private final Component reason;

    public CustomDisconnectedScreen(Screen parent, Component title, Component reason) {
        super(title);
        this.parent = parent;
        this.title = title;
        this.reason = reason;
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(this.parent);
            }
        }).bounds(this.width / 2 - 100, this.height - 40, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int titleY = 70;
        guiGraphics.drawCenteredString(this.font, this.title.getString(), this.width / 2, titleY, 0xFFFFFF);
        if (this.reason != null) {
            guiGraphics.drawCenteredString(this.font, this.reason.getString(), this.width / 2, titleY + 24, 0xAAAAAA);
        }
    }
}
