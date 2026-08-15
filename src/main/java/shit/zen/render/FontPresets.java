package shit.zen.render;

import shit.zen.render.FontRenderer;
import shit.zen.render.Fonts;

public final class FontPresets {
    public static FontRenderer pingfang(float size) {
        return Fonts.getRenderer("pingfang_sc_regular.ttf", size);
    }

    public static FontRenderer productSans(float size) {
        return Fonts.getRenderer("product_sans_regular.ttf", size);
    }

    public static FontRenderer astaSans(float size) {
        return Fonts.getRenderer("AstaSans-Medium.ttf", size);
    }

    public static FontRenderer poppinsRegular(float size) {
        return Fonts.getRenderer("Poppins-Regular.ttf", size);
    }

    public static FontRenderer poppinsMedium(float size) {
        return Fonts.getRenderer("Poppins-Medium.ttf", size);
    }

    public static FontRenderer poppinsBold(float size) {
        return Fonts.getRenderer("Poppins-Bold.ttf", size);
    }

    public static FontRenderer dannyhackIcon(float size) {
        return Fonts.getRenderer("dannyhack-icon.ttf", size);
    }

    public static FontRenderer museoSans(float size) {
        return Fonts.getRenderer("MuseoSansCyrl-900.ttf", size);
    }

    public static FontRenderer materialIcons(float size) {
        return Fonts.getRenderer("MaterialIcons-Regular.ttf", size);
    }

    public static FontRenderer axiformaBold(float size) {
        return Fonts.getRenderer("axiforma_bold.ttf", size);
    }

    public static FontRenderer axiformaRegular(float size) {
        return Fonts.getRenderer("axiforma_regular.ttf", size);
    }

    public static FontRenderer axiformaExtraBold(float size) {
        return Fonts.getRenderer("axiforma_extrabold.ttf", size);
    }

    // 正确传入参数 v 并加载对应的字体文件
    public static FontRenderer dannyhack(float v) {
        return Fonts.getRenderer("dannyhack.ttf", v);
    }
}