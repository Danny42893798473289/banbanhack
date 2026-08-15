package shit.zen.modules.impl.misc;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.ChatReceiveEvent;
import shit.zen.event.impl.DisconnectEvent;
import shit.zen.event.impl.WorldChangeEvent;
import shit.zen.irc.IrcClient;
import shit.zen.irc.IrcConfig;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.utils.misc.ChatUtil;
import shit.zen.utils.misc.ThreadPool;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跨服 IRC 聊天模块。
 * <p>
 * 开启后会连接到你自己部署的 ZenIRC 中转服务器（见 server/ 目录）：
 * - 服务器会把"当前正在同一个 host 上、并且开启了本模块"的玩家名单发回来，
 *   客户端据此在 Tab 玩家列表里给这些名字前面加上青色的 "[ZENMAX USER] " 标记。
 * - 输入 ".irc 文本" 会把文本发给所有当前在线、开启了本模块的用户，不分服务器。
 * <p>
 * 配置文件在 ~/.zen/irc.json，第一次运行会自动生成，需要手动填服务器地址/端口/密钥。
 */
public class IRC extends Module {

    public static IRC INSTANCE;

    private final BooleanSetting joinLeaveMessages = new BooleanSetting("Join/Leave Messages", true);
    private final BooleanSetting autoReconnect = new BooleanSetting("Auto Reconnect", true);

    private static final String TAG_PREFIX = "§b[ZENMAX USER] §r";
    private static final int RECONNECT_DELAY_SECONDS = 5;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int HEARTBEAT_SECONDS = 20;

    private IrcConfig config;
    private IrcClient client;
    private volatile String lastSentHost = null;
    private final Set<String> taggedUsers = ConcurrentHashMap.newKeySet();
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private ScheduledFuture<?> heartbeatFuture;
    private volatile boolean shuttingDown = false;

    public IRC() {
        super("IRC", Category.WORLD);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        shuttingDown = false;
        config = IrcConfig.loadOrCreate();
        if (config.isPlaceholder()) {
            ChatUtil.addMessage(Component.literal(
                    "§c[IRC] 请先编辑 " + IrcConfig.FILE.getAbsolutePath() + " 填写服务器地址与密钥，然后重新开启本模块。"));
            this.setEnabled(false);
            return;
        }
        client = new IrcClient(new IrcClient.IrcListener() {
            @Override
            public void onConnected() {
                handleConnected();
            }

            @Override
            public void onMessage(JsonObject message) {
                handleMessage(message);
            }

            @Override
            public void onDisconnected(String reason) {
                handleDisconnected(reason);
            }
        });
        reconnectAttempts.set(0);
        attemptConnect();
    }

    @Override
    protected void onDisable() {
        shuttingDown = true;
        cancelHeartbeat();
        if (client != null) {
            client.disconnect("module disabled");
        }
        taggedUsers.clear();
        lastSentHost = null;
    }

    private void attemptConnect() {
        if (shuttingDown || client == null) {
            return;
        }
        ThreadPool.submit(() -> {
            try {
                client.connect(config.host, config.port, config.useTls, CONNECT_TIMEOUT_MS);
            } catch (IOException ex) {
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (shuttingDown || !autoReconnect.getValue()) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        long delay = Math.min(RECONNECT_DELAY_SECONDS * Math.max(1, attempt / 3 + 1), 60);
        ThreadPool.scheduleWithDelay(this::attemptConnect, delay, TimeUnit.SECONDS);
    }

    private void handleConnected() {
        reconnectAttempts.set(0);
        JsonObject hello = new JsonObject();
        hello.addProperty("op", "hello");
        hello.addProperty("user", localUsername());
        hello.addProperty("token", config.token);
        hello.addProperty("host", currentServerHost());
        client.send(hello);
        lastSentHost = currentServerHost();
        startHeartbeat();
    }

    private void handleDisconnected(String reason) {
        cancelHeartbeat();
        taggedUsers.clear();
        if (!shuttingDown) {
            runOnMainThread(() -> ChatUtil.addMessage(Component.literal("§c[IRC] 连接断开: " + reason)));
            scheduleReconnect();
        }
    }

    private void handleMessage(JsonObject message) {
        if (!message.has("op")) return;
        String op = message.get("op").getAsString();
        switch (op) {
            case "welcome" -> {
                String motd = message.has("motd") ? message.get("motd").getAsString() : "connected";
                runOnMainThread(() -> ChatUtil.addMessage(Component.literal("§a[IRC] " + motd)));
            }
            case "error" -> {
                String msg = message.has("message") ? message.get("message").getAsString() : "unknown error";
                runOnMainThread(() -> ChatUtil.addMessage(Component.literal("§c[IRC] 错误: " + msg)));
            }
            case "chat" -> {
                String user = message.has("user") ? message.get("user").getAsString() : "?";
                String text = message.has("text") ? message.get("text").getAsString() : "";
                String host = message.has("host") && !message.get("host").isJsonNull() ? message.get("host").getAsString() : "?";
                runOnMainThread(() -> ChatUtil.addMessage(Component.literal(
                        "§b[IRC] §f" + user + " §7(" + host + ")§f: " + text)));
            }
            case "system" -> {
                if (joinLeaveMessages.getValue()) {
                    String text = message.has("text") ? message.get("text").getAsString() : "";
                    runOnMainThread(() -> ChatUtil.addMessage(Component.literal("§7[IRC] " + text)));
                }
            }
            case "presence" -> {
                String host = message.has("host") && !message.get("host").isJsonNull() ? message.get("host").getAsString() : null;
                String myHost = currentServerHost();
                if (Objects.equals(host, myHost) && message.has("users")) {
                    Set<String> next = ConcurrentHashMap.newKeySet();
                    message.getAsJsonArray("users").forEach(el -> next.add(el.getAsString()));
                    taggedUsers.clear();
                    taggedUsers.addAll(next);
                }
            }
            case "pong" -> {
                // 心跳回应，不需要处理
            }
            default -> {
                // 未知操作码，忽略，方便以后扩展协议
            }
        }
    }

    private void startHeartbeat() {
        cancelHeartbeat();
        heartbeatFuture = ThreadPool.scheduleWithDelay(this::heartbeatTick, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    private void heartbeatTick() {
        if (shuttingDown || client == null) {
            return;
        }
        if (client.isConnected()) {
            JsonObject ping = new JsonObject();
            ping.addProperty("op", "ping");
            client.send(ping);
            heartbeatFuture = ThreadPool.scheduleWithDelay(this::heartbeatTick, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void cancelHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    private void runOnMainThread(Runnable runnable) {
        if (mc != null) {
            mc.execute(runnable);
        } else {
            runnable.run();
        }
    }

    private String localUsername() {
        try {
            return mc.getUser() != null ? mc.getUser().getName() : "Unknown";
        } catch (Exception ex) {
            return "Unknown";
        }
    }

    private String currentServerHost() {
        ServerData serverData = mc.getCurrentServer();
        return serverData != null ? serverData.ip : null;
    }

    private void sendPresence(String host) {
        if (client != null && client.isConnected()) {
            JsonObject presence = new JsonObject();
            presence.addProperty("op", "presence");
            presence.addProperty("host", host);
            client.send(presence);
        }
    }

    /**
     * 供 IrcCommand 调用：发送一条跨服聊天消息。
     */
    public void sendChat(String text) {
        if (client == null || !client.isConnected()) {
            ChatUtil.addMessage(Component.literal("§c[IRC] 尚未连接，请先用 .t IRC 开启并检查配置。"));
            return;
        }
        JsonObject chat = new JsonObject();
        chat.addProperty("op", "chat");
        chat.addProperty("text", text);
        client.send(chat);
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        String host = currentServerHost();
        if (!Objects.equals(host, lastSentHost)) {
            lastSentHost = host;
            taggedUsers.clear();
            sendPresence(host);
        }
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent event) {
        lastSentHost = null;
        taggedUsers.clear();
        sendPresence(null);
    }

    /**
     * 给 Tab 玩家列表里的名字打标记。只处理 NAME 类型（玩家列表里的名字），
     * 通过字符串包含匹配用户名，和 NameProtect 模块使用的思路一致。
     */
    @EventTarget
    public void onChatReceive(ChatReceiveEvent event) {
        if (event.getMsgType() != ChatReceiveEvent.MessageType.NAME || taggedUsers.isEmpty()) {
            return;
        }
        String plain = event.getComponent().getString();
        for (String user : taggedUsers) {
            if (user != null && !user.isBlank() && plain.contains(user)) {
                MutableComponent tagged = Component.literal(TAG_PREFIX).append(event.getComponent());
                event.setComponent(tagged);
                break;
            }
        }
    }
}
