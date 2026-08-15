package shit.zen.hud;

import lombok.Getter;
import lombok.Setter;
import shit.zen.event.impl.GlRenderEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.utils.render.RenderUtil;

public abstract class HudElement
        extends Module {

    // 💡 移除这里的 Lombok 注解，由下方手动接管，实现高阶自适应缩放
    protected float x;
    protected float y;

    @Getter @Setter protected float width;
    @Getter @Setter protected float height;
    @Getter @Setter private boolean dragging = false;
    @Getter @Setter private float dragOffsetX;
    @Getter @Setter private float dragOffsetY;

    // 🌟 新增：动态分辨率缓存基准线
    private int lastScreenWidth = -1;
    private int lastScreenHeight = -1;

    public HudElement(String string) {
        super(string, Category.RENDER);
    }

    public abstract void onRender2D(Render2DEvent var1, float var2, float var3);

    public abstract void onGlRender(GlRenderEvent var1, float var2, float var3);

    public abstract void onSettings();

    /**
     * 🌟 核心：三区动态分辨率缩放检查
     * 确保靠右、靠下、居中的组件在窗口大小剧烈变化时，能完美重新解算自己的绝对坐标
     */
    private void checkResolution() {
        if (mc.getWindow() == null) return;
        int currentWidth = mc.getWindow().getGuiScaledWidth();
        int currentHeight = mc.getWindow().getGuiScaledHeight();

        // 首次运行，初始化当前的屏幕宽高基准
        if (lastScreenWidth == -1 || lastScreenHeight == -1) {
            lastScreenWidth = currentWidth;
            lastScreenHeight = currentHeight;
            return;
        }

        // 当检测到屏幕分辨率发生了改变
        if (currentWidth != lastScreenWidth || currentHeight != lastScreenHeight) {

            // 1. X 轴自适应重算
            if (this.x > lastScreenWidth * 0.66f) {
                // 原本靠右侧 (66% ~ 100%)：保持与右边缘的像素距离不变
                float padRight = lastScreenWidth - this.x;
                this.x = currentWidth - padRight;
            } else if (this.x > lastScreenWidth * 0.33f) {
                // 原本居中 (33% ~ 66%)：按照比例百分比缩放
                float pctX = this.x / (float) lastScreenWidth;
                this.x = currentWidth * pctX;
            } // 原本靠左侧 (0% ~ 33%) 的组件不作处理，保持原样即可

            // 2. Y 轴自适应重算
            if (this.y > lastScreenHeight * 0.66f) {
                // 原本靠下方：保持与底部边缘的绝对像素距离不变
                float padBottom = lastScreenHeight - this.y;
                this.y = currentHeight - padBottom;
            } else if (this.y > lastScreenHeight * 0.33f) {
                // 原本居中：按照比例缩放
                float pctY = this.y / (float) lastScreenHeight;
                this.y = currentHeight * pctY;
            } // 原本靠上方不作处理

            // 刷新缓存，将当前分辨率设定为新基准
            lastScreenWidth = currentWidth;
            lastScreenHeight = currentHeight;
        }
    }

    // 🌟 手动重写 Getter：确保外部无论是在 Render 还是 ClickGui 中调用 get 方法，都能触发分辨率校准
    public float getX() {
        this.checkResolution();
        return this.x;
    }

    public float getY() {
        this.checkResolution();
        return this.y;
    }

    // 🌟 手动重写 Setter：确保 ConfigManager 从本地文件读取保存的坐标时，能以当前窗口大小作为安全基准线
    public void setX(float x) {
        this.x = x;
        if (mc.getWindow() != null) {
            this.lastScreenWidth = mc.getWindow().getGuiScaledWidth();
        }
    }

    public void setY(float y) {
        this.y = y;
        if (mc.getWindow() != null) {
            this.lastScreenHeight = mc.getWindow().getGuiScaledHeight();
        }
    }

    public boolean mousePressed(int mouseX, int mouseY, int button) {
        if (this.isHovered(mouseX, mouseY) && button == 0) {
            this.dragging = true;
            this.dragOffsetX = (float)mouseX - this.getX();
            this.dragOffsetY = (float)mouseY - this.getY();
            return true;
        }
        return false;
    }

    public void mouseDragged(int mouseX, int mouseY) {
        // 🌟 拖拽时坐标基于当前分辨率实时改变，必须死锁当前分辨率基准，防止拖拽中触发自适应错位
        if (mc.getWindow() != null) {
            this.lastScreenWidth = mc.getWindow().getGuiScaledWidth();
            this.lastScreenHeight = mc.getWindow().getGuiScaledHeight();
        }
        this.x = (float)mouseX - this.dragOffsetX;
        this.y = (float)mouseY - this.dragOffsetY;
    }

    public boolean isHovered(int mouseX, int mouseY) {
        // 修改为调用 getX() 和 getY()
        return RenderUtil.isHovered(this.getX(), this.getY(), this.width, this.height, mouseX, mouseY);
    }

    public void stopDragging() {
        this.dragging = false;
    }
}