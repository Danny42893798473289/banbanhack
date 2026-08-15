package shit.zen.modules.impl.misc.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NeteaseApi {
    private static final String BASE = "https://music-api.gdstudio.xyz/api.php";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Pattern LRC_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})](.*)");

    public static CompletableFuture<List<SongInfo>> search(String keywords, int limit) {
        String encoded = java.net.URLEncoder.encode(keywords, StandardCharsets.UTF_8);
        return get("types=search&source=netease&name=" + encoded + "&count=" + limit + "&pages=1")
                .thenApply(root -> {
                    List<SongInfo> results = new ArrayList<>();
                    if (root == null || !root.isJsonArray()) return results;
                    JsonArray arr = root.getAsJsonArray();
                    for (JsonElement el : arr) {
                        JsonObject obj = el.getAsJsonObject();
                        long id = obj.get("id").getAsLong();
                        String name = obj.get("name").getAsString();
                        JsonArray artists = obj.getAsJsonArray("artist");
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < artists.size(); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(artists.get(i).getAsString());
                        }
                        String albumName = obj.has("album") ? obj.get("album").getAsString() : "";
                        String picId = obj.has("pic_id") ? obj.get("pic_id").getAsString() : "";
                        results.add(new SongInfo(id, name, sb.toString(), albumName, picId, 0));
                    }
                    return results;
                });
    }

    public record SongUrlResult(String url, long size) {}

    public static CompletableFuture<SongUrlResult> getSongUrl(long songId) {
        return get("types=url&source=netease&id=" + songId + "&br=320")
                .thenApply(root -> {
                    if (root == null || !root.isJsonObject()) return null;
                    JsonObject obj = root.getAsJsonObject();
                    String url = obj.has("url") && !obj.get("url").isJsonNull()
                            ? obj.get("url").getAsString() : null;
                    long size = obj.has("size") ? obj.get("size").getAsLong() : 0;
                    System.out.println("[MusicPlayer] Song URL: " + url + " size=" + size);
                    return url != null ? new SongUrlResult(url, size) : null;
                });
    }

    public static CompletableFuture<String> getAlbumPicUrl(String picId) {
        if (picId == null || picId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return get("types=pic&source=netease&id=" + picId + "&size=300")
                .thenApply(root -> {
                    if (root == null || !root.isJsonObject()) return null;
                    JsonObject obj = root.getAsJsonObject();
                    return obj.has("url") ? obj.get("url").getAsString() : null;
                });
    }

    public static CompletableFuture<List<LyricLine>> getLyrics(long songId) {
        return get("types=lyric&source=netease&id=" + songId)
                .thenApply(root -> {
                    if (root == null || !root.isJsonObject()) return Collections.emptyList();
                    JsonObject obj = root.getAsJsonObject();
                    String raw = obj.has("lyric") ? obj.get("lyric").getAsString() : "";
                    if (raw.isEmpty()) return Collections.emptyList();
                    return parseLrc(raw);
                });
    }

    private static List<LyricLine> parseLrc(String raw) {
        List<LyricLine> lines = new ArrayList<>();
        for (String line : raw.split("\n")) {
            Matcher m = LRC_PATTERN.matcher(line);
            if (!m.matches()) continue;
            String frac = m.group(3);
            long fracMs = frac.length() == 2 ? Long.parseLong(frac) * 10 : Long.parseLong(frac);
            long ms = Long.parseLong(m.group(1)) * 60_000
                    + Long.parseLong(m.group(2)) * 1000
                    + fracMs;
            String text = m.group(4).trim();
            if (!text.isEmpty()) {
                lines.add(new LyricLine(ms, text));
            }
        }
        Collections.sort(lines, (a, b) -> Long.compare(a.timeMs(), b.timeMs()));
        return lines;
    }

    private static CompletableFuture<JsonElement> get(String params) {
        String url = BASE + "?" + params;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .GET()
                .build();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    System.out.println("[MusicPlayer] API " + params.split("&")[0] + " status=" + resp.statusCode());
                    String body = resp.body();
                    JsonReader reader = new JsonReader(new StringReader(body));
                    reader.setLenient(true);
                    return JsonParser.parseReader(reader);
                })
                .exceptionally(e -> {
                    System.err.println("[MusicPlayer] API error: " + e.getMessage());
                    e.printStackTrace();
                    return null;
                });
    }
}
