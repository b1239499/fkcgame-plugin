package com.fkc.game.mahjong.score;

import com.fkc.game.mahjong.model.Meld;
import com.fkc.game.mahjong.model.Tile;

import java.util.ArrayList;
import java.util.List;

/**
 * Scoring engine for Taiwanese (16-tile) mahjong. Winning shape is 5 sets
 * (順子/刻子) + 1 pair (17 tiles including the winning tile), as opposed to
 * Riichi's 4 sets + 1 pair.
 * <p>
 * <b>Important honesty note:</b> Taiwanese mahjong's 台 (tai) table is not
 * standardized the way Japanese Riichi's yaku list is — it genuinely varies
 * by region and even by table. The values baked in here are a commonly-cited
 * "reference" set (門清/自摸/平胡/對對胡/一色台/三元/四喜/暗刻/花牌 etc.),
 * gathered from multiple public references, but every table's exact numbers
 * are configurable in config.yml precisely because there's no single
 * "correct" answer — server owners should adjust to match their own house
 * rules if they differ.
 * <p>
 * NOT implemented: 十六不搭 (a rare scattered-hand special shape), 全求人/
 * 半求人/不求人 as separate categories (folded into 門清/自摸 handling),
 * and any "seven pairs"-style Taiwanese variant (not standard in the 16-tile
 * game the way chiitoitsu is standard in Riichi).
 */
public class TaiwanHandEvaluator {

    public static class TaiSettings {
        public int menzen = 1;
        public int tsumo = 1;
        public int menzenTsumoBonus = 1; // extra on top of menzen+tsumo when both apply (matches common "自摸門清=3台" citation)
        public int pingHu = 2;
        public int duiDuiHu = 4; // 對對胡/碰碰胡
        public int sanAnKe = 2;  // 三暗刻
        public int siAnKe = 5;   // 四暗刻
        public int wuAnKe = 16;  // 五暗刻
        public int hunYiSe = 4;  // 混一色
        public int qingYiSe = 8; // 清一色
        public int ziYiSe = 16;  // 字一色
        public int xiaoSanYuan = 4;
        public int daSanYuan = 8;
        public int xiaoSiXi = 8;
        public int daSiXi = 16;
        public int fengKe = 1;   // seat/round wind triplet, each
        public int jianKe = 1;   // dragon triplet, each
        public int gangBonus = 1; // per declared kan
        public int flowerEach = 1;
        public int flowerFullSetBonus = 2; // owning all 4 of one flower family (replaces the per-flower count for that family)
        public int flowerAllEightBonus = 8; // owning all 8 flowers
    }

    public static class TaiHit {
        public final String name;
        public final int tai;
        public TaiHit(String name, int tai) { this.name = name; this.tai = tai; }
    }

    public static class WinContext {
        public boolean tsumo;
        public int seatWind;
        public int roundWind;
        public boolean isDealer;
        public int kanCount;
        public int pointsPerTai = 100;
        public int dealerMultiplier = 2;
    }

    public static class WinResult {
        public boolean valid;
        public List<TaiHit> tai = new ArrayList<>();
        public int totalTai;
        public long points;
        public String summary() {
            if (!valid) return "沒有胡牌型";
            StringBuilder sb = new StringBuilder();
            for (TaiHit t : tai) sb.append(t.name).append("(").append(t.tai).append("台) ");
            sb.append("— 共 ").append(totalTai).append(" 台，").append(points).append(" 點");
            return sb.toString();
        }
    }

    public static WinResult evaluate(List<Tile> concealed, List<Meld> melds, List<Tile> flowers, WinContext ctx, TaiSettings settings) {
        WinResult result = new WinResult();
        int setsNeeded = 5 - melds.size();
        List<Decomposition> decomps = decompose(concealed, setsNeeded);
        if (decomps.isEmpty()) {
            result.valid = false;
            return result;
        }

        WinResult best = null;
        for (Decomposition d : decomps) {
            WinResult candidate = score(d, melds, flowers, ctx, settings);
            if (best == null || candidate.totalTai > best.totalTai) best = candidate;
        }
        return best;
    }

    // -----------------------------------------------------------
    // Shape decomposition (5 sets + pair) — same recursive approach as
    // HandEvaluator's, generalized to a configurable set count.
    // -----------------------------------------------------------

    private static class Group {
        List<Tile> tiles;
        boolean isSequence;
        Group(List<Tile> tiles, boolean isSequence) { this.tiles = tiles; this.isSequence = isSequence; }
    }

    private static class Decomposition {
        List<Group> sets = new ArrayList<>();
        Tile pair;
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
                    d.pair = sorted.get(i);
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
    private static boolean contains(List<Tile> list, Tile t) { return count(list, t) > 0; }
    private static int indexOf(List<Tile> list, Tile t) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).equals(t)) return i;
        return -1;
    }
    private static List<Tile> removeN(List<Tile> list, Tile t, int n) {
        List<Tile> copy = new ArrayList<>(list);
        for (int i = 0; i < n; i++) copy.remove(indexOf(copy, t));
        return copy;
    }

    // -----------------------------------------------------------
    // Tai (台) scoring for one decomposition
    // -----------------------------------------------------------

    private static WinResult score(Decomposition d, List<Meld> melds, List<Tile> flowers, WinContext ctx, TaiSettings s) {
        WinResult result = new WinResult();
        boolean menzen = melds.stream().allMatch(m -> m.type == Meld.Type.ANKAN);

        List<Group> allSets = new ArrayList<>(d.sets);
        for (Meld m : melds) {
            allSets.add(new Group(m.tiles.subList(0, Math.min(3, m.tiles.size())), m.type == Meld.Type.CHI));
        }

        List<TaiHit> tai = new ArrayList<>();

        boolean allTriplets = allSets.stream().allMatch(g -> !g.isSequence);
        boolean noHonors = allSets.stream().flatMap(g -> g.tiles.stream()).noneMatch(Tile::isHonor) && !d.pair.isHonor();
        boolean anyHonor = allSets.stream().flatMap(g -> g.tiles.stream()).anyMatch(Tile::isHonor) || d.pair.isHonor();
        boolean allSequences = allSets.stream().allMatch(g -> g.isSequence);

        // 門清 / 自摸 (with the combo bonus when both apply together)
        if (menzen && ctx.tsumo) {
            tai.add(new TaiHit("門清自摸", s.menzen + s.tsumo + s.menzenTsumoBonus));
        } else {
            if (menzen) tai.add(new TaiHit("門清", s.menzen));
            if (ctx.tsumo) tai.add(new TaiHit("自摸", s.tsumo));
        }

        // 平胡: all sequences, no honors anywhere, not self-drawn (per common definition).
        if (allSequences && noHonors && !ctx.tsumo) {
            tai.add(new TaiHit("平胡", s.pingHu));
        }

        if (allTriplets) tai.add(new TaiHit("對對胡", s.duiDuiHu));

        long concealedTriplets = allSets.stream().filter(g -> !g.isSequence).count()
                - melds.stream().filter(m -> !m.isConcealed() && m.type != Meld.Type.CHI).count();
        if (concealedTriplets >= 5) {
            // A 5-concealed-triplet hand is, by definition, also 對對胡 —
            // the unconditional "if (allTriplets)" check above already adds
            // that tai, so we only need to add the 五暗刻 bonus itself here
            // (not a second, duplicate 對對胡).
            tai.add(new TaiHit("五暗刻", s.wuAnKe));
        } else if (concealedTriplets == 4) {
            tai.add(new TaiHit("四暗刻", s.siAnKe));
        } else if (concealedTriplets == 3) {
            tai.add(new TaiHit("三暗刻", s.sanAnKe));
        }

        Tile.Suit onlySuit = onlyNumberSuit(allSets, d.pair);
        if (onlySuit != null) {
            if (anyHonor) tai.add(new TaiHit("混一色", s.hunYiSe));
            else tai.add(new TaiHit("清一色", s.qingYiSe));
        } else if (allHonorTiles(allSets, d.pair)) {
            tai.add(new TaiHit("字一色", s.ziYiSe));
        }

        long dragonTriplets = allSets.stream().filter(g -> !g.isSequence && g.tiles.get(0).suit == Tile.Suit.DRAGON).count();
        long windTriplets = allSets.stream().filter(g -> !g.isSequence && g.tiles.get(0).suit == Tile.Suit.WIND).count();
        if (windTriplets == 4) {
            tai.add(new TaiHit("大四喜", s.daSiXi));
        } else if (windTriplets == 3 && d.pair.suit == Tile.Suit.WIND) {
            tai.add(new TaiHit("小四喜", s.xiaoSiXi));
        }
        if (dragonTriplets == 3) {
            tai.add(new TaiHit("大三元", s.daSanYuan));
        } else if (dragonTriplets == 2 && d.pair.suit == Tile.Suit.DRAGON) {
            tai.add(new TaiHit("小三元", s.xiaoSanYuan));
        }

        for (Group g : allSets) {
            if (g.isSequence) continue;
            Tile t = g.tiles.get(0);
            if (t.suit == Tile.Suit.DRAGON) tai.add(new TaiHit("箭牌:" + t.label(), s.jianKe));
            if (t.suit == Tile.Suit.WIND && (t.number == ctx.seatWind || t.number == ctx.roundWind)) {
                tai.add(new TaiHit("風牌:" + t.label(), s.fengKe));
            }
        }

        if (ctx.kanCount > 0) tai.add(new TaiHit("槓", s.gangBonus * ctx.kanCount));

        // Flowers.
        long seasonCount = flowers.stream().filter(f -> f.suit == Tile.Suit.FLOWER_SEASON).count();
        long plantCount = flowers.stream().filter(f -> f.suit == Tile.Suit.FLOWER_PLANT).count();
        if (seasonCount == 4 && plantCount == 4) {
            tai.add(new TaiHit("滿花", s.flowerAllEightBonus));
        } else {
            if (seasonCount == 4) tai.add(new TaiHit("春夏秋冬花槓", s.flowerFullSetBonus));
            else for (Tile f : flowers) if (f.suit == Tile.Suit.FLOWER_SEASON) tai.add(new TaiHit("花:" + f.label(), s.flowerEach));
            if (plantCount == 4) tai.add(new TaiHit("梅蘭竹菊花槓", s.flowerFullSetBonus));
            else for (Tile f : flowers) if (f.suit == Tile.Suit.FLOWER_PLANT) tai.add(new TaiHit("花:" + f.label(), s.flowerEach));
        }

        int totalTai = tai.stream().mapToInt(t -> t.tai).sum();
        result.valid = true;
        result.tai = tai;
        result.totalTai = totalTai;
        result.points = (long) totalTai * ctx.pointsPerTai * (ctx.isDealer ? ctx.dealerMultiplier : 1);
        return result;
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

    private static boolean allHonorTiles(List<Group> allSets, Tile pair) {
        boolean setsOk = allSets.stream().flatMap(g -> g.tiles.stream()).allMatch(Tile::isHonor);
        return setsOk && pair.isHonor();
    }
}
