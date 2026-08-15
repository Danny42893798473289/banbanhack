package shit.zen.modules.impl.misc;

import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.ModeSetting;

public class NotiSound extends Module {
    public static NotiSound INSTANCE;

    // 🌟 音效模式切换设置：这里填入你想支持的音效名字，比如 "Skeet", "Neverlose", "Custom" 等
    private final ModeSetting soundModeSetting = new ModeSetting(
            "Sound Mode", 
            "chenxx", "button", "experience","none"
    ).withDefault("button");

    public NotiSound() {
        // 如果你的 Category 里没有 CLIENT，可以改成 Category.RENDER
        super("NotiSound", Category.RENDER);
        INSTANCE = this;
        
        // 默认让这个音效控制模块保持开启状态
        this.setEnabled(true); 
    }

    /**
     * 🌟 公开给外部调用的方法
     * 用于获取当前玩家在 ClickGui 里选择了哪种音效模式
     * * @return 当前选中的音效模式名称字符串
     */
    public String getSoundMode() {
        // 如果模块被关闭了，可以返回一个默认值或者 "None" 代表不播放声音
        if (!this.isEnabled()) {
            return "None";
        }
        return this.soundModeSetting.getValue();
    }

    @Override
    protected void onEnable() {
        super.onEnable();
    }

    @Override
    protected void onDisable() {
        super.onDisable();
    }
}