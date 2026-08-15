package shit.zen.irc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极简的长连接客户端：协议是"换行分隔的 JSON"(JSON Lines)，
 * 一行就是一条完整的 JSON 消息，用 "\n" 分隔。
 * <p>
 * 这个类只负责连接管理和收发字节，不包含任何游戏逻辑，
 * 具体的业务处理都交给 {@link IrcListener} 的回调。
 */
public class IrcClient {

    public interface IrcListener {
        void onConnected();

        void onMessage(JsonObject message);

        void onDisconnected(String reason);
    }

    private final IrcListener listener;
    private volatile Socket socket;
    private volatile BufferedReader reader;
    private volatile BufferedWriter writer;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public IrcClient(IrcListener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected.get();
    }

    /**
     * 阻塞式连接，务必在后台线程调用（例如 ThreadPool.submit）。
     */
    public synchronized void connect(String host, int port, boolean useTls, int timeoutMs) throws IOException {
        disconnect("reconnecting");
        SocketFactory factory = useTls ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        Socket newSocket = factory.createSocket();
        newSocket.connect(new InetSocketAddress(host, port), timeoutMs);
        newSocket.setSoTimeout(0);
        newSocket.setTcpNoDelay(true);

        this.socket = newSocket;
        this.reader = new BufferedReader(new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8));
        connected.set(true);

        Thread readThread = new Thread(this::readLoop, "Zen-IRC-Read");
        readThread.setDaemon(true);
        readThread.start();

        listener.onConnected();
    }

    private void readLoop() {
        try {
            String line;
            BufferedReader currentReader = this.reader;
            while (connected.get() && currentReader != null && (line = currentReader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                    listener.onMessage(obj);
                } catch (JsonSyntaxException | IllegalStateException badJson) {
                    // 忽略格式错误的单行数据，不打断整条连接
                }
            }
            disconnect("connection closed by server");
        } catch (IOException ex) {
            disconnect(ex.getMessage() == null ? "io error" : ex.getMessage());
        }
    }

    public synchronized void send(JsonObject obj) {
        BufferedWriter currentWriter = this.writer;
        if (!connected.get() || currentWriter == null) {
            return;
        }
        try {
            currentWriter.write(obj.toString());
            currentWriter.write("\n");
            currentWriter.flush();
        } catch (IOException ex) {
            disconnect(ex.getMessage() == null ? "write error" : ex.getMessage());
        }
    }

    public synchronized void disconnect(String reason) {
        boolean wasConnected = connected.getAndSet(false);
        try {
            if (reader != null) reader.close();
        } catch (IOException ignored) {
        }
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        reader = null;
        writer = null;
        socket = null;
        if (wasConnected) {
            listener.onDisconnected(reason);
        }
    }
}
