package com.fkc.game.mahjong.model;

public class GameSettings {
    public enum GameLength { EAST_ONLY, EAST_SOUTH }
    public enum RuleSet { RIICHI, TAIWANESE }

    public RuleSet ruleSet = RuleSet.RIICHI;

    // --- Riichi-mode settings ---
    public GameLength gameLength = GameLength.EAST_ONLY;
    public int minHan = 1;
    public int redFiveCountPerSuit = 1;
    public int thinkingTimeSeconds = 15;
    public int startingScore = 25000;

    // --- Taiwanese-mode settings ---
    public int minTai = 1;
    public int pointsPerTai = 100;
    public int taiwanStartingScore = 1000;
    public int dealerMultiplier = 2;
}
