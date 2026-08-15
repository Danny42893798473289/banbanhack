package shit.zen.irc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import shit.zen.ZenClient;
import shit.zen.utils.misc.IOUtil;

import java.io.File;
import java.io.FileReader;

/**
 * IRC 模块的独立配置文件：{@code ~/.zen/irc.json}。
 * 之所以不用 Setting 系统，是因为客户端目前只有 Boolean/Mode/Number/MultiSelect
 * 几种设置类型，没有可以填字符串（服务器地址、端口、密钥）的设置项，
 * 用独立配置文件更简单也更不容易出问题。
 */
public class IrcConfig {

    public static final File FILE = new File(ZenClient.configDir, "irc.json");

    public String host = "127.0.0.1";
    public int port = 7878;
    public String token = "CHANGE_ME";
    public boolean useTls = false;

    public boolean isPlaceholder() {
        return token == null || token.isBlank() || token.equals("CHANGE_ME") || host == null || host.isBlank();
    }

    public static IrcConfig loadOrCreate() {
        IrcConfig config = new IrcConfig();
        try {
            if (!FILE.exists()) {
                config.save();
                return config;
            }
            try (FileReader fr = new FileReader(FILE)) {
                JsonObject obj = JsonParser.parseReader(fr).getAsJsonObject();
                if (obj.has("host")) config.host = obj.get("host").getAsString();
                if (obj.has("port")) config.port = obj.get("port").getAsInt();
                if (obj.has("token")) config.token = obj.get("token").getAsString();
                if (obj.has("useTls")) config.useTls = obj.get("useTls").getAsBoolean();
            }
        } catch (Exception ex) {
            ZenClient.logger.warn("Failed to load irc.json, using defaults", ex);
        }
        return config;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("host", host);
        obj.addProperty("port", port);
        obj.addProperty("token", token);
        obj.addProperty("useTls", useTls);
        IOUtil.writeJson(FILE, obj);
    }
}
