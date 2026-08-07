package com.fkc.game.mahjong.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * The 136-tile wall. 14 tiles are set aside as the "dead wall" — the last
 * few of those are dora indicators, revealed as the game progresses.
 */
public class Wall {

    private static final int DEAD_WALL_SIZE = 14;

    private final Deque<Tile> liveWall = new ArrayDeque<>();
    private final List<Tile> deadWall = new ArrayList<>();
    private int revealedDoraCount = 1;

    public Wall(int redFiveCount) {
        this(redFiveCount, false);
    }

    /**
     * @param includeFlowers true for Taiwanese mode (144 tiles: the usual
     *                       136 plus the 8 season/plant flower tiles).
     *                       Riichi mode never uses this (false).
     */
    public Wall(int redFiveCount, boolean includeFlowers) {
        List<Tile> all = new ArrayList<>(includeFlowers ? 144 : 136);
        for (int type = 0; type < 34; type++) {
            for (int copy = 0; copy < 4; copy++) {
                all.add(Tile.fromTypeIndex(type));
            }
        }
        if (includeFlowers) {
            for (int seat = 1; seat <= 4; seat++) {
                all.add(Tile.seasonFlower(seat));
                all.add(Tile.plantFlower(seat));
            }
        }
        Collections.shuffle(all);

        // Mark some 5s (man/pin/sou) as red fives, per configured count each.
        // (Taiwanese mahjong traditionally has no red-five/dora concept, so
        // callers building a Taiwan wall should just pass redFiveCount = 0.)
        markRedFives(all, 4, redFiveCount);  // 5 man = type index 4
        markRedFives(all, 13, redFiveCount); // 5 pin = type index 13
        markRedFives(all, 22, redFiveCount); // 5 sou = type index 22

        Collections.shuffle(all);

        if (includeFlowers) {
            // Taiwanese mahjong has no dora dead-wall concept — every tile
            // (all 144) is available to draw.
            liveWall.addAll(all);
        } else {
            for (int i = 0; i < DEAD_WALL_SIZE; i++) {
                deadWall.add(all.remove(all.size() - 1));
            }
            liveWall.addAll(all);
        }
    }

    private void markRedFives(List<Tile> all, int typeIndex, int count) {
        int marked = 0;
        for (int i = 0; i < all.size() && marked < count; i++) {
            Tile t = all.get(i);
            if (t.typeIndex() == typeIndex && !t.red) {
                all.set(i, new Tile(t.suit, t.number, true));
                marked++;
            }
        }
    }

    public boolean isEmpty() {
        return liveWall.isEmpty();
    }

    public int remaining() {
        return liveWall.size();
    }

    public Tile draw() {
        return liveWall.pollFirst();
    }

    /** Dora indicator tiles currently revealed (first one is the base dora indicator). */
    public List<Tile> revealedDoraIndicators() {
        return deadWall.subList(0, Math.min(revealedDoraCount, deadWall.size()));
    }

    /** Called when a kan is declared, to flip the next dora indicator (kan-dora). */
    public void revealNextDoraIndicator() {
        if (revealedDoraCount < deadWall.size()) {
            revealedDoraCount++;
        }
    }

    /**
     * Actual dora tiles (the tile that comes "after" each indicator), computed
     * from the currently revealed indicators.
     */
    public List<Tile> currentDoraTiles() {
        List<Tile> result = new ArrayList<>();
        for (Tile indicator : revealedDoraIndicators()) {
            result.add(nextTile(indicator));
        }
        return result;
    }

    private Tile nextTile(Tile indicator) {
        return switch (indicator.suit) {
            case MAN, PIN, SOU -> {
                int next = indicator.number == 9 ? 1 : indicator.number + 1;
                yield new Tile(indicator.suit, next);
            }
            case WIND -> {
                int next = indicator.number == 4 ? 1 : indicator.number + 1;
                yield new Tile(Tile.Suit.WIND, next);
            }
            case DRAGON -> {
                int next = indicator.number == 3 ? 1 : indicator.number + 1;
                yield new Tile(Tile.Suit.DRAGON, next);
            }
            case FLOWER_SEASON, FLOWER_PLANT -> indicator; // dora indicators never used in Taiwanese mode
        };
    }
}
