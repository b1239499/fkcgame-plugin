package com.fkc.game.mahjong.game;

import com.fkc.game.mahjong.model.Tile;

import java.util.List;
import java.util.UUID;

public class PossibleCall {
    public enum Type { RON, PON, MINKAN, CHI }

    public final UUID player;
    public final Type type;
    /** For CHI, the two hand tiles used alongside the discard. Empty for others. */
    public final List<Tile> handTilesUsed;

    public PossibleCall(UUID player, Type type, List<Tile> handTilesUsed) {
        this.player = player;
        this.type = type;
        this.handTilesUsed = handTilesUsed;
    }

    public String label() {
        return switch (type) {
            case RON -> "胡";
            case PON -> "碰";
            case MINKAN -> "槓";
            case CHI -> "吃" + (handTilesUsed.isEmpty() ? "" : handTilesUsed.get(0).label() + handTilesUsed.get(1).label());
        };
    }
}
