package com.fkc.game.uno.model;

public class UnoSettings {
    public int startingHandSize = 7;
    public int targetScore = 500;
    public int thinkingTimeSeconds = 30;
    /** How many cards you draw as a penalty if someone catches you not having called "UNO" at 1 card. */
    public int unoCatchPenalty = 2;
}
