package games.jvrcruz.siliconcraft.compiler;

public class NNumberParser {

    public static boolean isNumeric(String token) {
        if (token == null) return false;
        token = token.trim();
        if (token.isEmpty()) return false;
        if (token.startsWith("-") || token.startsWith("+")) {
            token = token.substring(1).trim();
        }
        if (token.isEmpty()) return false;
        if (token.startsWith("0x") || token.startsWith("0X")) {
            String hex = token.substring(2);
            if (hex.isEmpty()) return false;
            for (int i = 0; i < hex.length(); i++) {
                char c = hex.charAt(i);
                if (Character.digit(c, 16) == -1) return false;
            }
            return true;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public static int parseNumber(String token) {
        if (token == null) throw new NumberFormatException("null");
        token = token.trim();
        boolean negative = false;
        if (token.startsWith("-")) {
            negative = true;
            token = token.substring(1).trim();
        } else if (token.startsWith("+")) {
            token = token.substring(1).trim();
        }
        long val;
        if (token.startsWith("0x") || token.startsWith("0X")) {
            val = Long.parseUnsignedLong(token.substring(2), 16);
        } else {
            val = Long.parseLong(token, 10);
        }
        int result = (int) val;
        return negative ? -result : result;
    }
}
