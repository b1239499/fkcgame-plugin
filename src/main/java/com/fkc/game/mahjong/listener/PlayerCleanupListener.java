package com.fkc.game.mahjong.listener;

import com.fkc.game.mahjong.game.MahjongTable;
import com.fkc.game.mahjong.game.TableManager;
import com.fkc.game.mahjong.model.GamePlayer;
import com.fkc.game.mahjong.model.Tile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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

    /**
     * A player reconnecting mid-game has no way to know what happened while
     * they were offline until it's literally their turn again — this
     * proactively resyncs them (current hand, whose turn, last discard,
     * dora) instead of leaving them to guess or manually run /mahjong info.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table == null || table.phase != MahjongTable.Phase.PLAYING) return;
        GamePlayer gp = table.get(player.getUniqueId());
        if (gp == null) return;

        player.sendMessage(Component.text("歡迎回來！你的麻將牌局仍在進行中，目前狀態：", NamedTextColor.GOLD));

        var current = table.getCurrentPlayer();
        if (current != null) {
            player.sendMessage(Component.text("目前輪到: " + current.name
                    + (current.uuid.equals(player.getUniqueId()) ? "（就是你！）" : ""), NamedTextColor.AQUA));
        }
        Tile lastDiscard = table.getLastDiscard();
        var lastDiscarder = table.getLastDiscarder();
        if (lastDiscard != null && lastDiscarder != null) {
            player.sendMessage(Component.text("最新棄牌: " + lastDiscard.display() + "（" + lastDiscarder.name + "打出）", NamedTextColor.AQUA));
        }
        player.sendMessage(Component.text("牌山剩餘: " + table.getRemainingTiles() + "張", NamedTextColor.AQUA));
        Component hand = Component.text("你的手牌: ", NamedTextColor.YELLOW);
        for (Tile t : gp.hand) hand = hand.append(Component.text(t.display() + " "));
        player.sendMessage(hand);

        // Re-push their sidebar too — reconnecting gives them a fresh
        // scoreboard object, so whatever they had showing before is gone
        // until something triggers a refresh.
        table.refreshSidebars();
    }
}
