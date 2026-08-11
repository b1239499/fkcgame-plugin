package com.fkc.game.uno.model;

/**
 * A single UNO card. Standard 108-card deck: 4 colors × (one 0, two each of
 * 1-9, two Skip, two Reverse, two Draw Two) + 4 Wild + 4 Wild Draw Four.
 */
public class Card {

    public enum Color { RED, YELLOW, GREEN, BLUE, WILD }
    public enum Type { NUMBER, SKIP, REVERSE, DRAW_TWO, WILD, WILD_DRAW_FOUR }

    public final Color color;
    public final Type type;
    public final int number; // only meaningful when type == NUMBER

    public Card(Color color, Type type, int number) {
        this.color = color;
        this.type = type;
        this.number = number;
    }

    public static Card number(Color color, int number) {
        return new Card(color, Type.NUMBER, number);
    }

    public static Card action(Color color, Type type) {
        return new Card(color, type, -1);
    }

    public static Card wild(Type type) {
        return new Card(Color.WILD, type, -1);
    }

    public boolean isWild() {
        return type == Type.WILD || type == Type.WILD_DRAW_FOUR;
    }

    /** Standard UNO scoring value of this card, used when tallying the loser's hand at round end. */
    public int pointValue() {
        return switch (type) {
            case NUMBER -> number;
            case SKIP, REVERSE, DRAW_TWO -> 20;
            case WILD, WILD_DRAW_FOUR -> 50;
        };
    }

    private static String colorName(Color c) {
        return switch (c) {
            case RED -> "紅";
            case YELLOW -> "黃";
            case GREEN -> "綠";
            case BLUE -> "藍";
            case WILD -> "萬能";
        };
    }

    /** Chat-safe plain-text label, e.g. "紅5", "綠跳過", "萬能+4". No embedded color codes — Component.text() doesn't parse those, they'd show up as literal garbage text. */
    public String display() {
        return switch (type) {
            case NUMBER -> colorName(color) + number;
            case SKIP -> colorName(color) + "跳過";
            case REVERSE -> colorName(color) + "反轉";
            case DRAW_TWO -> colorName(color) + "+2";
            case WILD -> "萬能";
            case WILD_DRAW_FOUR -> "萬能+4";
        };
    }

    /** Plain (no color code) label, used inside hover text / logs. */
    public String plainLabel() {
        return switch (type) {
            case NUMBER -> colorName(color) + number;
            case SKIP -> colorName(color) + "跳過";
            case REVERSE -> colorName(color) + "反轉";
            case DRAW_TWO -> colorName(color) + "加二";
            case WILD -> "萬能牌";
            case WILD_DRAW_FOUR -> "萬能加四";
        };
    }

    /**
     * A unique code for this card's face (not this specific physical copy),
     * used for click-command round-tripping, e.g. "R5", "G-SKIP", "WILD4".
     */
    public String code() {
        String colorPart = switch (color) {
            case RED -> "R";
            case YELLOW -> "Y";
            case GREEN -> "G";
            case BLUE -> "B";
            case WILD -> "";
        };
        String typePart = switch (type) {
            case NUMBER -> String.valueOf(number);
            case SKIP -> "-SKIP";
            case REVERSE -> "-REV";
            case DRAW_TWO -> "-D2";
            case WILD -> "WILD";
            case WILD_DRAW_FOUR -> "WILD4";
        };
        return colorPart + typePart;
    }

    /**
     * Parses both the machine code from code() (for buttons) and a handful
     * of human-typeable shorthands, case-insensitive:
     * colors: r/red/紅/红, y/yellow/黃/黄, g/green/綠/绿, b/blue/藍/蓝
     * values: 0-9, s/skip/跳過, re/rev/reverse/反轉, d2/+2/加二
     * wild: w/wild/萬能, w4/wild4/萬能4/萬能+4
     */
    public static Card parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Exact machine code round-trip first.
        Card exact = parseExactCode(s);
        if (exact != null) return exact;

        String lower = s.toLowerCase();
        if (lower.equals("w") || lower.equals("wild") || s.equals("萬能") || s.equals("万能")) {
            return wild(Type.WILD);
        }
        if (lower.equals("w4") || lower.equals("wild4") || s.equals("萬能4") || s.equals("萬能+4") || s.equals("万能+4")) {
            return wild(Type.WILD_DRAW_FOUR);
        }

        Color color = null;
        String rest = null;
        String[][] colorPrefixes = {
                {"red", "R"}, {"紅", "R"}, {"红", "R"}, {"r", "R"},
                {"yellow", "Y"}, {"黃", "Y"}, {"黄", "Y"}, {"y", "Y"},
                {"green", "G"}, {"綠", "G"}, {"绿", "G"}, {"g", "G"},
                {"blue", "B"}, {"藍", "B"}, {"蓝", "B"}, {"b", "B"},
        };
        for (String[] pair : colorPrefixes) {
            String prefix = pair[0];
            boolean matches = prefix.matches("[a-z]+") ? lower.startsWith(prefix) : s.startsWith(prefix);
            if (!matches || s.length() <= prefix.length()) continue;
            color = switch (pair[1]) {
                case "R" -> Color.RED;
                case "Y" -> Color.YELLOW;
                case "G" -> Color.GREEN;
                default -> Color.BLUE;
            };
            rest = s.substring(prefix.length());
            break;
        }
        if (color == null || rest == null || rest.isEmpty()) return null;

        String restLower = rest.toLowerCase();
        if (restLower.equals("s") || restLower.equals("skip") || rest.equals("跳過") || rest.equals("跳过")) {
            return action(color, Type.SKIP);
        }
        if (restLower.equals("re") || restLower.equals("rev") || restLower.equals("reverse") || rest.equals("反轉") || rest.equals("反转")) {
            return action(color, Type.REVERSE);
        }
        if (restLower.equals("d2") || rest.equals("+2") || rest.equals("加二") || rest.equals("加2")) {
            return action(color, Type.DRAW_TWO);
        }
        try {
            int num = Integer.parseInt(rest);
            if (num >= 0 && num <= 9) return number(color, num);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return null;
    }

    private static Card parseExactCode(String s) {
        String upper = s.toUpperCase();
        if (upper.equals("WILD")) return wild(Type.WILD);
        if (upper.equals("WILD4")) return wild(Type.WILD_DRAW_FOUR);
        if (upper.length() < 2) return null;
        char colorChar = upper.charAt(0);
        Color color = switch (colorChar) {
            case 'R' -> Color.RED;
            case 'Y' -> Color.YELLOW;
            case 'G' -> Color.GREEN;
            case 'B' -> Color.BLUE;
            default -> null;
        };
        if (color == null) return null;
        String rest = upper.substring(1);
        return switch (rest) {
            case "-SKIP" -> action(color, Type.SKIP);
            case "-REV" -> action(color, Type.REVERSE);
            case "-D2" -> action(color, Type.DRAW_TWO);
            default -> {
                try {
                    int num = Integer.parseInt(rest);
                    yield (num >= 0 && num <= 9) ? number(color, num) : null;
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
        };
    }

    @Override
    public String toString() {
        return plainLabel();
    }
}
