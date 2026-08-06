package com.fkc.game.uno.command;

import com.fkc.game.uno.game.UnoTable;
import com.fkc.game.uno.game.UnoTableManager;
import com.fkc.game.uno.model.Card;
import com.fkc.game.uno.model.UnoPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class UnoCommand implements CommandExecutor {

    private final UnoTableManager manager;
    private final org.bukkit.plugin.Plugin plugin;
    private final com.fkc.game.uno.econ.EconomyHook economy;
    private final java.util.Map<UUID, Long> pendingDestroyConfirm = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DESTROY_CONFIRM_WINDOW_MS = 30_000;

    public UnoCommand(UnoTableManager manager, org.bukkit.plugin.Plugin plugin, com.fkc.game.uno.econ.EconomyHook economy) {
        this.manager = manager;
        this.plugin = plugin;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("這個指令只能由玩家使用。");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text("用法: /uno <create|join|leave|ready|start|bot|destroy|info|list|action>", NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create", "開桌", "开桌", "建桌" -> handleCreate(player);
            case "join", "加入" -> handleJoin(player, args);
            case "leave", "離開", "离开", "退出" -> handleLeave(player);
            case "ready", "準備", "准备" -> handleReady(player);
            case "start", "開始", "开始" -> handleStart(player);
            case "bot", "電腦", "电脑", "加電腦", "加电脑" -> handleBot(player);
            case "reload", "重載", "重载", "重新載入" -> handleReload(player);
            case "destroy", "解散" -> handleDestroy(player, args);
            case "info", "資訊", "资讯", "查看" -> handleInfo(player);
            case "list", "列表", "清單", "清单" -> handleList(player);
            case "action" -> handleAction(player, args);
            default -> handleShorthand(player, args);
        }
        return true;
    }

    private void handleCreate(Player player) {
        if (manager.tableOf(player.getUniqueId()) != null) {
            player.sendMessage(Component.text("你已經在一張牌桌了，先 /uno leave 離開。", NamedTextColor.RED));
            return;
        }

        String requiredPermission = plugin.getConfig().getString("uno.table-creation.permission", "");
        if (requiredPermission != null && !requiredPermission.isBlank() && !player.hasPermission(requiredPermission)) {
            player.sendMessage(Component.text("你沒有權限開桌。", NamedTextColor.RED));
            return;
        }

        double fee = plugin.getConfig().getDouble("uno.table-creation.fee", 0);
        if (fee > 0) {
            if (!economy.isAvailable()) {
                player.sendMessage(Component.text("開桌費設定已啟用，但伺服器沒有偵測到 Vault 經濟系統，暫時免費開桌。", NamedTextColor.GRAY));
            } else if (!economy.withdraw(player, fee)) {
                player.sendMessage(Component.text("餘額不足，開桌需要 " + fee + " 元。", NamedTextColor.RED));
                return;
            } else {
                player.sendMessage(Component.text("已扣除開桌費 " + fee + " 元。", NamedTextColor.GRAY));
            }
        }

        manager.create(player.getUniqueId(), player.getName());
        player.sendMessage(Component.text("已建立 UNO 牌桌（2-6 人），等待其他玩家加入。使用 /uno bot 可以加入電腦玩家補位。", NamedTextColor.GREEN));
    }

    private void handleJoin(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("用法: /uno join <桌主名稱>", NamedTextColor.YELLOW));
            return;
        }
        Player host = Bukkit.getPlayerExact(args[1]);
        if (host == null) {
            player.sendMessage(Component.text("找不到該桌主或桌主不在線上。", NamedTextColor.RED));
            return;
        }
        if (manager.tableOf(player.getUniqueId()) != null) {
            player.sendMessage(Component.text("你已經在一張牌桌了，先 /uno leave 離開。", NamedTextColor.RED));
            return;
        }
        boolean ok = manager.join(host.getUniqueId(), player.getUniqueId(), player.getName());
        player.sendMessage(ok
                ? Component.text("已加入 " + host.getName() + " 的 UNO 牌桌。", NamedTextColor.GREEN)
                : Component.text("加入失敗（桌子不存在、已滿 6 人或已開局）。", NamedTextColor.RED));
    }

    private void handleLeave(Player player) {
        UnoTable table = manager.tableOf(player.getUniqueId());
        if (table != null && table.phase == UnoTable.Phase.PLAYING) {
            player.sendMessage(Component.text("遊戲進行中無法離開，請等對局結束，或請桌主解散牌桌。", NamedTextColor.RED));
            return;
        }
        manager.leave(player.getUniqueId());
        player.sendMessage(Component.text("已離開 UNO 牌桌。", NamedTextColor.YELLOW));
    }

    private void handleReady(Player player) {
        UnoTable table = manager.tableOf(player.getUniqueId());
        if (table == null) { player.sendMessage(Component.text("你不在任何牌桌。", NamedTextColor.RED)); return; }
        table.toggleReady(player.getUniqueId());
        UnoPlayer up = table.get(player.getUniqueId());
        player.sendMessage(Component.text(up.ready ? "你已準備。" : "你取消了準備。", NamedTextColor.YELLOW));
    }

    private void handleStart(Player player) {
        UnoTable table = manager.tableOf(player.getUniqueId());
        if (table == null) { player.sendMessage(Component.text("你不在任何牌桌。", NamedTextColor.RED)); return; }
        if (!table.id.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("只有桌主能開始遊戲。", NamedTextColor.RED));
            return;
        }
        if (!table.canStart()) {
            player.sendMessage(Component.text("需要至少 2 人且全部準備完成才能開始（可用 /uno bot 補電腦位）。", NamedTextColor.RED));
            return;
        }
        table.startGame();
    }

    private void handleBot(Player player) {
        UnoTable table = manager.tableOf(player.getUniqueId());
        if (table == null || !table.id.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("只有桌主能加入電腦玩家。", NamedTextColor.RED));
            return;
        }
        if (table.phase != UnoTable.Phase.WAITING) {
            player.sendMessage(Component.text("遊戲已開始，無法加入電腦玩家。", NamedTextColor.RED));
            return;
        }
        boolean added = table.addPlayer(UUID.randomUUID(), "電腦" + (table.players.size()), true);
        player.sendMessage(added
                ? Component.text("已加入一位電腦玩家。", NamedTextColor.GREEN)
                : Component.text("桌子已滿（最多 6 人）。", NamedTextColor.RED));
    }

    private void handleReload(Player player) {
        if (!player.isOp() && !player.hasPermission("uno.reload")) {
            player.sendMessage(Component.text("你沒有權限重新載入設定。", NamedTextColor.RED));
            return;
        }
        plugin.reloadConfig();
        player.sendMessage(Component.text("已重新載入 config.yml。注意：只有之後新開的牌桌會套用新設定，"
                + "已經在等待中或進行中的桌子不會回頭改變。", NamedTextColor.GREEN));
    }

    private void handleDestroy(Player player, String[] args) {
        UnoTable table = manager.tableOf(player.getUniqueId());
        if (table == null || !table.id.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("只有桌主能解散牌桌。", NamedTextColor.RED));
            return;
        }

        boolean confirming = args.length > 1
                && (args[1].equalsIgnoreCase("confirm") || args[1].equals("確認") || args[1].equals("确认"));

        if (table.phase == UnoTable.Phase.PLAYING) {
            if (!confirming) {
                pendingDestroyConfirm.put(table.id, System.currentTimeMillis());
                player.sendMessage(Component.text("⚠ 遊戲正在進行中，解散會讓所有玩家的手牌/戰績直接消失。", NamedTextColor.RED));
                player.sendMessage(Component.text("[確認解散]", NamedTextColor.RED)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/uno destroy confirm")));
                return;
            }
            Long requestedAt = pendingDestroyConfirm.remove(table.id);
            if (requestedAt == null || System.currentTimeMillis() - requestedAt > DESTROY_CONFIRM_WINDOW_MS) {
                player.sendMessage(Component.text("確認已逾時，請重新輸入 /uno destroy。", NamedTextColor.RED));
                return;
            }
        }

        manager.destroy(table.id);
        player.sendMessage(Component.text("已解散 UNO 牌桌。", NamedTextColor.YELLOW));
    }

    private void handleInfo(Player player) {
        UnoTable table = manager.tableOf(player.getUniqueId());
        if (table == null) { player.sendMessage(Component.text("你不在任何牌桌。", NamedTextColor.RED)); return; }
        player.sendMessage(Component.text("=== UNO 牌桌資訊 ===", NamedTextColor.GOLD));
        for (UnoPlayer p : table.players) {
            player.sendMessage(Component.text((p.isBot ? "[電腦] " : "") + p.name
                    + (table.phase == UnoTable.Phase.WAITING ? (p.ready ? " (已準備)" : " (未準備)")
                        : " " + p.score + "分" + (p.hand.size() == 1 && p.unoCalled ? " (UNO!)" : " (剩 " + p.hand.size() + " 張)")),
                    NamedTextColor.WHITE));
        }
        if (table.phase == UnoTable.Phase.PLAYING) {
            var current = table.getCurrentPlayer();
            if (current != null) {
                player.sendMessage(Component.text("目前輪到: " + current.name
                        + (current.uuid.equals(player.getUniqueId()) ? "（就是你！）" : ""), NamedTextColor.AQUA));
            }
            UnoPlayer me = table.get(player.getUniqueId());
            if (me != null) {
                Component hand = Component.text("你的手牌: ", NamedTextColor.YELLOW);
                for (Card c : me.hand) hand = hand.append(Component.text(c.display() + " "));
                player.sendMessage(hand);
            }
        }
    }

    private void handleList(Player player) {
        var tables = manager.all();
        if (tables.isEmpty()) {
            player.sendMessage(Component.text("目前沒有任何 UNO 牌桌。", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text("=== UNO 牌桌列表 ===", NamedTextColor.GOLD));
        tables.values().stream()
                .sorted((a, b) -> Long.compare(a.createdAt, b.createdAt))
                .forEach(t -> player.sendMessage(Component.text(t.players.get(0).name + " 的桌子 — "
                        + t.players.size() + "/6 人 — " + t.phase, NamedTextColor.WHITE)));
    }

    private void handleAction(Player player, String[] args) {
        UnoTable table = manager.tableOf(player.getUniqueId());
        if (table == null || table.phase != UnoTable.Phase.PLAYING) return;
        UnoPlayer up = table.get(player.getUniqueId());
        if (up == null) return;
        if (args.length < 2) return;

        switch (args[1].toLowerCase()) {
            case "play" -> {
                if (args.length < 3) return;
                Card cardType = Card.parse(args[2]);
                if (cardType == null) return;
                Card actual = findInHand(up, cardType);
                if (actual == null) return;
                Card.Color chosen = null;
                if (args.length > 3) {
                    try { chosen = Card.Color.valueOf(args[3].toUpperCase()); } catch (IllegalArgumentException ignored) {}
                }
                String error = table.playCard(up, actual, chosen);
                if (error != null) player.sendMessage(Component.text(error, NamedTextColor.RED));
            }
            case "wildcolor" -> {
                if (args.length < 3) return;
                Card cardType = Card.parse(args[2]);
                if (cardType == null) return;
                Card actual = findInHand(up, cardType);
                if (actual != null) table.promptColorChoice(up, actual);
            }
            case "draw" -> {
                String error = table.drawCard(up);
                if (error != null) player.sendMessage(Component.text(error, NamedTextColor.RED));
            }
            case "pass" -> {
                String error = table.pass(up);
                if (error != null) player.sendMessage(Component.text(error, NamedTextColor.RED));
            }
            case "calluno" -> table.callUno(up);
            case "catch" -> {
                if (args.length < 3) return;
                Player targetBukkit = Bukkit.getPlayerExact(args[2]);
                if (targetBukkit == null) return;
                UnoPlayer target = table.get(targetBukkit.getUniqueId());
                if (target == null) return;
                String error = table.catchUno(up, target);
                if (error != null) player.sendMessage(Component.text(error, NamedTextColor.GRAY));
            }
            default -> {}
        }
    }

    /**
     * Lets players type short top-level commands like "/uno 摸" instead of
     * "/uno action draw". Rewrites into the equivalent action args and
     * delegates to handleAction so there's only one place that validates
     * and executes each move.
     */
    private void handleShorthand(Player player, String[] args) {
        String token = args[0];
        String param1 = args.length > 1 ? args[1] : null;
        String param2 = args.length > 2 ? args[2] : null;

        String[] rewritten = switch (token) {
            case "出", "play" -> param1 == null ? null
                    : (param2 != null ? new String[]{"action", "play", param1, param2} : new String[]{"action", "play", param1});
            case "摸", "摸牌", "draw" -> new String[]{"action", "draw"};
            case "跳過", "跳过", "過", "过", "pass" -> new String[]{"action", "pass"};
            case "喊uno", "喊UNO", "uno" -> new String[]{"action", "calluno"};
            case "抓", "catch" -> param1 == null ? null : new String[]{"action", "catch", param1};
            default -> null;
        };

        if (rewritten == null) {
            player.sendMessage(Component.text("未知的子指令。", NamedTextColor.RED));
            return;
        }
        handleAction(player, rewritten);
    }

    private Card findInHand(UnoPlayer p, Card type) {
        for (Card c : p.hand) {
            if (c.color == type.color && c.type == type.type && c.number == type.number) return c;
        }
        return null;
    }
}
