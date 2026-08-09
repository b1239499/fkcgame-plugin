package com.fkc.game.mahjong.model;

import java.util.Objects;

/**
 * A single mahjong tile.
 * type 0 = number tile (man/pin/sou), 1 = wind, 2 = dragon.
 * For number tiles: suit is MAN/PIN/SOU, number is 1-9.
 * For winds: number is 1=East 2=South 3=West 4=North.
 * For dragons: number is 1=Haku(white) 2=Hatsu(green) 3=Chun(red).
 */
public class Tile implements Comparable<Tile> {

    public enum Suit { MAN, PIN, SOU, WIND, DRAGON, FLOWER_SEASON, FLOWER_PLANT }

    public final Suit suit;
    public final int number; // 1-9 for MAN/PIN/SOU, 1-4 for WIND, 1-3 for DRAGON
    public final boolean red; // red five (aka dora)

    public Tile(Suit suit, int number, boolean red) {
        this.suit = suit;
        this.number = number;
        this.red = red;
    }

    public Tile(Suit suit, int number) {
        this(suit, number, false);
    }

    public boolean isNumber() {
        return suit == Suit.MAN || suit == Suit.PIN || suit == Suit.SOU;
    }

    public boolean isHonor() {
        return suit == Suit.WIND || suit == Suit.DRAGON;
    }

    public boolean isTerminal() {
        return isNumber() && (number == 1 || number == 9);
    }

    public boolean isTerminalOrHonor() {
        return isTerminal() || isHonor();
    }

    /** Season (春夏秋冬) or plant (梅蘭竹菊) flower tile — Taiwanese mahjong only. */
    public boolean isFlower() {
        return suit == Suit.FLOWER_SEASON || suit == Suit.FLOWER_PLANT;
    }

    /**
     * Whether this flower matches the given seat (1=East..4=North) — the
     * "正花" bonus condition in Taiwanese mahjong.
     */
    public boolean matchesSeat(int seatWind) {
        return isFlower() && number == seatWind;
    }

    /**
     * Equality/sort key ignores the red-five flag: a red 5 and a normal 5
     * are the same tile for the purposes of forming sets/pairs.
     */
    public int typeIndex() {
        return switch (suit) {
            case MAN -> number - 1;
            case PIN -> 9 + number - 1;
            case SOU -> 18 + number - 1;
            case WIND -> 27 + number - 1;
            case DRAGON -> 31 + number - 1;
            case FLOWER_SEASON -> 34 + number - 1; // 34-37, Taiwanese-only, never appears in riichi's 0-33 range
            case FLOWER_PLANT -> 38 + number - 1;  // 38-41
        };
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Tile other)) return false;
        return this.typeIndex() == other.typeIndex();
    }

    @Override
    public int hashCode() {
        return typeIndex();
    }

    @Override
    public int compareTo(Tile other) {
        return Integer.compare(this.typeIndex(), other.typeIndex());
    }

    /** Unicode mahjong tile glyph for chat display. */
    public String glyph() {
        return switch (suit) {
            case MAN -> String.valueOf((char) (0x1F007 + (number - 1)));
            case SOU -> String.valueOf((char) (0x1F010 + (number - 1)));
            case PIN -> String.valueOf((char) (0x1F019 + (number - 1)));
            case WIND -> switch (number) {
                case 1 -> "\uD83C\uDC00"; // East
                case 2 -> "\uD83C\uDC02"; // South
                case 3 -> "\uD83C\uDC03"; // West
                default -> "\uD83C\uDC01"; // North
            };
            case DRAGON -> switch (number) {
                case 1 -> "\uD83C\uDC06"; // Haku (white)
                case 2 -> "\uD83C\uDC05"; // Hatsu (green)
                default -> "\uD83C\uDC04"; // Chun (red)
            };
            case FLOWER_PLANT -> switch (number) {
                case 1 -> "\uD83C\uDC22"; // Plum (梅)
                case 2 -> "\uD83C\uDC23"; // Orchid (蘭)
                case 3 -> "\uD83C\uDC24"; // Bamboo (竹)
                default -> "\uD83C\uDC25"; // Chrysanthemum (菊)
            };
            case FLOWER_SEASON -> switch (number) {
                case 1 -> "\uD83C\uDC26"; // Spring (春)
                case 2 -> "\uD83C\uDC27"; // Summer (夏)
                case 3 -> "\uD83C\uDC28"; // Autumn (秋)
                default -> "\uD83C\uDC29"; // Winter (冬)
            };
        };
    }

    /** Short Chinese label, e.g. "5萬", "東", "中". */
    public String label() {
        return switch (suit) {
            case MAN -> number + "萬";
            case PIN -> number + "筒";
            case SOU -> number + "條";
            case WIND -> switch (number) {
                case 1 -> "東";
                case 2 -> "南";
                case 3 -> "西";
                default -> "北";
            };
            case DRAGON -> switch (number) {
                case 1 -> "白";
                case 2 -> "發";
                default -> "中";
            };
            case FLOWER_PLANT -> switch (number) {
                case 1 -> "花梅";
                case 2 -> "花蘭";
                case 3 -> "花竹";
                default -> "花菊";
            };
            case FLOWER_SEASON -> switch (number) {
                case 1 -> "花春";
                case 2 -> "花夏";
                case 3 -> "花秋";
                default -> "花冬";
            };
        };
    }

    public static Tile plantFlower(int seatNumber) {
        return new Tile(Suit.FLOWER_PLANT, seatNumber);
    }

    public static Tile seasonFlower(int seatNumber) {
        return new Tile(Suit.FLOWER_SEASON, seatNumber);
    }

    /** Safe-to-print-anywhere text form: Chinese label, with 寶 appended for a red five (aka dora). No embedded color codes — Component.text() doesn't parse those. */
    public String display() {
        return label() + (red ? "寶" : "");
    }

    /**
     * The special Unicode character mapped to this tile's ItemsAdder font
     * image (see mahjong_tiles_config_v2.yml). A red five still uses the
     * same tile image as a normal five — there's no separate "red" tile
     * graphic in the asset pack — with "寶" appended as text afterward
     * (same convention as display()) so it's still distinguishable.
     */
    public String imageGlyph() {
        String symbol = switch (suit) {
            case MAN -> String.valueOf((char) (0xE000 + (number - 1)));
            case PIN -> String.valueOf((char) (0xE009 + (number - 1)));
            case SOU -> String.valueOf((char) (0xE012 + (number - 1)));
            case WIND -> String.valueOf((char) (0xE01B + (number - 1)));
            case DRAGON -> String.valueOf((char) (0xE01F + (number - 1)));
            case FLOWER_PLANT -> String.valueOf((char) (0xE022 + (number - 1)));
            case FLOWER_SEASON -> String.valueOf((char) (0xE026 + (number - 1)));
        };
        return symbol + (red ? "寶" : "");
    }

    /** All 34 tile types, one copy each (no red flag). */
    public static Tile fromTypeIndex(int idx) {
        if (idx < 9) return new Tile(Suit.MAN, idx + 1);
        if (idx < 18) return new Tile(Suit.PIN, idx - 9 + 1);
        if (idx < 27) return new Tile(Suit.SOU, idx - 18 + 1);
        if (idx < 31) return new Tile(Suit.WIND, idx - 27 + 1);
        return new Tile(Suit.DRAGON, idx - 31 + 1);
    }

    /**
     * Parses a human-typed tile name into a Tile, or returns null if it
     * doesn't match any recognized format. Accepts, case-insensitively and
     * with surrounding whitespace trimmed:
     * <ul>
     *   <li>Numeric type index 0-33 (what the clickable buttons send)</li>
     *   <li>Romaji-style shorthand: "5m"/"5man", "5p"/"5pin", "5s"/"5sou"</li>
     *   <li>Chinese suit names: "5萬"/"5万", "5筒"/"5饼", "5條"/"5条"</li>
     *   <li>Winds: "東"/"东"/"e"/"east", "南"/"s2"/"south",
     *       "西"/"west", "北"/"north"</li>
     *   <li>Dragons: "白"/"haku", "發"/"发"/"hatsu", "中"/"chun"</li>
     * </ul>
     */
    public static Tile parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Plain numeric type index (what buttons send under the hood).
        try {
            int idx = Integer.parseInt(s);
            if (idx >= 0 && idx <= 33) return fromTypeIndex(idx);
        } catch (NumberFormatException ignored) {
            // fall through to text formats
        }

        String lower = s.toLowerCase();

        // Honor tiles first (no leading digit to parse).
        switch (lower) {
            case "東", "东", "east" -> { return new Tile(Suit.WIND, 1); }
            case "南", "south" -> { return new Tile(Suit.WIND, 2); }
            case "西", "west" -> { return new Tile(Suit.WIND, 3); }
            case "北", "north" -> { return new Tile(Suit.WIND, 4); }
            case "白", "haku" -> { return new Tile(Suit.DRAGON, 1); }
            case "發", "发", "hatsu" -> { return new Tile(Suit.DRAGON, 2); }
            case "中", "chun" -> { return new Tile(Suit.DRAGON, 3); }
            default -> { /* try number+suit formats below */ }
        }

        if (lower.length() < 2) return null;
        char first = lower.charAt(0);
        if (first < '1' || first > '9') return null;
        int number = first - '0';
        String suitPart = s.substring(1);
        String suitLower = suitPart.toLowerCase();

        boolean isMan = suitLower.startsWith("m") || suitPart.startsWith("萬") || suitPart.startsWith("万");
        boolean isPin = suitLower.startsWith("p") || suitPart.startsWith("筒") || suitPart.startsWith("饼") || suitPart.startsWith("餅");
        boolean isSou = suitLower.startsWith("s") || suitPart.startsWith("條") || suitPart.startsWith("条") || suitPart.startsWith("索");

        if (isMan) return new Tile(Suit.MAN, number);
        if (isPin) return new Tile(Suit.PIN, number);
        if (isSou) return new Tile(Suit.SOU, number);
        return null;
    }

    @Override
    public String toString() {
        return label();
    }
}
