#pragma once

#include <QColor>
#include <QString>

namespace loader::theme {

enum class ThemePreset {
    Dark,
    Light,
};

constexpr ThemePreset kActiveTheme = ThemePreset::Dark;

struct Palette {
    QColor windowTop;
    QColor windowBottom;
    QColor windowBorder;

    QColor bodyText;
    QColor titleText;
    QColor hintText;
    QColor statusText;
    QColor statusBackground;
    QColor statusBorder;

    QColor titleBarTop;
    QColor titleBarBottom;
    QColor titleBarText;
    QColor titleBarMuted;
    QColor titleBarButtonHover;
    QColor titleBarButtonPressed;
    QColor titleBarCloseHover;
    QColor titleBarClosePressed;

    QColor instanceRowBaseTop;
    QColor instanceRowBaseBottom;
    QColor instanceRowHoverTop;
    QColor instanceRowHoverBottom;
    QColor instanceRowStripe;
    QColor instanceRowOutline;
    QColor instanceRowPidText;
    QColor instanceRowTitleText;

    QColor buttonTop;
    QColor buttonBottom;
    QColor buttonHoverTop;
    QColor buttonHoverBottom;
    QColor buttonPressedTop;
    QColor buttonPressedBottom;
    QColor buttonDisabledBg;
    QColor buttonDisabledText;
    QColor buttonDisabledBorder;

    QColor scrollBarTrack;
    QColor scrollBarHandle;
    QColor scrollBarHandleHover;
    QColor emptyText;

    QColor overlayTop;
    QColor overlayBottom;
    QColor overlayGlow;
    QColor overlayRing;
    QColor overlayAccent;
    QColor overlayAccentAlt;
    QColor overlayText;
    QColor overlaySubtext;
    QColor overlayTrack;
    QColor overlayFill;
    QColor overlayBorder;

    QColor splashTop;
    QColor splashBottom;
    QColor splashGlow;
    QColor splashText;
    QColor splashShadow;
    QColor splashTrack;
    QColor splashTrail;
    QColor splashDot;

    QColor scanHalo;
    QColor scanCore;
    QColor success;
    QColor error;
};

inline Palette paletteFor(ThemePreset preset) {
    if (preset == ThemePreset::Light) {
        // 浅色主题保留你原有配色不变
        return {
            /* window */ QColor("#eef3fb"), QColor("#e2e9f4"), QColor(255, 255, 255, 38),
            /* body */ QColor("#1b2430"), QColor("#111827"), QColor("#64748b"), QColor("#334155"), QColor(255, 255, 255, 220), QColor("#dbe4f0"),
            /* title bar */ QColor("#f8fbff"), QColor("#e8eef8"), QColor("#1f2937"), QColor("#6b7280"), QColor(255, 255, 255, 70), QColor(255, 255, 255, 110), QColor("#ef4444"), QColor("#dc2626"),
            /* instance row */ QColor("#ffffff"), QColor("#f1f5f9"), QColor("#dbeafe"), QColor("#bfdbfe"), QColor("#4f7cf0"), QColor(15, 23, 42, 40), QColor("#64748b"), QColor("#0f172a"),
            /* buttons */ QColor("#4f7cf0"), QColor("#3f6ed8"), QColor("#5b8ff4"), QColor("#4a7ce8"), QColor("#3a5fb7"), QColor("#2e4f9a"), QColor("#e2e8f0"), QColor("#94a3b8"), QColor("#cbd5e1"),
            /* scroll */ QColor("#e5e7eb"), QColor("#c7d2fe"), QColor("#9fb3f8"), QColor("#94a3b8"),
            /* overlay */ QColor("#f8fbff"), QColor("#eef3fb"), QColor(79, 124, 240, 90), QColor(79, 124, 240, 60), QColor("#4f7cf0"), QColor("#6ea8ff"), QColor("#0f172a"), QColor("#475569"), QColor("#e2e8f0"), QColor(255, 255, 255, 90), QColor(15, 23, 42, 34),
            /* splash */ QColor("#f8fbff"), QColor("#eef3fb"), QColor(79, 124, 240, 90), QColor("#0f172a"), QColor(0, 0, 0, 75), QColor("#cbd5e1"), QColor(79, 124, 240, 210), QColor(79, 124, 240, 230),
            /* scan */ QColor(79, 124, 240, 80), QColor("#4f7cf0"), QColor("#16a34a"), QColor("#dc2626"),
        };
    }

    // ===================== 暗黑主题：暗紫色背景 + 紫光霓虹 =====================
    // 主底色：深紫科技底 #140F24 / #08050F
    return {
        /* 窗口整体背景(实色，不透明，避免全局玻璃) */
        QColor("#140F24"),        // windowTop
        QColor("#08050F"),        // windowBottom
        QColor(255, 255, 255, 22),// windowBorder 浅细边框

        /* 全局文字 */
        QColor("#F2E8FF"),        // bodyText 主文本
        QColor("#FFFFFF"),        // titleText 标题白字
        QColor("#8D84A7"),        // hintText 提示浅灰
        QColor("#E9DDFE"),        // statusText 状态栏文字
        QColor(32, 24, 51, 220),  // statusBackground 状态栏底色
        QColor("#33284C"),        // statusBorder 状态栏边框

        /* 标题栏 */
        QColor("#1A1230"),        // titleBarTop
        QColor("#0F0A18"),        // titleBarBottom
        QColor("#F4EBFF"),        // titleBarText
        QColor("#A78BFA"),        // titleBarMuted 辅助紫色
        QColor(88, 28, 135, 35),  // titleBarButtonHover 悬浮半透紫
        QColor(88, 28, 135, 50),  // titleBarButtonPressed 按压加深
        QColor("#F43F5E"),        // titleBarCloseHover 关闭按钮悬浮红
        QColor("#DC2626"),        // titleBarClosePressed 关闭按压红

        /* 列表行项目 */
        QColor("#191328"),        // instanceRowBaseTop
        QColor("#11101C"),        // instanceRowBaseBottom
        QColor(109, 40, 217, 30), // instanceRowHoverTop 悬浮紫蓝半透
        QColor(168, 85, 247, 24), // instanceRowHoverBottom 悬浮紫半透
        QColor(139, 92, 246, 45), // instanceRowStripe 隔行霓虹紫
        QColor(255, 255, 255, 18),// instanceRowOutline 行轮廓
        QColor("#B8A6F0"),        // instanceRowPidText
        QColor("#F2E8FF"),        // instanceRowTitleText

        // ============= 核心：暗紫渐变 液态玻璃按钮（上下渐变 + 半透质感） =============
        QColor(124, 58, 237, 160), // buttonTop 常态上半层：紫色半透玻璃
        QColor(91, 33, 182, 140),  // buttonBottom 常态下半层：深紫半透玻璃
        QColor(139, 92, 246, 190), // buttonHoverTop 悬浮上：提亮紫色
        QColor(109, 40, 217, 170), // buttonHoverBottom 悬浮下：提亮深紫
        QColor(91, 33, 182, 170),  // buttonPressedTop 按压上：加深紫色
        QColor(76, 29, 149, 160),  // buttonPressedBottom 按压下：加深深紫
        QColor("#231A34"),        // buttonDisabledBg 禁用背景
        QColor("#7C7590"),        // buttonDisabledText 禁用文字
        QColor("#33284C"),        // buttonDisabledBorder 禁用边框

        /* 滚动条 配套暗紫色 */
        QColor("#100B17"),        // scrollBarTrack 滑道底色
        QColor(109, 40, 217, 140),// scrollBarHandle 滑块(半透紫)
        QColor(139, 92, 246, 180),// scrollBarHandleHover 滑块悬浮(亮紫)
        QColor("#7C7590"),        // emptyText 空数据文字

        /* 弹窗/遮罩层 暗紫光晕 */
        QColor("#171126"),        // overlayTop
        QColor("#0D0A13"),        // overlayBottom
        QColor(124, 58, 237, 100),// overlayGlow 紫光晕
        QColor(167, 139, 250, 80), // overlayRing 光圈紫蓝
        QColor("#8B5CF6"),        // overlayAccent 主强调紫
        QColor("#C084FC"),        // overlayAccentAlt 次强调淡紫
        QColor("#F5EFFF"),        // overlayText
        QColor("#9F8FBE"),        // overlaySubtext
        QColor(255, 255, 255, 24),// overlayTrack
        QColor(255, 255, 255, 24),// overlayFill
        QColor(255, 255, 255, 28),// overlayBorder

        /* 启动页/加载页 */
        QColor("#120E1E"),        // splashTop
        QColor("#07050D"),        // splashBottom
        QColor(124, 58, 237, 120),// splashGlow 紫光晕
        QColor("#F7EFFF"),        // splashText
        QColor(0, 0, 0, 90),      // splashShadow
        QColor(255, 255, 255, 24),// splashTrack
        QColor(168, 85, 247, 220),// splashTrail 紫轨迹
        QColor(192, 132, 252, 230),// splashDot 淡紫光点

        /* 扫描/特效光晕 */
        QColor(139, 92, 246, 80), // scanHalo 扫描外圈光晕
        QColor("#A78BFA"),        // scanCore 扫描中心
        QColor("#34D399"),        // success 成功绿
        QColor("#F87171"),        // error 错误红
    };
}

inline const Palette& currentPalette() {
    static const Palette palette = paletteFor(kActiveTheme);
    return palette;
}

inline QString hex(const QColor& color) {
    return color.name(QColor::HexRgb);
}

inline QString rgba(const QColor& color, int alpha) {
    QColor c(color);
    c.setAlpha(alpha);
    return QStringLiteral("rgba(%1, %2, %3, %4)")
        .arg(c.red())
        .arg(c.green())
        .arg(c.blue())
        .arg(c.alpha());
}

} // namespace loader::theme
