package online.yudream.minecraft.bridge.core.http;

/** The status code and raw body of one YuDream Admin HTTP call. */
public final class HttpResult {

    private final int statusCode;
    private final String body;

    public HttpResult(int statusCode, String body) {
        this.statusCode = statusCode;
        this.body = body;
    }

    public int statusCode() {
        return statusCode;
    }

    public String body() {
        return body;
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    public String trimmedBody() {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HttpResult)) {
            return false;
        }
        HttpResult other = (HttpResult) o;
        return statusCode == other.statusCode
                && (body == null ? other.body == null : body.equals(other.body));
    }

    @Override
    public int hashCode() {
        int result = statusCode;
        result = 31 * result + (body == null ? 0 : body.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "HttpResult[statusCode=" + statusCode + ", body=" + body + "]";
    }
}
