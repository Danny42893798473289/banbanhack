package shit.zen.modules.impl.render;

import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import com.mojang.datafixers.util.Pair;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.PacketEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.render.FontStore;
import shit.zen.settings.impl.BooleanSetting;

import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SwordNotifier extends Module {

    public static SwordNotifier INSTANCE;

    public final BooleanSetting chatAlert = new BooleanSetting("Chat Alert", true);
    public final BooleanSetting distanceText = new BooleanSetting("Distance Text", true);
    public final BooleanSetting redesp = new BooleanSetting("RED MARK", true);
    public final BooleanSetting sendToPublicChat = new BooleanSetting("Send To Public Chat", false);

    // 🌟 听觉雷达警告
    public final BooleanSetting soundAlert = new BooleanSetting("Sound Alert", true);
    // 🌟 原版光灵箭透视高亮
    public final BooleanSetting nativeGlow = new BooleanSetting("Native Glow", true);
    // 🌟 发现目标时本地劈雷（视觉特效）
    public final BooleanSetting strikeLightning = new BooleanSetting("Strike Lightning", true);

    private final Set<String> markedPlayerNames = new HashSet<>();
    private final Set<String> alertedPlayerNames = new HashSet<>();

    // 异步安全的位置队列，用于在主线程生成闪电
    private final List<Vec3> lightningQueue = new CopyOnWriteArrayList<>();

    private int textIndex = 0;
    private String pendingMessage = "";
    private long retryTime = 0;

    private int tickCounter = 0;
    private double currentNearestDistance = -1;
    private String currentNearestName = "";

    public SwordNotifier() {
        super("SwordNotifier", Category.RENDER);
        INSTANCE = this;
    }

    public boolean isMarked(String playerName) {
        if (!this.isEnabled() || playerName == null) return false;
        return this.markedPlayerNames.contains(playerName.toLowerCase().trim());
    }

    @Override
    public void onEnable() {
        this.clearMarkers();
        this.pendingMessage = "";
        this.retryTime = 0;
        this.tickCounter = 0;
        this.currentNearestDistance = -1;
        this.currentNearestName = "";
        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.clearMarkers();
        this.tickCounter = 0;
        super.onDisable();
    }

    private void clearMarkers() {
        if (mc.level != null) {
            for (Player player : mc.level.players()) {
                if (player != null && player.getGameProfile().getName() != null) {
                    if (this.markedPlayerNames.contains(player.getGameProfile().getName().toLowerCase().trim())) {
                        player.setGlowingTag(false);
                    }
                }
            }
        }
        this.markedPlayerNames.clear();
        this.alertedPlayerNames.clear();
        this.lightningQueue.clear();
        this.currentNearestDistance = -1;
        this.currentNearestName = "";
    }

    private void sendNativeChatMessage(String text) {
        if (mc.player == null || text.isEmpty()) return;
        mc.player.connection.sendChat(text);
    }

    private void sendNextMessage(String name) {
        if (!this.sendToPublicChat.getValue()) return;

        String msg = "";
        switch (this.textIndex) {
            case 0 -> msg = "我看到了，" + name + "是杀手。";
            case 1 -> msg = name + "是杀手。";
            case 2 -> msg = "这个" + name + "是杀手!";
        }

        this.textIndex = (this.textIndex + 1) % 3;
        this.pendingMessage = msg;
        this.sendNativeChatMessage(msg);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;

        this.tickCounter++;

        // 🌟 处理本地劈雷特效 (在主线程运行保证安全)
        if (!this.lightningQueue.isEmpty()) {
            if (this.strikeLightning.getValue()) {
                for (Vec3 pos : this.lightningQueue) {
                    LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(mc.level);
                    if (lightning != null) {
                        lightning.setPos(pos.x, pos.y, pos.z);
                        lightning.setVisualOnly(true); // 设置为纯视觉效果，不生火不造成伤害

                        // 使用极小的负数ID避免与服务器里的实体ID冲突
                        int fakeEntityId = -100000 - (int)(Math.random() * 10000);
                        //mc.level.addEntity(fakeEntityId, lightning);

                        // 播放本地立体声雷声
                        mc.level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 10000.0F, 0.8F + mc.level.random.nextFloat() * 0.2F, false);
                        mc.level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER, 2.0F, 0.5F + mc.level.random.nextFloat() * 0.2F, false);
                    }
                }
            }
            this.lightningQueue.clear(); // 清空队列
        }

        double nearestDistance = -1;
        String nearestName = "";

        // 🌟 核心逻辑：距离计算 & 设置原版发光
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;

            String rawName = player.getGameProfile().getName();
            if (rawName == null) continue;

            if (this.markedPlayerNames.contains(rawName.toLowerCase().trim())) {
                if (this.nativeGlow.getValue()) {
                    player.setGlowingTag(true);
                } else {
                    player.setGlowingTag(false);
                }

                double distance = mc.player.distanceTo(player);
                if (nearestDistance < 0 || distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestName = rawName;
                }
            }
        }

        this.currentNearestDistance = nearestDistance;
        this.currentNearestName = nearestName;

        // 🌟 听觉雷达逻辑
        if (this.soundAlert.getValue() && nearestDistance >= 0) {
            if (nearestDistance <= 10.0) {
                if (this.tickCounter % 5 == 0) {
                    mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
                }
            } else if (nearestDistance <= 20.0) {
                if (this.tickCounter % 10 == 0) {
                    mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
                }
            } else if (nearestDistance <= 30.0) {
                if (this.tickCounter % 20 == 0) {
                    mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
                }
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.level == null || event.getPacket() == null) return;

        // 1. 底层拦截拔剑动作
        if (event.getPacket() instanceof ClientboundSetEquipmentPacket equipmentPacket) {
            Entity entity = mc.level.getEntity(equipmentPacket.getEntity());
            if (entity instanceof Player targetPlayer && targetPlayer != mc.player) {

                for (Pair<EquipmentSlot, ItemStack> slotChange : equipmentPacket.getSlots()) {
                    EquipmentSlot slot = slotChange.getFirst();
                    ItemStack itemStack = slotChange.getSecond();

                    if ((slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND)
                            && itemStack.is(Items.DIAMOND_SWORD)) {

                        String rawName = targetPlayer.getGameProfile().getName();
                        if (rawName == null || rawName.isEmpty()) continue;

                        String lowerName = rawName.toLowerCase().trim();
                        this.markedPlayerNames.add(lowerName);

                        // 当且仅当第一次发现此人拿剑时（即不在已警告名单内），才劈雷和发消息
                        if (!this.alertedPlayerNames.contains(lowerName)) {

                            // 👉 将目标坐标推入队列，等待下一次 Tick 劈雷
                            this.lightningQueue.add(targetPlayer.position());

                            if (this.chatAlert.getValue()) {
                                mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(
                                        "§c[Warning] §f玩家 §e" + rawName + " §f是杀手，已被全网永久锁定！"
                                ));
                                this.sendNextMessage(rawName);
                            }
                            this.alertedPlayerNames.add(lowerName);
                        }
                    }
                }
            }
        }

        // 2. 拦截服务器系统聊天返回
        String chatMessage = "";
        if (event.getPacket() instanceof ClientboundSystemChatPacket packet) {
            chatMessage = packet.content().getString();
        } else if (event.getPacket() instanceof ClientboundDisguisedChatPacket packet) {
            chatMessage = packet.message().getString();
        }

        if (!chatMessage.isEmpty()) {
            if (chatMessage.contains("请不要刷屏或者发送重复消息哦")) {
                Pattern pattern = Pattern.compile("\\((\\d+)\\s*秒\\)");
                Matcher matcher = pattern.matcher(chatMessage);
                if (matcher.find()) {
                    try {
                        int seconds = Integer.parseInt(matcher.group(1));
                        this.retryTime = System.currentTimeMillis() + (seconds + 1) * 1000L;
                    } catch (Exception e) {
                        this.retryTime = System.currentTimeMillis() + 2000L;
                    }
                }
            }

            if (chatMessage.contains("匹配") || chatMessage.contains("游戏开始") || chatMessage.contains("START")) {
                this.clearMarkers();
                this.pendingMessage = "";
                this.retryTime = 0;

                mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(
                        "§a[SwordNotifier] §f检测到新对局，已重置黑名单并强行终止挂起的重发任务。"
                ));
            }
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (this.sendToPublicChat.getValue() && !this.pendingMessage.isEmpty() && this.retryTime > 0) {
            if (System.currentTimeMillis() >= this.retryTime) {
                this.sendNativeChatMessage(this.pendingMessage);
                this.retryTime = 0;
                this.pendingMessage = "";
            }
        }

        if (this.currentNearestDistance < 0) return;

        if (this.distanceText.getValue()) {
            String text = String.format("⚠ 危险目标 [%s] 距你 %.1f 格 ⚠", this.currentNearestName, this.currentNearestDistance);

            int screenWidth = mc.getWindow().getGuiScaledWidth();
            int screenHeight = mc.getWindow().getGuiScaledHeight();

            float x = screenWidth / 2.0f;
            float y = screenHeight * 0.65f;

            Color distanceColor;
            if (this.currentNearestDistance <= 10.0) {
                distanceColor = new Color(255, 30, 30);
            } else if (this.currentNearestDistance <= 20.0) {
                distanceColor = new Color(255, 128, 0);
            } else if (this.currentNearestDistance <= 30.0) {
                distanceColor = new Color(255, 255, 0);
            } else {
                distanceColor = new Color(50, 255, 50);
            }

            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();

            if (event.poseStack() != null) {
                FontStore.PINGFANG_18.drawStringCenteredColor(
                        event.poseStack(),
                        text,
                        x,
                        y,
                        distanceColor
                );
            }
        }
    }
}