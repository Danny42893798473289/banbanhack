package shit.zen.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import shit.zen.ZenClient;
import shit.zen.gui.legacy.CategoryPanel;
import shit.zen.gui.legacy.ModuleButton;
import shit.zen.modules.Category;

public class OldClickGui
extends Screen {
    private final List<CategoryPanel> categoryPanels = new ArrayList<>();
    private static final String TITLE = "Click GUI";
    // 鼠标在方框区域外滚动时，整体上下平移的像素速度（每个滚动单位）
    private static final int PAN_SPEED = 24;

    public OldClickGui() {
        super(Component.nullToEmpty(TITLE));
        int x = 20;
        for (Category category : Category.values()) {
            this.categoryPanels.add(new CategoryPanel(x, 20, 140, 20, category));
            x += 160;
        }
    }

    public void init() {
        super.init();
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            for (ModuleButton moduleButton : categoryPanel.moduleButtons) {
                moduleButton.reset();
            }
        }
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            categoryPanel.render(guiGraphics, mouseX, mouseY, partialTicks);
            categoryPanel.mouseDragged(mouseX, mouseY);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            categoryPanel.mouseReleased(mouseX, mouseY, button);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            categoryPanel.mouseClicked(mouseX, mouseY, button);
        }
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        boolean overAnyPanel = false;
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            if (categoryPanel.isMouseOverPanel(mouseX, mouseY)) {
                overAnyPanel = true;
                categoryPanel.mouseScrolled(mouseX, mouseY, scrollDelta);
            }
        }
        if (!overAnyPanel) {
            // 鼠标不在任何方框范围内时，整体平移所有方框（上下滑动）
            int panAmount = (int) Math.round(scrollDelta * (double) PAN_SPEED);
            for (CategoryPanel categoryPanel : this.categoryPanels) {
                categoryPanel.y += panAmount;
            }
        }
        return true;
    }

    public void onClose() {
        if (ZenClient.isReady()) {
            ZenClient.instance.getConfigManager().saveAll();
        }
        super.onClose();
    }

    static {
        OldClickGui oldClickGui = new OldClickGui();
    }
}