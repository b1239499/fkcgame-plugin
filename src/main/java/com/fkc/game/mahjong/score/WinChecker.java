package com.fkc.game.mahjong.score;

import com.fkc.game.mahjong.model.Meld;
import com.fkc.game.mahjong.model.Tile;

import java.util.ArrayList;
import java.util.List;

public class WinChecker {

    /** Does concealedPlusCandidate (already includes the candidate tile) form a valid winning shape? */
    public static boolean isWinningShape(List<Tile> concealedPlusCandidate, List<Meld> melds, HandEvaluator.WinContext ctx) {
        HandEvaluator.WinResult r = HandEvaluator.evaluate(concealedPlusCandidate, melds, ctx);
        return r.valid;
    }

    /**
     * Given a 13-tile-equivalent concealed hand (i.e. hand.size() == 13 - 3*melds.size()),
     * returns every tile type that would complete the hand's SHAPE (tenpai
     * waits) — this deliberately ignores whether a yaku would actually be
     * available, since tenpai is a shape question, not a yaku question.
     * Empty if not tenpai.
     */
    public static List<Tile> tenpaiWaits(List<Tile> concealed, List<Meld> melds) {
        List<Tile> waits = new ArrayList<>();
        for (int type = 0; type < 34; type++) {
            Tile candidate = Tile.fromTypeIndex(type);
            List<Tile> test = new ArrayList<>(concealed);
            test.add(candidate);
            if (HandEvaluator.hasValidShape(test, melds)) {
                waits.add(candidate);
            }
        }
        return waits;
    }

    public static boolean isTenpai(List<Tile> concealed, List<Meld> melds) {
        return !tenpaiWaits(concealed, melds).isEmpty();
    }
}
