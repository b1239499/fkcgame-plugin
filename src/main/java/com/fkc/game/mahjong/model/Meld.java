package com.fkc.game.mahjong.model;

import java.util.List;
import java.util.UUID;

public class Meld {

    public enum Type { CHI, PON, ANKAN, MINKAN, KAKAN }

    public final Type type;
    public final List<Tile> tiles;
    /** Whose discard this was called from; null for a concealed (an-)kan. */
    public final UUID calledFromPlayer;

    public Meld(Type type, List<Tile> tiles, UUID calledFromPlayer) {
        this.type = type;
        this.tiles = tiles;
        this.calledFromPlayer = calledFromPlayer;
    }

    public boolean isKan() {
        return type == Type.ANKAN || type == Type.MINKAN || type == Type.KAKAN;
    }

    public boolean isConcealed() {
        return type == Type.ANKAN;
    }

    public String display() {
        StringBuilder sb = new StringBuilder();
        for (Tile t : tiles) {
            sb.append(t.display());
        }
        return sb.toString();
    }
}
