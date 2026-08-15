package shit.zen.gui;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import shit.zen.ZenClient;
import shit.zen.gui.material3.MD3Theme;
import shit.zen.modules.impl.misc.MusicPlayer;
import shit.zen.modules.impl.misc.music.AudioPlayer;
import shit.zen.modules.impl.misc.music.LyricLine;
import shit.zen.modules.impl.misc.music.NeteaseApi;
import shit.zen.modules.impl.misc.music.SongInfo;
import shit.zen.modules.impl.render.LyricsModule;
import shit.zen.render.DrawContext;
import shit.zen.render.FontPresets;
import shit.zen.render.FontRenderer;
import shit.zen.render.GlHelper;
import shit.zen.render.Paint;
import shit.zen.render.Rectangle;
import shit.zen.render.Renderer;
import shit.zen.render.RoundedRectangle;
import shit.zen.render.Texture;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.render.ColorUtil;

public class MusicPlayerScreen extends Screen {
    // Fonts
    private static final FontRenderer TITLE_FONT    = FontPresets.pingfang(22.0f);
    private static final FontRenderer HEADING_FONT  = FontPresets.pingfang(16.0f);
    private static final FontRenderer BODY_FONT     = FontPresets.pingfang(16.0f);
    private static final FontRenderer INPUT_FONT    = FontPresets.pingfang(20.0f);
    private static final FontRenderer SMALL_FONT    = FontPresets.pingfang(14.0f);
    private static final FontRenderer SIDEBAR_TITLE_FONT = FontPresets.pingfang(20.0f);
    private static final FontRenderer SIDEBAR_FONT  = FontPresets.productSans(18.0f);

    private static final FontRenderer ICON_FONT     = FontPresets.materialIcons(18.0f);
    private static final FontRenderer ICON_FONT_LG  = FontPresets.materialIcons(24.0f);

    // Material Icons
    private static final String ICON_PREV    = "";
    private static final String ICON_PLAY    = "";
    private static final String ICON_PAUSE   = "";
    private static final String ICON_NEXT    = "";
    private static final String ICON_VOLUME  = "";
    private static final String ICON_SEARCH  = "";
    private static final String ICON_QUEUE   = "";
    private static final String ICON_INFO    = "";
    private static final String ICON_PERSON  = "";

    // Layout — M3 inspired
    private static final float SIDEBAR_W    = 160.0f;
    private static final float BOTTOM_H     = 76.0f;
    private static final float PANEL_RADIUS = 16.0f;
    private static final float PAD          = 20.0f;
    private static final float GAP          = 10.0f;

    // State
    private Page page = Page.SEARCH;
    private final List<ClickArea> clickAreas = new ArrayList<>();
    private final Map<Long, List<LyricLine>> lyricsCache = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> lyricsRequested = new ConcurrentHashMap<>();
    private final AtomicLong searchSeq = new AtomicLong();

    private String searchText = "";
    private boolean searchFocused;
    private boolean searchDirty;
    private boolean searchSelectAll;
    private long lastSearchEditMs;
    private volatile List<SongInfo> searchResults = List.of();
    private volatile boolean searching;

    private final List<SongInfo> playQueue = new ArrayList<>();
    private int queueIndex = -1;

    private float searchScroll;
    private float maxSearchScroll;
    private float queueScroll;
    private float maxQueueScroll;
    private float lyricScroll;

    private DragTarget dragTarget = DragTarget.NONE;
    private Rectangle lastProgressRect;
    private Rectangle lastVolumeRect;
    private float pendingVolume = -1.0f;

    private final SmoothAnimationTimer openAnim = new SmoothAnimationTimer();

    // Album art
    private volatile Texture albumTexture;
    private long albumSongId = -1;
    private volatile boolean albumLoading = false;
    private volatile byte[] albumBytes;
    private int albumRetryCount = 0;

    public MusicPlayerScreen() {
        super(Component.literal("Music Player"));
    }

    @Override
    public void tick() {
        // Stop playback when not in a world (title screen)
        if (Minecraft.getInstance().level == null) {
            MusicPlayer.AUDIO_PLAYER.stop();
        }
        if (this.searchDirty && this.searchFocused
                && System.currentTimeMillis() - this.lastSearchEditMs >= 350) {
            this.startSearch();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.clickAreas.clear();
        this.openAnim.animate(1.0, 0.3, Easings.EASE_OUT_QUAD);
        this.openAnim.tick();
        float openProgress = this.openAnim.getValueF();

        Renderer.render(guiGraphics, ctx -> {
            float outerW = Math.max(280.0f, Math.min(this.width - 32.0f, 720.0f));
            float outerH = Math.max(220.0f, Math.min(this.height - 32.0f, 420.0f));
            float ox = (this.width - outerW) * 0.5f;
            float oy = (this.height - outerH) * 0.5f;

            // Scrim
            ctx.drawRectXYWH(0, 0, this.width, this.height,
                    new Paint().setColor(MD3Theme.withAlpha(MD3Theme.SCRIM, openProgress)));

            // Main panel — M3 surface on surface container
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(ox, oy, outerW, outerH, PANEL_RADIUS),
                    new Paint().setColor(MD3Theme.withAlpha(MD3Theme.SURFACE_DIM, openProgress * 0.98f)));
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(ox, oy, outerW, outerH, PANEL_RADIUS),
                    new Paint().setColor(MD3Theme.withAlpha(MD3Theme.OUTLINE_VARIANT, openProgress * 0.12f)));

            float contentX = ox + SIDEBAR_W;
            float contentY = oy;
            float contentW = outerW - SIDEBAR_W;
            float contentH = outerH - BOTTOM_H;

            // Separator between sidebar and content
            ctx.drawRectXYWH(contentX, contentY + 12, 1, contentH - 24,
                    new Paint().setColor(MD3Theme.withAlpha(MD3Theme.OUTLINE_VARIANT, openProgress * 0.5f)));

            renderSidebar(ctx, ox, oy, SIDEBAR_W, contentH, mouseX, mouseY);

            switch (this.page) {
                case PLAYER -> renderPlayer(ctx, contentX, contentY, contentW, contentH, mouseX, mouseY);
                case SEARCH -> renderSearch(ctx, contentX, contentY, contentW, contentH, mouseX, mouseY);
                case QUEUE -> renderQueue(ctx, contentX, contentY, contentW, contentH, mouseX, mouseY);
                case ABOUT -> renderAbout(ctx, contentX, contentY, contentW, contentH);
            }

            renderBottomBar(ctx, ox, contentY + contentH, outerW, BOTTOM_H, mouseX, mouseY);
        });
    }

    // ───────────── Sidebar ─────────────

    private void renderSidebar(DrawContext ctx, float x, float y, float w, float h, int mx, int my) {
        int accent = MD3Theme.PRIMARY;

        GlHelper.drawText("Music", x + PAD, y + 22, SIDEBAR_TITLE_FONT, MD3Theme.TEXT_HIGH);
        GlHelper.drawText("Player", x + PAD, y + 42, SMALL_FONT, MD3Theme.withAlpha(accent, 0.55f));

        float navY = y + 68;
        float navH = 32;
        float navGap = 6;
        float navW = w - PAD * 2;

        navItem(ctx, x + PAD, navY, navW, navH, Page.PLAYER, ICON_PERSON, "Player", mx, my);
        navItem(ctx, x + PAD, navY + navH + navGap, navW, navH, Page.SEARCH, ICON_SEARCH, "Search", mx, my);
        navItem(ctx, x + PAD, navY + (navH + navGap) * 2, navW, navH, Page.QUEUE, ICON_QUEUE, "Queue", mx, my);
        navItem(ctx, x + PAD, navY + (navH + navGap) * 3, navW, navH, Page.ABOUT, ICON_INFO, "About", mx, my);
    }

    private void navItem(DrawContext ctx, float x, float y, float w, float h, Page target, String icon, String label, int mx, int my) {
        boolean active = this.page == target;
        boolean hover = contains(mx, my, x, y, w, h);
        int accent = MD3Theme.PRIMARY;

        if (active) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, 10),
                    new Paint().setColor(MD3Theme.withAlpha(accent, 0.15f)));
        } else if (hover) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, 10),
                    new Paint().setColor(MD3Theme.withAlpha(MD3Theme.SURFACE_HIGH, 0.5f)));
        }

        int textColor = active ? MD3Theme.TEXT_HIGH : hover ? MD3Theme.TEXT_MED : MD3Theme.TEXT_LOW;
        int iconColor = active ? accent : textColor;
        GlHelper.drawText(icon, x + 12, y + (h - ICON_FONT.getMetrics().capHeight()) / 2f + 3f, ICON_FONT, iconColor);
        GlHelper.drawText(label, x + 38, y + (h - SIDEBAR_FONT.getMetrics().capHeight()) / 2f + 2f, SIDEBAR_FONT, textColor);

        this.clickAreas.add(new ClickArea(x, y, w, h, () -> this.page = target));
    }

    // ───────────── Search Page ─────────────

    private void renderSearch(DrawContext ctx, float x, float y, float w, float h, int mx, int my) {
        int accent = MD3Theme.PRIMARY;

        // ── Unified search card ──
        float cardPad = PAD * 1.2f;
        float cardR = 20f;
        float cardX = x + PAD;
        float cardY = y + PAD;
        float cardW = w - PAD * 2;
        float inputH = 44f;
        float headerH = 32f;

        // Card background
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(cardX, cardY, cardW, h - PAD, cardR),
                new Paint().setColor(MD3Theme.withAlpha(MD3Theme.SURFACE, 0.95f)));

        // ── Header area: "Search" + input ──
        float innerX = cardX + cardPad;
        float innerW = cardW - cardPad * 2;

        GlHelper.drawText("Search", innerX, cardY + cardPad, TITLE_FONT, MD3Theme.TEXT_HIGH);

        float inputY = cardY + cardPad + headerH + 4;
        boolean inputHov = contains(mx, my, innerX, inputY, innerW, inputH);

        // Input background — surface container
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(innerX, inputY, innerW, inputH, 12),
                new Paint().setColor(MD3Theme.withAlpha(MD3Theme.SURFACE_CONTAINER, 0.9f)));
        if (searchFocused) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(innerX, inputY, innerW, inputH, 12),
                    new Paint().setColor(MD3Theme.withAlpha(accent, 0.12f)));
        }

        // Input text
        float textY = inputY + (inputH - INPUT_FONT.getMetrics().capHeight()) / 2f + 1f;
        boolean empty = searchText.isEmpty() && !searchFocused;

        // Search icon
        float iconX = innerX + 14;
        float iconY = inputY + (inputH - ICON_FONT.getMetrics().capHeight()) / 2f + 1f;
        GlHelper.drawText(ICON_SEARCH, iconX, iconY, ICON_FONT,
                MD3Theme.withAlpha(empty ? MD3Theme.TEXT_LOW : accent, 0.55f));

        // Input text (after icon)
        float inputTextOffset = iconX + GlHelper.getStringWidth(ICON_SEARCH, ICON_FONT) + 8;
        String display = empty ? "Search songs..." : searchText;
        int textAlpha = empty ? 100 : 200;
        GlHelper.drawText(ellipsize(display, 28), inputTextOffset, textY, INPUT_FONT,
                MD3Theme.withAlpha(searchFocused && !empty ? MD3Theme.TEXT_HIGH : MD3Theme.TEXT_LOW, textAlpha / 255f));

        // Cursor
        if (searchFocused) {
            float cursorX = inputTextOffset + measure(searchText.isEmpty() && !searchSelectAll ? "" : searchText, INPUT_FONT) + 2;
            float cursorH = 18f;
            float cursorY = inputY + (inputH - cursorH) / 2f;
            if (searchSelectAll && !searchText.isEmpty()) {
                float selW = measure(searchText, INPUT_FONT) + 6;
                ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(inputTextOffset - 2, cursorY, selW, cursorH, 4),
                        new Paint().setColor(MD3Theme.withAlpha(accent, 0.25f)));
            } else {
                float blink = (float) Math.abs(Math.sin(System.currentTimeMillis() / 200.0));
                ctx.drawLine(cursorX, cursorY, cursorX, cursorY + cursorH,
                        new Paint().setColor(MD3Theme.withAlpha(accent, (int) (200 * blink))).setStrokeWidth(1.5f));
            }
        }
        this.clickAreas.add(new ClickArea(innerX, inputY, innerW, inputH, () -> {
            this.searchFocused = true;
            this.searchSelectAll = false;
        }));

        // Enter key hint
        float hintX = innerX + innerW - 14;
        float hintW = GlHelper.getStringWidth("⏎", SMALL_FONT);
        GlHelper.drawText("⏎", hintX - hintW, textY, SMALL_FONT,
                MD3Theme.withAlpha(accent, searchText.isEmpty() ? 0.25f : 0.5f));

        // ── Results area ──
        float listY = inputY + inputH + 12;
        float listH = (cardY + h - PAD) - listY - cardPad;
        float rowH = 48f;

        maxSearchScroll = Math.max(0, searchResults.size() * (rowH + 3) - 3 - listH);
        searchScroll = clamp(searchScroll, 0, maxSearchScroll);

        ctx.save();
        ctx.clipRect(Rectangle.ofXYWH(innerX, listY, innerW, listH), true);

        if (searching) {
            String loading = ICON_SEARCH + "  Searching...";
            float lw = GlHelper.getStringWidth(loading, BODY_FONT);
            GlHelper.drawText(loading, innerX + (innerW - lw) / 2f, listY + listH / 2f - 10, BODY_FONT,
                    MD3Theme.withAlpha(MD3Theme.TEXT_LOW, 0.6f));
        } else if (searchResults.isEmpty() && !searchText.isEmpty() && !searchDirty) {
            GlHelper.drawText("No results found", innerX + 14, listY + 14, BODY_FONT, MD3Theme.TEXT_DISABLED);
        } else if (searchResults.isEmpty() && searchText.isEmpty()) {
            GlHelper.drawText("Type to search for songs", innerX + 14, listY + 14, BODY_FONT, MD3Theme.TEXT_DISABLED);
        } else {
            float yy = listY - searchScroll;
            AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
            SongInfo playing = player.getCurrentSong();
            for (int i = 0; i < searchResults.size(); i++) {
                SongInfo song = searchResults.get(i);
                if (yy + rowH < listY) { yy += rowH + 3; continue; }
                if (yy > listY + listH) break;

                boolean hover = contains(mx, my, innerX, yy, innerW, rowH);
                boolean isPlaying = playing != null && playing.id == song.id;

                if (isPlaying) {
                    ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(innerX, yy, innerW, rowH, 10),
                            new Paint().setColor(MD3Theme.withAlpha(accent, 0.12f)));
                } else if (hover) {
                    ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(innerX, yy, innerW, rowH, 10),
                            new Paint().setColor(MD3Theme.withAlpha(MD3Theme.SURFACE_HIGH, 0.4f)));
                }

                // Playing indicator
                if (isPlaying) {
                    GlHelper.drawText("♪", innerX + 12, yy + 14, HEADING_FONT, accent);
                }

                float textOffset = isPlaying ? 32 : 14;
                int nameColor = isPlaying ? accent : hover ? MD3Theme.TEXT_HIGH : MD3Theme.TEXT_MED;
                GlHelper.drawText(ellipsize(song.name, 34), innerX + textOffset, yy + 10,
                        isPlaying ? HEADING_FONT : BODY_FONT, nameColor);
                GlHelper.drawText(ellipsize(song.artist, 38), innerX + textOffset, yy + 30,
                        SMALL_FONT, MD3Theme.TEXT_DISABLED);

                if (song.duration > 0) {
                    String dur = song.formatDuration();
                    float durW = GlHelper.getStringWidth(dur, SMALL_FONT);
                    GlHelper.drawText(dur, innerX + innerW - durW - 4, yy + 14, SMALL_FONT, MD3Theme.TEXT_LOW);
                }

                int fi = i;
                this.clickAreas.add(new ClickArea(innerX, yy, innerW, rowH, () -> playSong(searchResults.get(fi))));
                yy += rowH + 3;
            }
        }

        ctx.restore();
    }

    // ───────────── Player Page ─────────────

    private void renderPlayer(DrawContext ctx, float x, float y, float w, float h, int mx, int my) {
        AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
        SongInfo song = player.getCurrentSong();

        if (song != null) {
            loadAlbumArt(song);
            if (albumBytes != null) {
                try {
                    NativeImage img = NativeImage.read(new ByteArrayInputStream(albumBytes));
                    DynamicTexture dyn = new DynamicTexture(img);
                    albumTexture = new Texture(dyn.getId(), img.getWidth(), img.getHeight());
                } catch (Exception e) {
                    System.err.println("[MusicPlayerScreen] Album art failed: " + e.getMessage());
                }
                albumBytes = null;
            }
        }

        GlHelper.drawText("Now Playing", x + PAD, y + PAD + 4, TITLE_FONT, MD3Theme.TEXT_HIGH);

        float coverSize = Math.min(180, Math.max(120, w * 0.28f));
        float coverX = x + PAD;
        float coverY = y + 58;
        float coverR = 14f;

        // Album art surface
        if (albumTexture != null) {
            org.joml.Matrix4f pose = ctx.getPoseStack().last().pose();
            DrawContext.getRoundedRectShader().drawTextured(pose,
                    coverX, coverY, coverX + coverSize, coverY + coverSize,
                    coverR, coverR, coverR, coverR,
                    0xFFFFFFFF, albumTexture.getGlId(), 0, 0, 1, 1);
        } else {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(coverX, coverY, coverSize, coverSize, coverR),
                    new Paint().setColor(MD3Theme.SURFACE_CONTAINER));
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(coverX, coverY, coverSize, coverSize, coverR),
                    new Paint().setColor(MD3Theme.withAlpha(MD3Theme.PRIMARY, 0.08f)));
        }

        if (song != null) {
            GlHelper.drawText(ellipsize(song.name, 22), coverX, coverY + coverSize + GAP + 2,
                    HEADING_FONT, MD3Theme.TEXT_HIGH);
            GlHelper.drawText(ellipsize(song.artist, 28), coverX, coverY + coverSize + GAP + 24,
                    BODY_FONT, MD3Theme.TEXT_MED);
        } else {
            GlHelper.drawText("No track playing", coverX, coverY + coverSize + GAP + 2,
                    HEADING_FONT, MD3Theme.TEXT_HIGH);
            GlHelper.drawText("Search a song to start.", coverX, coverY + coverSize + GAP + 24,
                    BODY_FONT, MD3Theme.TEXT_MED);
        }

        float lyricsX = coverX + coverSize + 24;
        float lyricsY = y + 58;
        float lyricsW = w - (lyricsX - x) - PAD;
        float lyricsH = h - 88;

        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(lyricsX, lyricsY, lyricsW, lyricsH, 14),
                new Paint().setColor(MD3Theme.SURFACE_CONTAINER));

        renderLyrics(ctx, song, lyricsX + 16, lyricsY + 14, lyricsW - 32, lyricsH - 28);
    }

    private void renderLyrics(DrawContext ctx, SongInfo song, float x, float y, float w, float h) {
        int accent = MD3Theme.PRIMARY;
        if (song == null) {
            GlHelper.drawText("Lyrics will appear here.", x, y, BODY_FONT, MD3Theme.TEXT_DISABLED);
            return;
        }

        List<LyricLine> lines = lyricsFor(song);
        if (lines.isEmpty()) {
            GlHelper.drawText("No lyrics available.", x, y, BODY_FONT, MD3Theme.TEXT_DISABLED);
            return;
        }

        AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
        long posMs = player.getCurrentPositionMs();
        int current = findCurrentLyricLine(lines, posMs);

        float lineH = 28;
        float targetScroll = Math.max(0, current * lineH - h * 0.42f);
        lyricScroll += (targetScroll - lyricScroll) * 0.18f;

        ctx.save();
        ctx.clipRect(Rectangle.ofXYWH(x, y, w, h), true);

        float yy = y - lyricScroll;
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            if (yy > y - lineH && yy < y + h + lineH) {
                boolean active = i == current;
                String display = timestamp(line.timeMs()) + "  " + line.text();
                int lyricColor = active ? accent : MD3Theme.TEXT_DISABLED;
                GlHelper.drawText(ellipsize(display, 60), x, yy + 3,
                        active ? HEADING_FONT : SMALL_FONT, lyricColor);
            }
            yy += lineH;
        }

        ctx.restore();
    }

    private int findCurrentLyricLine(List<LyricLine> lines, long posMs) {
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs() <= posMs) idx = i;
            else break;
        }
        return Math.max(0, idx);
    }

    private List<LyricLine> lyricsFor(SongInfo song) {
        List<LyricLine> cached = lyricsCache.get(song.id);
        if (cached != null) return cached;

        if (lyricsRequested.putIfAbsent(song.id, Boolean.TRUE) == null) {
            NeteaseApi.getLyrics(song.id).thenAccept(lines ->
                    lyricsCache.put(song.id, lines == null ? List.of() : lines));
        }
        return List.of();
    }

    // ───────────── Queue Page ─────────────

    private void renderQueue(DrawContext ctx, float x, float y, float w, float h, int mx, int my) {
        int accent = MD3Theme.PRIMARY;
        GlHelper.drawText("Queue", x + PAD, y + PAD + 4, TITLE_FONT, MD3Theme.TEXT_HIGH);

        if (!playQueue.isEmpty()) {
            String count = playQueue.size() + " tracks";
            float countW = GlHelper.getStringWidth(count, SMALL_FONT);
            GlHelper.drawText(count, x + w - countW - PAD, y + 28, SMALL_FONT, MD3Theme.TEXT_LOW);
        }

        float listX = x + PAD;
        float listY = y + 52;
        float rowH = 48;
        float listW = w - PAD * 2;
        float listH = h - 72;
        maxQueueScroll = Math.max(0, playQueue.size() * (rowH + 3) - 3 - listH);
        queueScroll = clamp(queueScroll, 0, maxQueueScroll);

        ctx.save();
        ctx.clipRect(Rectangle.ofXYWH(listX, listY, listW, listH), true);

        if (playQueue.isEmpty()) {
            GlHelper.drawText("Queue is empty.", listX + 14, listY + 14, BODY_FONT, MD3Theme.TEXT_DISABLED);
        } else {
            AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
            SongInfo playing = player.getCurrentSong();
            float yy = listY - queueScroll;
            for (int i = 0; i < playQueue.size(); i++) {
                SongInfo song = playQueue.get(i);
                if (yy + rowH < listY) { yy += rowH + 3; continue; }
                if (yy > listY + listH) break;

                boolean hover = contains(mx, my, listX, yy, listW, rowH);
                boolean isPlaying = playing != null && playing.id == song.id;

                if (isPlaying) {
                    ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(listX, yy, listW, rowH, 10),
                            new Paint().setColor(MD3Theme.withAlpha(accent, 0.12f)));
                } else if (hover) {
                    ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(listX, yy, listW, rowH, 10),
                            new Paint().setColor(MD3Theme.withAlpha(MD3Theme.SURFACE_HIGH, 0.4f)));
                }

                float numOffset = 0;
                if (isPlaying) {
                    GlHelper.drawText("♪", listX + 12, yy + 14, HEADING_FONT, accent);
                    numOffset = 32;
                } else {
                    String index = String.valueOf(i + 1);
                    float idxW = GlHelper.getStringWidth(index, SMALL_FONT);
                    GlHelper.drawText(index, listX + 14, yy + 16, SMALL_FONT, i == queueIndex ? accent : MD3Theme.TEXT_LOW);
                    numOffset = idxW + 26;
                }

                int nameColor = isPlaying ? accent : hover ? MD3Theme.TEXT_HIGH : MD3Theme.TEXT_MED;
                GlHelper.drawText(ellipsize(song.name, 30), listX + numOffset, yy + 10,
                        isPlaying ? HEADING_FONT : BODY_FONT, nameColor);
                GlHelper.drawText(ellipsize(song.artist, 34), listX + numOffset, yy + 30,
                        SMALL_FONT, MD3Theme.TEXT_DISABLED);

                if (song.duration > 0) {
                    String dur = song.formatDuration();
                    float durW = GlHelper.getStringWidth(dur, SMALL_FONT);
                    GlHelper.drawText(dur, listX + listW - durW - 4, yy + 16, SMALL_FONT, MD3Theme.TEXT_LOW);
                }

                int fi = i;
                this.clickAreas.add(new ClickArea(listX, yy, listW, rowH, () -> {
                    queueIndex = fi;
                    playSong(playQueue.get(fi));
                }));
                yy += rowH + 3;
            }
        }

        ctx.restore();
    }

    // ───────────── About Page ─────────────

    private void renderAbout(DrawContext ctx, float x, float y, float w, float h) {
        GlHelper.drawText("About", x + PAD, y + PAD + 4, TITLE_FONT, MD3Theme.TEXT_HIGH);

        float cardY = y + 56;
        float halfW = (w - PAD * 2 - GAP) * 0.5f;

        aboutCard(ctx, x + PAD, cardY, halfW, 110, "Music Player",
                "An in-game music player using\nNeteaseCloudMusic API.\nSearch, queue, and play music\ninside Minecraft.");
        aboutCard(ctx, x + PAD + halfW + GAP, cardY, halfW, 110, "Notice",
                "For personal learning and testing.\nThe client does not host or\ndistribute music content.");

        aboutCard(ctx, x + PAD, cardY + 126, w - PAD * 2, 80, "Credits",
                "Music API: gdstudio.xyz\nBased on NeteaseCloudMusic public API.");
    }

    private void aboutCard(DrawContext ctx, float x, float y, float w, float h, String title, String body) {
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, 12),
                new Paint().setColor(MD3Theme.SURFACE_CONTAINER));
        GlHelper.drawText(title, x + 16, y + 14, HEADING_FONT, MD3Theme.TEXT_HIGH);

        String[] lines = body.split("\\n");
        float yy = y + 38;
        for (String line : lines) {
            GlHelper.drawText(ellipsize(line, 46), x + 16, yy, SMALL_FONT, MD3Theme.TEXT_MED);
            yy += 20;
        }
    }

    // ───────────── Bottom Bar ─────────────

    private void renderBottomBar(DrawContext ctx, float x, float y, float w, float h, int mx, int my) {
        int accent = MD3Theme.PRIMARY;
        AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
        SongInfo currentSong = player.getCurrentSong();

        // Background — bottom rounded only
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, y, w, h, new float[]{0, 0, PANEL_RADIUS, PANEL_RADIUS}),
                new Paint().setColor(MD3Theme.SURFACE_DIM));

        // Top separator
        ctx.drawRectXYWH(x + PANEL_RADIUS, y, w - PANEL_RADIUS * 2, 1,
                new Paint().setColor(MD3Theme.withAlpha(MD3Theme.OUTLINE_VARIANT, 0.6f)));

        // Song info — left
        if (currentSong != null) {
            GlHelper.drawText(ellipsize(currentSong.name, 20), x + PAD, y + 12, BODY_FONT, MD3Theme.TEXT_HIGH);
            GlHelper.drawText(ellipsize(currentSong.artist, 24), x + PAD, y + 32, SMALL_FONT, MD3Theme.TEXT_MED);
        } else {
            GlHelper.drawText("No track", x + PAD, y + 12, BODY_FONT, MD3Theme.TEXT_HIGH);
            GlHelper.drawText("Music Player", x + PAD, y + 32, SMALL_FONT, MD3Theme.TEXT_LOW);
        }

        // Controls — center
        float cx = x + w * 0.5f;
        float cy = y + 12;
        drawIconBtn(ctx, cx - 52, cy, 36, 28, ICON_PREV, mx, my, this::prevSong);
        drawIconBtn(ctx, cx - 8, cy - 2, 40, 32,
                player.getState() == AudioPlayer.State.PLAYING ? ICON_PAUSE : ICON_PLAY, mx, my, player::togglePause);
        drawIconBtn(ctx, cx + 36, cy, 36, 28, ICON_NEXT, mx, my, this::nextSong);

        // Progress bar
        float pbX = cx - 130;
        float pbW = 260;
        float pbY = y + 50;

        float progress = player.getProgress();
        long currentMs = player.getCurrentPositionMs();
        long totalMs = currentSong != null ? currentSong.duration : 0;

        String curStr = timestamp(currentMs);
        String totStr = timestamp(totalMs);
        float timeY = pbY - 1;
        float curW = GlHelper.getStringWidth(curStr, SMALL_FONT);
        GlHelper.drawText(curStr, pbX - curW - 6, timeY, SMALL_FONT, MD3Theme.TEXT_LOW);
        drawSlider(ctx, pbX, pbY, pbW, progress, accent);
        GlHelper.drawText(totStr, pbX + pbW + 6, timeY, SMALL_FONT, MD3Theme.TEXT_LOW);
        this.lastProgressRect = Rectangle.ofXYWH(pbX, pbY - 8, pbW, 20);
        this.clickAreas.add(new ClickArea(pbX, pbY - 8, pbW, 20, () -> this.dragTarget = DragTarget.PROGRESS));

        // Volume
        float volX = x + w - 110;
        float volVal = this.dragTarget == DragTarget.VOLUME && this.pendingVolume >= 0
                ? this.pendingVolume : player.getVolume();
        GlHelper.drawText(ICON_VOLUME, volX - 16, timeY - 1, ICON_FONT, MD3Theme.TEXT_LOW);
        drawSlider(ctx, volX, pbY - 5f, 80, volVal, accent);
        this.lastVolumeRect = Rectangle.ofXYWH(volX, pbY - 13, 80, 20);
        this.clickAreas.add(new ClickArea(volX, pbY - 13, 80, 20, () -> this.dragTarget = DragTarget.VOLUME));
    }

    private void drawIconBtn(DrawContext ctx, float x, float y, float width, float height, String icon,
                             int mx, int my, Runnable action) {
        boolean hover = contains(mx, my, x, y, width, height);
        int bg = hover ? MD3Theme.withAlpha(MD3Theme.SURFACE_HIGH, 0.7f) : MD3Theme.withAlpha(MD3Theme.SURFACE_HIGH, 0.2f);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, width, height, 10), new Paint().setColor(bg));
        int color = hover ? MD3Theme.TEXT_HIGH : MD3Theme.TEXT_MED;
        float iy = y + (height - ICON_FONT_LG.getMetrics().capHeight()) / 2f;
        float iw = GlHelper.getStringWidth(icon, ICON_FONT_LG);
        GlHelper.drawText(icon, x + (width - iw) / 2, iy, ICON_FONT_LG, color);
        this.clickAreas.add(new ClickArea(x, y, width, height, action));
    }

    // ───────────── Drawing Helpers ─────────────

    private void drawSlider(DrawContext ctx, float x, float y, float w, float value, int accent) {
        float p = clamp(value, 0, 1);
        // Track bg
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, 3, 1.5f),
                new Paint().setColor(MD3Theme.withAlpha(MD3Theme.SURFACE_HIGHEST, 0.7f)));
        // Track fill
        if (p > 0.01f) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w * p, 3, 1.5f),
                    new Paint().setColor(MD3Theme.withAlpha(accent, 0.8f)));
        }
        // Thumb
        float thumbR = 6f;
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x + w * p - thumbR, y - (thumbR - 1.5f), thumbR * 2, thumbR * 2, thumbR),
                new Paint().setColor(p > 0.01f ? (int)accent : MD3Theme.TEXT_MED));
    }

    private void rounded(DrawContext ctx, float x, float y, float w, float h, float r, int[] rgba) {
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, r),
                new Paint().setColor(ColorUtil.fromARGB(rgba[0], rgba[1], rgba[2], rgba[3])));
    }

    private float measure(String text, FontRenderer font) {
        return GlHelper.getStringWidth(text, font);
    }

    // ───────────── Interaction ─────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        for (int i = clickAreas.size() - 1; i >= 0; i--) {
            ClickArea area = clickAreas.get(i);
            if (!area.contains(mouseX, mouseY)) continue;
            area.action.run();
            if (this.dragTarget == DragTarget.PROGRESS) updateProgress(mouseX);
            else if (this.dragTarget == DragTarget.VOLUME) updateVolume(mouseX);
            return true;
        }

        this.searchFocused = false;
        this.searchSelectAll = false;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            if (this.dragTarget == DragTarget.PROGRESS) { updateProgress(mouseX); return true; }
            if (this.dragTarget == DragTarget.VOLUME)   { updateVolume(mouseX);   return true; }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.dragTarget == DragTarget.VOLUME && this.pendingVolume >= 0) {
            MusicPlayer.AUDIO_PLAYER.setVolume(this.pendingVolume);
            this.pendingVolume = -1;
        }
        this.dragTarget = DragTarget.NONE;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.page == Page.SEARCH) {
            this.searchScroll = clamp(this.searchScroll - (float) delta * 28, 0, this.maxSearchScroll);
            return true;
        }
        if (this.page == Page.QUEUE) {
            this.queueScroll = clamp(this.queueScroll - (float) delta * 28, 0, this.maxQueueScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.searchFocused || codePoint < 32 || codePoint == 127) return super.charTyped(codePoint, modifiers);
        this.searchText = this.searchSelectAll ? String.valueOf(codePoint) : this.searchText + codePoint;
        this.searchSelectAll = false;
        this.searchDirty = true;
        this.lastSearchEditMs = System.currentTimeMillis();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.searchFocused) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { startSearch(); return true; }
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (this.searchSelectAll) { this.searchText = ""; this.searchSelectAll = false; }
                    else if (!this.searchText.isEmpty()) this.searchText = this.searchText.substring(0, this.searchText.length() - 1);
                    this.searchDirty = true;
                    this.lastSearchEditMs = System.currentTimeMillis();
                    return true;
                }
                case GLFW.GLFW_KEY_DELETE -> { this.searchText = ""; this.searchSelectAll = false; this.searchDirty = true; this.lastSearchEditMs = System.currentTimeMillis(); return true; }
                case GLFW.GLFW_KEY_ESCAPE -> { this.searchFocused = false; return true; }
            }
            if (Screen.isPaste(keyCode)) {
                String paste = Minecraft.getInstance().keyboardHandler.getClipboard();
                this.searchText = this.searchSelectAll ? paste : this.searchText + paste;
                this.searchSelectAll = false;
                this.searchDirty = true;
                this.lastSearchEditMs = System.currentTimeMillis();
                return true;
            }
            if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_A) {
                this.searchSelectAll = !this.searchText.isEmpty();
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { this.onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ───────────── Actions ─────────────

    private void startSearch() {
        this.searchDirty = false;
        String query = this.searchText.trim();
        long seq = this.searchSeq.incrementAndGet();
        if (query.isEmpty()) { this.searchResults = List.of(); this.searching = false; return; }
        this.searching = true;
        NeteaseApi.search(query, 20).whenComplete((results, throwable) -> {
            if (this.searchSeq.get() != seq) return;
            this.searchResults = results == null ? List.of() : results;
            this.searching = false;
            this.searchScroll = 0;
        });
    }

    private void playSong(SongInfo song) {
        queueIndex = searchResults.indexOf(song);
        if (queueIndex >= 0) {
            playQueue.clear();
            playQueue.addAll(searchResults);
        }

        NeteaseApi.getLyrics(song.id).thenAccept(lyrics -> {
            lyricsCache.put(song.id, lyrics == null ? List.of() : lyrics);
            try {
                LyricsModule mod = ZenClient.getInstance().getModuleManager().getModule(LyricsModule.class);
                if (mod != null) mod.setLyrics(song.id, lyrics);
            } catch (Exception ignored) {}
        });

        NeteaseApi.getSongUrl(song.id).thenAccept(result -> {
            if (result == null) return;
            if (song.duration <= 0 && result.size() > 0) song.duration = (result.size() * 1000L) / 40000;
            waitForWarmupThenPlay(song, result.url());
        });
    }

    private void waitForWarmupThenPlay(SongInfo song, String url) {
        LyricsModule mod = ZenClient.getInstance().getModuleManager().getModule(LyricsModule.class);
        if (mod == null || mod.isWarmupComplete()) { MusicPlayer.AUDIO_PLAYER.play(song, url); return; }
        new Thread(() -> {
            while (!mod.isWarmupComplete()) { try { Thread.sleep(50); } catch (InterruptedException e) { return; } }
            MusicPlayer.AUDIO_PLAYER.play(song, url);
        }, "LyricsWarmupWait").start();
    }

    private void nextSong() {
        if (playQueue.isEmpty() || queueIndex < 0) return;
        queueIndex = (queueIndex + 1) % playQueue.size();
        playSong(playQueue.get(queueIndex));
    }

    private void prevSong() {
        if (playQueue.isEmpty() || queueIndex < 0) return;
        queueIndex = (queueIndex - 1 + playQueue.size()) % playQueue.size();
        playSong(playQueue.get(queueIndex));
    }

    private void updateProgress(double mouseX) {
        if (lastProgressRect == null) return;
        AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
        SongInfo song = player.getCurrentSong();
        if (song == null || song.duration <= 0) return;
        float ratio = clamp((float) ((mouseX - lastProgressRect.getX()) / lastProgressRect.getWidth()), 0, 1);
        player.seekToMs((long) (ratio * song.duration));
    }

    private void updateVolume(double mouseX) {
        if (lastVolumeRect == null) return;
        float ratio = clamp((float) ((mouseX - lastVolumeRect.getX()) / lastVolumeRect.getWidth()), 0, 1);
        this.pendingVolume = ratio;
        MusicPlayer.AUDIO_PLAYER.setVolume(ratio);
        MusicPlayer musicMod = ZenClient.getInstance().getModuleManager().getModule(MusicPlayer.class);
        if (musicMod != null) musicMod.setVolumeSetting(ratio);
    }

    // ───────────── Album Art ─────────────

    private void loadAlbumArt(SongInfo song) {
        if (albumLoading) return;
        if (song.id == albumSongId && albumTexture != null) return;
        if (song.id == albumSongId && albumRetryCount >= 2) return;
        if (song.id != albumSongId) albumRetryCount = 0;
        albumSongId = song.id;
        albumTexture = null;
        albumBytes = null;
        albumLoading = true;
        albumRetryCount++;

        NeteaseApi.getAlbumPicUrl(song.albumPicUrl).thenAccept(picUrl -> {
            if (picUrl == null || picUrl.isEmpty()) { albumLoading = false; return; }
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(picUrl))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
                        .GET().build();
                byte[] bytes = client.send(req, HttpResponse.BodyHandlers.ofByteArray()).body();
                if (bytes != null && bytes.length > 100) albumBytes = bytes;
                else { System.err.println("[MusicPlayerScreen] Album art too small"); albumSongId = -1; }
            } catch (Exception e) { System.err.println("[MusicPlayerScreen] Album download failed: " + e.getMessage()); albumSongId = -1; }
            finally { albumLoading = false; }
        }).exceptionally(e -> { albumLoading = false; albumSongId = -1; return null; });
    }

    // ───────────── Utilities ─────────────

    private static boolean contains(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static String ellipsize(String value, int maxChars) {
        if (value == null) return "";
        if (value.length() <= maxChars) return value;
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static String timestamp(long ms) {
        long safe = Math.max(0, ms);
        long total = safe / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ───────────── Types ─────────────

    private enum Page { PLAYER, SEARCH, QUEUE, ABOUT }
    private enum DragTarget { NONE, PROGRESS, VOLUME }

    private record ClickArea(float x, float y, float width, float height, Runnable action) {
        boolean contains(double mouseX, double mouseY) { return MusicPlayerScreen.contains(mouseX, mouseY, x, y, width, height); }
    }
}