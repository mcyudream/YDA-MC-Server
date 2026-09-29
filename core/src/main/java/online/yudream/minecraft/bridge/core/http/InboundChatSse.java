package online.yudream.minecraft.bridge.core.http;

import online.yudream.minecraft.bridge.core.json.JsonSyntaxException;
import online.yudream.minecraft.bridge.core.json.JsonValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * 群服互联 SSE 客户端：解析 {@code event/data} 帧，把 {@code message} 事件回调给监听器。
 *
 * <p>{@code connected} 帧携带服务端当前缓冲最大序号 {@code latest}（首连快进游标用，
 * 与轮询接口的 {@code cursor==0 只快进不广播} 语义一致）；{@code heartbeat} 帧只用于保活。
 */
public final class InboundChatSse {

    public interface Listener {

        /** 连接建立（含重放前）。游标为 0 时应快进到 {@code latest}，不广播历史消息。 */
        default void onConnected(long latest) {
        }

        /** 每个解析完成的 SSE 事件（connected/heartbeat/message）都会回调，供调试日志使用。 */
        default void onEvent(String event, String data) {
        }

        /** 一条要广播进游戏的群消息。 */
        void onMessage(long seq, String sender, String content, long at);
    }

    private InboundChatSse() {
    }

    /** 阻塞读取直到流断开；HTTP 非 200 或响应不是 SSE 时抛 {@link IOException}，由调用方降级轮询。 */
    public static void read(HttpURLConnection connection, Listener listener) throws IOException {
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP " + status);
            }
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.contains("text/event-stream")) {
                throw new IOException("非 SSE 响应：" + contentType);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String event = "message";
            StringBuilder data = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data != null) {
                        dispatch(event, data.toString(), listener);
                    }
                    event = "message";
                    data = null;
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String payload = line.substring(5);
                    if (payload.startsWith(" ")) {
                        payload = payload.substring(1);
                    }
                    if (data == null) {
                        data = new StringBuilder();
                    }
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(payload);
                }
                // id:/retry:/注释行与本协议无关，忽略
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void dispatch(String event, String data, Listener listener) {
        listener.onEvent(event, data);
        if ("connected".equals(event)) {
            JsonValue value = parse(data);
            if (value != null && value.isObject()) {
                listener.onConnected(value.getOr("latest", JsonValue.of(0L)).asLong(0L));
            }
            return;
        }
        if (!"message".equals(event)) {
            return;
        }
        JsonValue value = parse(data);
        if (value == null || !value.isObject()) {
            return;
        }
        String content = value.getOr("content", JsonValue.of("")).asString("");
        if (content.trim().isEmpty()) {
            return;
        }
        listener.onMessage(
                value.getOr("seq", JsonValue.of(0L)).asLong(0L),
                value.getOr("sender", JsonValue.of("")).asString(""),
                content,
                value.getOr("at", JsonValue.of(0L)).asLong(0L));
    }

    private static JsonValue parse(String data) {
        try {
            return JsonValue.parse(data);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }
}
