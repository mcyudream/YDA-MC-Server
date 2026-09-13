package online.yudream.minecraft.bridge.common.http;

/** The status code and raw body of one YuDream Admin HTTP call. */
public record HttpResult(int statusCode, String body) {

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    public String trimmedBody() {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}
