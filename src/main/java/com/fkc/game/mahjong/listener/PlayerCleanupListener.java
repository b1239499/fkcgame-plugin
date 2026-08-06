package com.fkc.game.mahjong.listener;

import com.fkc.game.mahjong.game.MahjongTable;
import com.fkc.game.mahjong.game.TableManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerCleanupListener implements Listener {

    private final TableManager manager;

    public PlayerCleanupListener(TableManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        MahjongTable table = manager.tableOf(event.getPlayer().getUniqueId());
        if (table == null) return;
        if (table.phase == MahjongTable.Phase.WAITING) {
            manager.leave(event.getPlayer().getUniqueId());
        }
        // If the game is already PLAYING, we deliberately leave the player's
        // seat intact (their hand, score, etc. all stay put) so they can
        // reconnect and keep playing instead of losing their spot mid-game.
    }
}
