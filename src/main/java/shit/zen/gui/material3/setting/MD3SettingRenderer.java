package shit.zen.gui.material3.setting;

import shit.zen.settings.Setting;
import net.minecraft.client.gui.GuiGraphics;

public interface MD3SettingRenderer {
    int render(GuiGraphics gg, Setting<?> s, int x, int y, int w, int mx, int my, float alpha, float accent);

    boolean onClick(Setting<?> s, int x, int y, int w, int mx, int my, int btn, float scale);

    boolean supports(Setting<?> s);

    int getHeight(Setting<?> s);

    void onMouseRelease(double mx, double my, int btn);

    void onMouseDrag(Setting<?> s, double mx, double my, int btn);
}
