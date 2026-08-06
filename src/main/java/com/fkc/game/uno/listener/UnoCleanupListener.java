package com.fkc.game.uno.listener;

import com.fkc.game.uno.game.UnoTable;
import com.fkc.game.uno.game.UnoTableManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class UnoCleanupListener implements Listener {

    private final UnoTableManager manager;

    public UnoCleanupListener(UnoTableManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UnoTable table = manager.tableOf(event.getPlayer().getUniqueId());
        if (table == null) return;
        if (table.phase == UnoTable.Phase.WAITING) {
            manager.leave(event.getPlayer().getUniqueId());
        }
        // If PLAYING, leave their seat intact so they can reconnect and
        // keep playing, same policy as the mahjong module.
    }
}
