package com.fkc.game.mahjong.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class GamePlayer {

    public final UUID uuid;
    public final String name;
    public final boolean isBot;

    public final List<Tile> hand = new ArrayList<>();
    public final List<Meld> melds = new ArrayList<>();
    public final List<Tile> discards = new ArrayList<>();
    /** Flower tiles set aside face-up (Taiwanese mode only; empty in Riichi mode). */
    public final List<Tile> flowers = new ArrayList<>();

    public boolean riichi = false;
    public boolean doubleRiichi = false;
    public boolean ippatsuEligible = false;
    public boolean menzen = true; // still concealed (no open melds)
    /** "託管" — when true, the table auto-declares tsumo/ron for this player the instant they're eligible, instead of waiting for a manual click. */
    public boolean autoWin = false;
    public int seatWind = 1; // 1=East 2=South 3=West 4=North, rotates each hand
    public int score = 25000;
    public boolean ready = false;

    public GamePlayer(UUID uuid, String name, boolean isBot) {
        this.uuid = uuid;
        this.name = name;
        this.isBot = isBot;
    }

    public void sortHand() {
        Collections.sort(hand);
    }

    public boolean isTenpai() {
        // Cheap check delegated to HandEvaluator by callers; placeholder kept
        // false here so callers must invoke the evaluator explicitly.
        return false;
    }
}
