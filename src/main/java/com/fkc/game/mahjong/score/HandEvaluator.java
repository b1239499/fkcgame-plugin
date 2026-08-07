package com.fkc.game.mahjong.score;

import com.fkc.game.mahjong.model.Meld;
import com.fkc.game.mahjong.model.Tile;

import java.util.ArrayList;
import java.util.List;

/**
 * Decomposes a concealed hand into valid mentsu (sets) + pair combinations,
 * detects yaku, and computes han/fu/points.
 * <p>
 * This is a from-scratch implementation (not wrapping an external library)
 * so the plugin compiles reliably without depending on an unverified
 * third-party API surface. It covers the yaku that come up in the vast
 * majority of real hands. NOT implemented: most yakuman other than
 * kokushi musou (no suuankou/daisangen/tsuuiisou/chinroutou/etc.),
 * chankan/rinshan/haitei/houtei situational yaku, and ura-dora.
 */
public class HandEvaluator {

    public static class WinContext {
        public boolean tsumo;
        public boolean riichi;
        public boolean doubleRiichi;
        public boolean ippatsu;
        public int seatWind;   // 1=E 2=S 3=W 4=N
        public int roundWind;  // 1=E 2=S 3=W 4=N
        public List<Tile> doraTiles = new ArrayList<>();
        public boolean isDealer;
    }

    public static class YakuHit {
        public final String name;
        public final int han;
        public YakuHit(String name, int han) { this.name = name; this.han = han; }
    }

    public static class WinResult {
        public boolean valid;
        public List<YakuHit> yaku = new ArrayList<>();
        public int totalHan;
        public int fu;
        public boolean isYakuman;
        public long points;
        public String summary() {
            if (!valid) return "沒有胡牌型";
            StringBuilder sb = new StringBuilder();
            for (YakuHit y : yaku) {
                sb.append(y.name).append(isYakuman ? "" : "(" + y.han + "翻)").append(" ");
            }
            if (isYakuman) {
                sb.append("— 役滿！");
            } else {
                sb.append("— 共 ").append(totalHan).append(" 翻 ").append(fu).append(" 符，").append(points).append(" 點");
            }
            return sb.toString();
        }
    }

    /**
     * Structural validity only — is this a legal 4-sets+pair (or chiitoitsu
     * or kokushi) shape, regardless of whether any yaku would apply?
     * Used for tenpai detection, which is a shape question, not a yaku
     * question — a hand can be tenpai even if its only possible yaku is
     * riichi itself (very common), which evaluate() alone can't tell you
     * before riichi has actually been declared.
     */
    public static boolean hasValidShape(List<Tile> concealed, List<Meld> melds) {
        if (melds.isEmpty() && (isKokushi(concealed) || isChiitoitsu(concealed))) {
            return true;
        }
        int setsNeeded = 4 - melds.size();
        return !decompose(concealed, setsNeeded).isEmpty();
    }

    public static WinResult evaluate(List<Tile> concealed, List<Meld> melds, WinContext ctx) {
        WinResult result = new WinResult();

        if (melds.isEmpty() && isKokushi(concealed)) {
            result.valid = true;
            result.isYakuman = true;
            result.yaku.add(new YakuHit("國士無雙", 0));
            result.points = ctx.isDealer ? 48000 : 32000;
            return result;
        }

        if (melds.isEmpty() && isChiitoitsu(concealed)) {
            boolean menzen = true;
            List<YakuHit> yaku = new ArrayList<>();
            yaku.add(new YakuHit("七對子", 2));
            addCommonSituational(yaku, ctx, menzen);
            int doraHan = countDora(concealed, melds, ctx.doraTiles) + countAkaDora(concealed, melds);
            if (doraHan > 0) yaku.add(new YakuHit("寶牌", doraHan));
            int totalHan = yaku.stream().mapToInt(y -> y.han).sum();
            result.valid = true;
            result.yaku = yaku;
            result.totalHan = totalHan;
            result.fu = 25;
            result.points = computePoints(totalHan, 25, ctx.isDealer, ctx.tsumo);
            return result;
        }

        int setsNeeded = 4 - melds.size();
        List<Decomposition> decomps = decompose(concealed, setsNeeded);
        if (decomps.isEmpty()) {
            result.valid = false;
            return result;
        }

        WinResult best = null;
        for (Decomposition d : decomps) {
            WinResult candidate = scoreDecomposition(d, melds, ctx, concealed);
            if (candidate.valid && (best == null || candidate.totalHan > best.totalHan
                    || (candidate.totalHan == best.totalHan && candidate.fu > best.fu))) {
                best = candidate;
            }
        }
        return best != null ? best : result;
    }

    private static class Group {
        List<Tile> tiles;
        boolean isSequence;
        Group(List<Tile> tiles, boolean isSequence) { this.tiles = tiles; this.isSequence = isSequence; }
    }

    private static class Decomposition {
        List<Group> sets = new ArrayList<>();
        Tile pair1;
        Tile pair2;
    }

    private static List<Decomposition> decompose(List<Tile> tiles, int setsNeeded) {
        List<Decomposition> results = new ArrayList<>();
        if (tiles.size() != setsNeeded * 3 + 2) return results;
        List<Tile> sorted = new ArrayList<>(tiles);
        sorted.sort(null);

        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                if (!sorted.get(i).equals(sorted.get(j))) continue;
                List<Tile> rest = new ArrayList<>(sorted);
                rest.remove(j);
                rest.remove(i);
                List<List<Group>> setCombos = new ArrayList<>();
                findSets(rest, new ArrayList<>(), setCombos);
                for (List<Group> combo : setCombos) {
                    Decomposition d = new Decomposition();
                    d.sets = combo;
                    d.pair1 = sorted.get(i);
                    d.pair2 = sorted.get(j);
                    results.add(d);
                }
                break;
            }
        }
        return results;
    }

    private static void findSets(List<Tile> remaining, List<Group> current, List<List<Group>> out) {
        if (remaining.isEmpty()) {
            out.add(new ArrayList<>(current));
            return;
        }
        Tile first = remaining.get(0);

        if (count(remaining, first) >= 3) {
            List<Tile> next = removeN(remaining, first, 3);
            current.add(new Group(List.of(first, first, first), false));
            findSets(next, current, out);
            current.remove(current.size() - 1);
        }

        if (first.isNumber() && first.number <= 7) {
            Tile second = new Tile(first.suit, first.number + 1);
            Tile third = new Tile(first.suit, first.number + 2);
            if (contains(remaining, second) && contains(remaining, third)) {
                List<Tile> next = new ArrayList<>(remaining);
                next.remove(indexOf(next, first));
                next.remove(indexOf(next, second));
                next.remove(indexOf(next, third));
                current.add(new Group(List.of(first, second, third), true));
                findSets(next, current, out);
                current.remove(current.size() - 1);
            }
        }
    }

    private static int count(List<Tile> list, Tile t) {
        int c = 0;
        for (Tile x : list) if (x.equals(t)) c++;
        return c;
    }
    private static boolean contains(List<Tile> list, Tile t) {
        for (Tile x : list) if (x.equals(t)) return true;
        return false;
    }
    private static int indexOf(List<Tile> list, Tile t) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).equals(t)) return i;
        return -1;
    }
    private static List<Tile> removeN(List<Tile> list, Tile t, int n) {
        List<Tile> copy = new ArrayList<>(list);
        for (int i = 0; i < n; i++) copy.remove(indexOf(copy, t));
        return copy;
    }

    private static boolean isChiitoitsu(List<Tile> tiles) {
        if (tiles.size() != 14) return false;
        List<Tile> sorted = new ArrayList<>(tiles);
        sorted.sort(null);
        for (int i = 0; i < 14; i += 2) {
            if (!sorted.get(i).equals(sorted.get(i + 1))) return false;
        }
        return true;
    }

    private static boolean isKokushi(List<Tile> tiles) {
        if (tiles.size() != 14) return false;
        int[] terminalTypes = {0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33};
        boolean hasPair = false;
        for (int type : terminalTypes) {
            int c = 0;
            for (Tile t : tiles) if (t.typeIndex() == type) c++;
            if (c == 0) return false;
            if (c >= 2) hasPair = true;
        }
        for (Tile t : tiles) {
            boolean ok = false;
            for (int type : terminalTypes) if (t.typeIndex() == type) ok = true;
            if (!ok) return false;
        }
        return hasPair;
    }

    private static WinResult scoreDecomposition(Decomposition d, List<Meld> melds, WinContext ctx, List<Tile> concealed) {
        WinResult result = new WinResult();
        boolean menzen = melds.stream().allMatch(m -> m.type == Meld.Type.ANKAN);

        List<Group> allSets = new ArrayList<>(d.sets);
        for (Meld m : melds) {
            allSets.add(new Group(m.tiles.subList(0, Math.min(3, m.tiles.size())), m.type == Meld.Type.CHI));
        }

        List<YakuHit> yaku = new ArrayList<>();

        boolean allSequences = allSets.stream().noneMatch(g -> !g.isSequence);
        boolean allTriplets = allSets.stream().allMatch(g -> !g.isSequence);
        boolean noTerminalsHonors = allSets.stream().flatMap(g -> g.tiles.stream()).noneMatch(Tile::isTerminalOrHonor)
                && !d.pair1.isTerminalOrHonor();
        boolean everyGroupHasTerminalHonor = allSets.stream().allMatch(g -> g.tiles.stream().anyMatch(Tile::isTerminalOrHonor))
                && d.pair1.isTerminalOrHonor();
        boolean anyHonorPresent = allSets.stream().flatMap(g -> g.tiles.stream()).anyMatch(Tile::isHonor) || d.pair1.isHonor();

        addCommonSituational(yaku, ctx, menzen);

        boolean pinfu = allSequences && menzen && isPinfuShape(d, ctx);
        if (menzen && ctx.tsumo) yaku.add(new YakuHit("門前清自摸和", 1));
        if (pinfu) yaku.add(new YakuHit("平和", 1));
        if (noTerminalsHonors) yaku.add(new YakuHit("斷么九", 1));
        if (allTriplets) yaku.add(new YakuHit("對對和", 2));

        long concealedTriplets = allSets.stream().filter(g -> !g.isSequence).count()
                - melds.stream().filter(m -> !m.isConcealed() && m.type != Meld.Type.CHI).count();
        if (concealedTriplets >= 3 && !allSequences) yaku.add(new YakuHit("三暗刻", 2));

        for (Group g : allSets) {
            if (g.isSequence) continue;
            Tile t = g.tiles.get(0);
            if (t.suit == Tile.Suit.DRAGON) yaku.add(new YakuHit("役牌:" + t.label(), 1));
            if (t.suit == Tile.Suit.WIND && t.number == ctx.seatWind) yaku.add(new YakuHit("役牌:自風", 1));
            if (t.suit == Tile.Suit.WIND && t.number == ctx.roundWind) yaku.add(new YakuHit("役牌:場風", 1));
        }

        if (menzen) {
            for (int i = 0; i < d.sets.size(); i++) {
                for (int j = i + 1; j < d.sets.size(); j++) {
                    Group a = d.sets.get(i), b = d.sets.get(j);
                    if (a.isSequence && b.isSequence && a.tiles.equals(b.tiles)) {
                        yaku.add(new YakuHit("一盃口", 1));
                    }
                }
            }
        }

        if (hasSanshoku(allSets)) yaku.add(new YakuHit("三色同順", menzen ? 2 : 1));
        if (hasIttsuu(allSets)) yaku.add(new YakuHit("一氣通貫", menzen ? 2 : 1));

        if (everyGroupHasTerminalHonor && !allTriplets) {
            boolean pureTerminalOnly = allSets.stream().flatMap(g -> g.tiles.stream()).noneMatch(Tile::isHonor)
                    && !d.pair1.isHonor();
            yaku.add(new YakuHit(pureTerminalOnly ? "純全帶么九" : "混全帶么九", pureTerminalOnly ? (menzen ? 3 : 2) : (menzen ? 2 : 1)));
        }

        Tile.Suit onlySuit = onlyNumberSuit(allSets, d.pair1);
        if (onlySuit != null) {
            if (anyHonorPresent) {
                yaku.add(new YakuHit("混一色", menzen ? 3 : 2));
            } else {
                yaku.add(new YakuHit("清一色", menzen ? 6 : 5));
            }
        }

        long dragonTriplets = allSets.stream().filter(g -> !g.isSequence && g.tiles.get(0).suit == Tile.Suit.DRAGON).count();
        if (dragonTriplets == 2 && d.pair1.suit == Tile.Suit.DRAGON) yaku.add(new YakuHit("小三元", 2));

        if (yaku.isEmpty()) {
            result.valid = false;
            return result;
        }

        int doraHan = countDora(concealed, melds, ctx.doraTiles);
        int akaDoraHan = countAkaDora(concealed, melds);
        if (doraHan > 0) yaku.add(new YakuHit("寶牌", doraHan));
        if (akaDoraHan > 0) yaku.add(new YakuHit("赤寶牌", akaDoraHan));

        int totalHan = yaku.stream().mapToInt(y -> y.han).sum();
        int fu = computeFu(d, allSets, menzen, ctx.tsumo, pinfu);

        result.valid = true;
        result.yaku = yaku;
        result.totalHan = totalHan;
        result.fu = fu;
        result.points = computePoints(totalHan, fu, ctx.isDealer, ctx.tsumo);
        return result;
    }

    private static void addCommonSituational(List<YakuHit> yaku, WinContext ctx, boolean menzen) {
        if (ctx.doubleRiichi) {
            yaku.add(new YakuHit("兩立直", 2));
        } else if (ctx.riichi && menzen) {
            yaku.add(new YakuHit("立直", 1));
        }
        if (ctx.ippatsu && menzen && (ctx.riichi || ctx.doubleRiichi)) {
            yaku.add(new YakuHit("一發", 1));
        }
    }

    private static boolean isPinfuShape(Decomposition d, WinContext ctx) {
        if (d.pair1.suit == Tile.Suit.DRAGON) return false;
        if (d.pair1.suit == Tile.Suit.WIND && (d.pair1.number == ctx.seatWind || d.pair1.number == ctx.roundWind)) return false;
        return true;
    }

    private static boolean hasSanshoku(List<Group> allSets) {
        for (Group g : allSets) {
            if (!g.isSequence) continue;
            int startNum = g.tiles.get(0).number;
            boolean man = false, pin = false, sou = false;
            for (Group other : allSets) {
                if (!other.isSequence || other.tiles.get(0).number != startNum) continue;
                switch (other.tiles.get(0).suit) {
                    case MAN -> man = true;
                    case PIN -> pin = true;
                    case SOU -> sou = true;
                    default -> {}
                }
            }
            if (man && pin && sou) return true;
        }
        return false;
    }

    private static boolean hasIttsuu(List<Group> allSets) {
        for (Tile.Suit suit : new Tile.Suit[]{Tile.Suit.MAN, Tile.Suit.PIN, Tile.Suit.SOU}) {
            boolean has123 = false, has456 = false, has789 = false;
            for (Group g : allSets) {
                if (!g.isSequence || g.tiles.get(0).suit != suit) continue;
                int n = g.tiles.get(0).number;
                if (n == 1) has123 = true;
                if (n == 4) has456 = true;
                if (n == 7) has789 = true;
            }
            if (has123 && has456 && has789) return true;
        }
        return false;
    }

    private static Tile.Suit onlyNumberSuit(List<Group> allSets, Tile pair) {
        Tile.Suit suit = null;
        for (Group g : allSets) {
            for (Tile t : g.tiles) {
                if (!t.isNumber()) continue;
                if (suit == null) suit = t.suit;
                else if (suit != t.suit) return null;
            }
        }
        if (pair.isNumber()) {
            if (suit == null) suit = pair.suit;
            else if (suit != pair.suit) return null;
        }
        return suit;
    }

    private static int countDora(List<Tile> concealed, List<Meld> melds, List<Tile> doraTiles) {
        int count = 0;
        List<Tile> all = new ArrayList<>(concealed);
        for (Meld m : melds) all.addAll(m.tiles);
        for (Tile t : all) {
            for (Tile dora : doraTiles) {
                if (t.equals(dora)) count++;
            }
        }
        return count;
    }

    private static int countAkaDora(List<Tile> concealed, List<Meld> melds) {
        int count = 0;
        for (Tile t : concealed) if (t.red) count++;
        for (Meld m : melds) for (Tile t : m.tiles) if (t.red) count++;
        return count;
    }

    private static int computeFu(Decomposition d, List<Group> allSets, boolean menzen, boolean tsumo, boolean pinfu) {
        if (pinfu && tsumo) return 20; // fixed, no rounding needed
        int fu = 20;
        if (menzen && !tsumo) fu += 10;
        if (tsumo) fu += 2;
        for (Group g : allSets) {
            if (g.isSequence) continue;
            boolean terminalHonor = g.tiles.get(0).isTerminalOrHonor();
            fu += terminalHonor ? 4 : 2;
        }
        if (d.pair1.suit == Tile.Suit.DRAGON) fu += 2;
        return ((fu + 9) / 10) * 10;
    }

    private static long computePoints(int han, int fu, boolean dealer, boolean tsumo) {
        if (han >= 13) return dealer ? 48000 : 32000;
        if (han >= 11) return dealer ? 36000 : 24000;
        if (han >= 8) return dealer ? 24000 : 16000;
        if (han >= 6) return dealer ? 18000 : 12000;
        if (han >= 5) return dealer ? 12000 : 8000;
        long base = (long) fu * (1L << (2 + han));
        if (base > 2000) base = 2000;
        if (tsumo) {
            long nonDealerPay = roundUp100(dealer ? base * 2 : base);
            long dealerPay = roundUp100(dealer ? base * 2 : base * 2);
            return dealer ? nonDealerPay * 3 : nonDealerPay * 2 + dealerPay;
        }
        return roundUp100(base * (dealer ? 6 : 4));
    }

    private static long roundUp100(long v) {
        return ((v + 99) / 100) * 100;
    }
}
