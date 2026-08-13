package com.fkc.game.uno.listener;

import com.fkc.game.uno.game.UnoTable;
import com.fkc.game.uno.game.UnoTableManager;
import com.fkc.game.uno.model.Card;
import com.fkc.game.uno.model.UnoPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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

    /** Proactively resyncs a reconnecting player instead of leaving them to guess or manually run /uno info. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UnoTable table = manager.tableOf(player.getUniqueId());
        if (table == null || table.phase != UnoTable.Phase.PLAYING) return;
        UnoPlayer up = table.get(player.getUniqueId());
        if (up == null) return;

        player.sendMessage(Component.text("歡迎回來！你的 UNO 牌局仍在進行中，目前狀態：", NamedTextColor.GOLD));

        Card top = table.getTopCard();
        Card.Color color = table.getCurrentColor();
        if (top != null) player.sendMessage(Component.text("目前牌面: " + top.display(), NamedTextColor.AQUA));
        player.sendMessage(Component.text("牌堆剩餘: " + table.getRemainingCards() + "張", NamedTextColor.AQUA));
        var current = table.getCurrentPlayer();
        if (current != null) {
            player.sendMessage(Component.text("目前輪到: " + current.name
                    + (current.uuid.equals(player.getUniqueId()) ? "（就是你！）" : ""), NamedTextColor.AQUA));
        }
        Component hand = Component.text("你的手牌: ", NamedTextColor.YELLOW);
        for (Card c : up.hand) hand = hand.append(Component.text(c.display() + " "));
        player.sendMessage(hand);

        table.refreshSidebars();
    }
}
