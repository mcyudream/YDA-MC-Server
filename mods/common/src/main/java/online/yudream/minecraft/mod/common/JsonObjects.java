package online.yudream.minecraft.mod.common;

import java.util.Collection;

public final class JsonObjects {

    private JsonObjects() {
    }

    public static String playerEvent(PlayerEventPayload payload) {
        StringBuilder json = new StringBuilder("{")
                .append("\"playerId\":\"").append(escape(payload.getPlayerId())).append("\",")
                .append("\"playerName\":\"").append(escape(payload.getPlayerName())).append("\",")
                .append("\"eventAt\":").append(payload.getEventAt());
        // content 只在群服互联事件（聊天/死亡/成就）上出现；旧端点的载荷保持不变
        if (payload.getContent() != null) {
            json.append(",\"content\":\"").append(escape(payload.getContent())).append("\"");
        }
        return json.append("}").toString();
    }

    public static String playerSnapshot(Collection<PlayerEventPayload> players, long observedAt) {
        StringBuilder json = new StringBuilder("{\"observedAt\":")
                .append(observedAt)
                .append(",\"players\":[");
        boolean first = true;
        for (PlayerEventPayload player : players) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"playerId\":\"").append(escape(player.getPlayerId()))
                    .append("\",\"playerName\":\"").append(escape(player.getPlayerName())).append("\"}");
        }
        return json.append("]}").toString();
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    result.append("\\\"");
                    break;
                case '\\':
                    result.append("\\\\");
                    break;
                case '\b':
                    result.append("\\b");
                    break;
                case '\f':
                    result.append("\\f");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        result.append("\\u");
                        for (int pad = hex.length(); pad < 4; pad++) {
                            result.append('0');
                        }
                        result.append(hex);
                    } else {
                        result.append(c);
                    }
                    break;
            }
        }
        return result.toString();
    }
}
