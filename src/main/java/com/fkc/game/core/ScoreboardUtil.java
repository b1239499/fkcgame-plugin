package com.fkc.game.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;
import java.util.logging.Logger;

/**
 * Shows a live-updating sidebar (the scoreboard panel on the right side of
 * the screen) for a player, refreshed by calling {@link #show} again
 * whenever the game state changes.
 * <p>
 * <b>Confirmed caveat (not just a hypothetical one anymore):</b> on at
 * least one real Folia server, {@code ScoreboardManager.getNewScoreboard()}
 * throws {@code UnsupportedOperationException} outright. Every entry point
 * here is therefore defensively wrapped — a scoreboard failure must never
 * be allowed to propagate up into actual game logic (it did once, and it
 * broke {@code /uno start} entirely). Once a failure is seen, this quietly
 * disables itself for the rest of the server's uptime rather than
 * repeatedly trying (and repeatedly failing) on every single game event.
 */
public class ScoreboardUtil {

    private static final String OBJECTIVE_NAME = "fkcgame_side";
    private static volatile boolean disabled = false;

    /** Replaces the player's sidebar with the given title + lines (first line = top). Silently does nothing if the server doesn't support this. */
    public static void show(Player player, String title, List<String> lines) {
        if (disabled) return;
        try {
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
        } catch (Throwable t) {
            disableAndWarn(t);
        }
    }

    /** Restores the player's normal (server default) scoreboard, removing our sidebar. Silently does nothing if the server doesn't support this. */
    public static void clear(Player player) {
        if (disabled) return;
        try {
            player.setScoreboard(Bukkit.getServer().getScoreboardManager().getMainScoreboard());
        } catch (Throwable t) {
            disableAndWarn(t);
        }
    }

    private static void disableAndWarn(Throwable t) {
        disabled = true;
        Logger.getLogger("FkcGame").warning("[FkcGame] 即時計分板功能在這台伺服器上不受支援（"
                + t.getClass().getSimpleName() + ": " + t.getMessage()
                + "），已自動停用這個功能，不影響麻將/UNO 其他功能正常運作。");
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
