package com.fkc.game.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;

/**
 * Shows a live-updating sidebar (the scoreboard panel on the right side of
 * the screen) for a player, refreshed by calling {@link #show} again
 * whenever the game state changes.
 * <p>
 * <b>Honest caveat:</b> this is the one part of the plugin I can't fully
 * verify in a sandbox the way the game-logic engines were tested — the
 * Scoreboard API has shifted its recommended method signatures across
 * Minecraft versions (legacy String-based objective registration vs newer
 * Criteria/Component-based overloads), and I don't have a live server to
 * confirm rendering behavior against. This uses the older, long-deprecated-
 * but-still-present String-based {@code registerNewObjective} overload
 * specifically because it has stayed available across more versions than
 * the newer ones, minimizing the chance this breaks on your server version
 * — but please actually test it and report back what you see.
 */
public class ScoreboardUtil {

    private static final String OBJECTIVE_NAME = "fkcgame_side";

    /** Replaces the player's sidebar with the given title + lines (first line = top). */
    public static void show(Player player, String title, List<String> lines) {
        Scoreboard board = Bukkit.getServer().getScoreboardManager().getNewScoreboard();
        String safeTitle = title.length() > 32 ? title.substring(0, 32) : title;
        Objective obj = board.registerNewObjective(OBJECTIVE_NAME, "dummy", safeTitle);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        int score = lines.size();
        for (String line : lines) {
            obj.getScore(uniqueEntry(line, score)).setScore(score);
            score--;
        }
        player.setScoreboard(board);
    }

    /** Restores the player's normal (server default) scoreboard, removing our sidebar. */
    public static void clear(Player player) {
        player.setScoreboard(Bukkit.getServer().getScoreboardManager().getMainScoreboard());
    }

    /**
     * Scoreboard entries must be unique strings (two identical lines would
     * collide into one). Pad with an invisible legacy color code so
     * duplicate or blank lines still render as separate rows. Also trims
     * to a conservative length, since very old clients cap sidebar line
     * width — modern clients don't, but there's no reason to test that
     * limit here.
     */
    private static String uniqueEntry(String text, int index) {
        String suffix = ChatColor.values()[index % ChatColor.values().length].toString();
        String base = text.length() > 40 ? text.substring(0, 40) : text;
        return base + suffix;
    }
}
