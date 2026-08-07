package com.fkc.game.uno.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UnoPlayer {

    public final UUID uuid;
    public final String name;
    public final boolean isBot;

    public final List<Card> hand = new ArrayList<>();
    public int score = 0;
    public boolean ready = false;
    /** Set true once this player has called "UNO" after being left with exactly one card. */
    public boolean unoCalled = false;

    public UnoPlayer(UUID uuid, String name, boolean isBot) {
        this.uuid = uuid;
        this.name = name;
        this.isBot = isBot;
    }
}
