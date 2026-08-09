package com.fkc.game.mahjong.command;

import com.fkc.game.mahjong.game.MahjongTable;
import com.fkc.game.mahjong.game.TableManager;
import com.fkc.game.mahjong.model.GamePlayer;
import com.fkc.game.mahjong.model.Tile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MahjongCommand implements CommandExecutor, TabCompleter {

    private final TableManager manager;
    private final org.bukkit.plugin.Plugin plugin;
    private final com.fkc.game.mahjong.econ.EconomyHook economy;
    private final java.util.Map<UUID, Long> pendingDestroyConfirm = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DESTROY_CONFIRM_WINDOW_MS = 30_000;

    public MahjongCommand(TableManager manager, org.bukkit.plugin.Plugin plugin, com.fkc.game.mahjong.econ.EconomyHook economy) {
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
            player.sendMessage(Component.text("用法: /mahjong <create|join|leave|ready|start|bot|destroy|info|list|action>", NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create", "開桌", "开桌", "建桌" -> handleCreate(player, args);
            case "join", "加入" -> handleJoin(player, args);
            case "leave", "離開", "离开", "退出" -> handleLeave(player);
            case "ready", "準備", "准备" -> handleReady(player);
            case "start", "開始", "开始" -> handleStart(player);
            case "bot", "電腦", "电脑", "加電腦", "加电脑" -> handleBot(player);
            case "destroy", "解散" -> handleDestroy(player, args);
            case "info", "資訊", "资讯", "查看" -> handleInfo(player);
            case "list", "列表", "清單", "清单" -> handleList(player);
            case "exchange", "換三張", "换三张" -> handleExchangeToggle(player);
            case "reload", "重載", "重载", "重新載入" -> handleReload(player);
            case "rank", "排行", "排行榜" -> handleRank(player, args);
            case "sidebar", "面板", "側邊欄", "侧边栏" -> handleSidebarToggle(player);
            case "tenpai", "聽牌", "听牌" -> handleTenpai(player);
            case "auto", "託管", "托管" -> handleAutoWin(player);
            case "tiles", "圖示", "图示", "切換牌面" -> handleTileDisplayToggle(player);
            case "action" -> handleAction(player, args);
            default -> handleShorthand(player, args);
        }
        return true;
    }

    private void handleExchangeToggle(Player player) {
        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table == null || !table.id.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("只有桌主能設定換三張。", NamedTextColor.RED));
            return;
        }
        if (table.phase != MahjongTable.Phase.WAITING) {
            player.sendMessage(Component.text("遊戲已開始，無法更改此設定。", NamedTextColor.RED));
            return;
        }
        table.exchangeThreeEnabled = !table.exchangeThreeEnabled;
        player.sendMessage(Component.text("換三張已" + (table.exchangeThreeEnabled ? "開啟" : "關閉") + "。", NamedTextColor.YELLOW));
    }

    private void handleReload(Player player) {
        if (!player.isOp() && !player.hasPermission("mahjong.reload")) {
            player.sendMessage(Component.text("你沒有權限重新載入設定。", NamedTextColor.RED));
            return;
        }
        plugin.reloadConfig();
        plugin.getLogger().info("[mahjong] config.yml 已由 " + player.getName() + " 重新載入。");
        player.sendMessage(Component.text("已重新載入 config.yml。注意：只有之後新開的牌桌會套用新設定，"
                + "已經在等待中或進行中的桌子不會回頭改變。", NamedTextColor.GREEN));
    }

    private void handleRank(Player player, String[] args) {
        var ruleSet = parseRuleSet(args.length > 1 ? args[1] : null);
        var leaderboard = manager.leaderboardFor(ruleSet);
        String title = ruleSet == com.fkc.game.mahjong.model.GameSettings.RuleSet.TAIWANESE ? "台灣麻將" : "日本立直麻將";
        player.sendMessage(Component.text("=== " + title + "排行榜（前 10 名） ===", NamedTextColor.GOLD));
        var top = leaderboard.top(10);
        if (top.isEmpty()) {
            player.sendMessage(Component.text("目前還沒有任何有效對局紀錄（電腦局不計分）。", NamedTextColor.GRAY));
            return;
        }
        int rank = 1;
        for (var entry : top) {
            player.sendMessage(Component.text(rank + ". " + entry.name + "  累積 " + entry.score
                    + " 點（" + entry.matches + " 場）", NamedTextColor.WHITE));
            rank++;
        }
        player.sendMessage(Component.text("查其他規則排行榜: /mahjong rank riichi 或 /mahjong rank taiwan", NamedTextColor.GRAY));
    }

    private void handleSidebarToggle(Player player) {
        boolean serverDefault = plugin.getConfig().getBoolean("mahjong.sidebar-enabled", true);
        boolean nowEnabled = manager.sidebarPrefs().toggle(player.getUniqueId(), serverDefault);
        player.sendMessage(Component.text(nowEnabled ? "已開啟你的即時計分板。" : "已關閉你的即時計分板。", NamedTextColor.YELLOW));

        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table == null || table.phase != MahjongTable.Phase.PLAYING) return;
        if (nowEnabled) {
            table.refreshSidebars();
        } else {
            com.fkc.game.core.ScoreboardUtil.clear(player);
        }
    }

    private void handleTenpai(Player player) {
        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table == null || table.phase != MahjongTable.Phase.PLAYING) {
            player.sendMessage(Component.text("你目前沒有在進行中的牌局。", NamedTextColor.RED));
            return;
        }
        GamePlayer gp = table.get(player.getUniqueId());
        if (gp == null) return;

        int waitingSize = table.waitingHandSize(gp);
        if (gp.hand.size() == waitingSize) {
            List<Tile> waits = table.tenpaiWaits(gp.hand, gp.melds);
            if (waits.isEmpty()) {
                player.sendMessage(Component.text("目前沒有聽牌。", NamedTextColor.GRAY));
            } else {
                Component msg = Component.text("你現在聽牌，等待: ", NamedTextColor.GOLD);
                for (Tile t : waits) msg = msg.append(Component.text(table.render(t, player.getUniqueId()) + " "));
                player.sendMessage(msg);
            }
        } else if (gp.hand.size() == waitingSize + 1) {
            var options = table.discardOptionsForTenpai(gp);
            if (options.isEmpty()) {
                player.sendMessage(Component.text("目前手牌打哪張都不會聽牌。", NamedTextColor.GRAY));
            } else {
                player.sendMessage(Component.text("打出以下任一張可以聽牌:", NamedTextColor.GOLD));
                for (var entry : options.entrySet()) {
                    Component line = Component.text("打 " + table.render(entry.getKey(), player.getUniqueId()) + " → 等待: ", NamedTextColor.YELLOW);
                    for (Tile t : entry.getValue()) line = line.append(Component.text(table.render(t, player.getUniqueId()) + " "));
                    player.sendMessage(line);
                }
            }
        } else {
            player.sendMessage(Component.text("目前手牌狀態無法判斷聽牌（可能剛叫牌還沒打出）。", NamedTextColor.GRAY));
        }
    }

    private void handleAutoWin(Player player) {
        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table == null || table.phase != MahjongTable.Phase.PLAYING) {
            player.sendMessage(Component.text("你目前沒有在進行中的牌局。", NamedTextColor.RED));
            return;
        }
        GamePlayer gp = table.get(player.getUniqueId());
        if (gp == null) return;
        gp.autoWin = !gp.autoWin;
        player.sendMessage(gp.autoWin
                ? Component.text("已開啟託管：聽牌後只要自摸或榮和的機會出現，系統會自動幫你胡，不用自己搶時間點按鈕。", NamedTextColor.GOLD)
                : Component.text("已關閉託管，改回手動胡牌。", NamedTextColor.YELLOW));
    }

    private void handleTileDisplayToggle(Player player) {
        boolean nowImage = manager.tileDisplayPrefs().toggle(player.getUniqueId(), false);
        player.sendMessage(nowImage
                ? Component.text("已切換成圖示牌面（需要伺服器有安裝對應的資源包，否則會顯示成方塊或亂碼）。", NamedTextColor.GOLD)
                : Component.text("已切換回文字牌面。", NamedTextColor.YELLOW));
    }

    private void handleCreate(Player player, String[] args) {
        if (manager.tableOf(player.getUniqueId()) != null) {
            player.sendMessage(Component.text("你已經在一張桌子了，先 /mahjong leave 離開。", NamedTextColor.RED));
            return;
        }

        String requiredPermission = plugin.getConfig().getString("mahjong.table-creation.permission", "");
        if (requiredPermission != null && !requiredPermission.isBlank() && !player.hasPermission(requiredPermission)) {
            player.sendMessage(Component.text("你沒有權限開桌。", NamedTextColor.RED));
            return;
        }

        double fee = plugin.getConfig().getDouble("mahjong.table-creation.fee", 0);
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

        com.fkc.game.mahjong.model.GameSettings.RuleSet ruleSet = parseRuleSet(args.length > 1 ? args[1] : null);

        manager.create(player.getUniqueId(), player.getName(), ruleSet);
        String ruleSetName = ruleSet == com.fkc.game.mahjong.model.GameSettings.RuleSet.TAIWANESE ? "台灣麻將(十六張)" : "日本立直麻將";
        player.sendMessage(Component.text("已建立" + ruleSetName + "桌，等待其他玩家加入。使用 /mahjong bot 可以加入電腦玩家補位"
                + (ruleSet == com.fkc.game.mahjong.model.GameSettings.RuleSet.TAIWANESE ? "。" : "，/mahjong exchange 可切換是否開啟換三張。"),
                NamedTextColor.GREEN));

        if (plugin.getConfig().getBoolean("mahjong.announce-table-creation", true)) {
            Component announcement = Component.text("🀄 " + player.getName() + " 開了一桌" + ruleSetName
                            + "！輸入 /mahjong join " + player.getName() + " 加入", NamedTextColor.GOLD)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/mahjong join " + player.getName()));
            for (Player online : Bukkit.getServer().getOnlinePlayers()) {
                if (!online.getUniqueId().equals(player.getUniqueId())) online.sendMessage(announcement);
            }
        }
    }

    private com.fkc.game.mahjong.model.GameSettings.RuleSet parseRuleSet(String arg) {
        if (arg == null) return com.fkc.game.mahjong.model.GameSettings.RuleSet.RIICHI;
        return switch (arg.toLowerCase()) {
            case "taiwan", "taiwanese", "台麻", "台灣", "台湾", "十六張", "十六张" -> com.fkc.game.mahjong.model.GameSettings.RuleSet.TAIWANESE;
            default -> com.fkc.game.mahjong.model.GameSettings.RuleSet.RIICHI;
        };
    }

    private void handleJoin(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("用法: /mahjong join <桌主名稱>", NamedTextColor.YELLOW));
            return;
        }
        Player host = Bukkit.getPlayerExact(args[1]);
        if (host == null) {
            player.sendMessage(Component.text("找不到該桌主或桌主不在線上。", NamedTextColor.RED));
            return;
        }
        if (manager.tableOf(player.getUniqueId()) != null) {
            player.sendMessage(Component.text("你已經在一張桌子了，先 /mahjong leave 離開。", NamedTextColor.RED));
            return;
        }
        boolean ok = manager.join(host.getUniqueId(), player.getUniqueId(), player.getName());
        player.sendMessage(ok
                ? Component.text("已加入 " + host.getName() + " 的麻將桌。", NamedTextColor.GREEN)
                : Component.text("加入失敗（桌子不存在、已滿或已開局）。", NamedTextColor.RED));
    }

    private void handleLeave(Player player) {
        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table != null && table.phase == MahjongTable.Phase.PLAYING) {
            player.sendMessage(Component.text("遊戲進行中無法離開，請等對局結束，或請桌主解散牌桌。", NamedTextColor.RED));
            return;
        }
        manager.leave(player.getUniqueId());
        player.sendMessage(Component.text("已離開麻將桌。", NamedTextColor.YELLOW));
    }

    private void handleReady(Player player) {
        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table == null) { player.sendMessage(Component.text("你不在任何桌子。", NamedTextColor.RED)); return; }
        if (table.phase != MahjongTable.Phase.WAITING) {
            player.sendMessage(Component.text("遊戲已經開始，準備狀態不會有任何效果。", NamedTextColor.RED));
            return;
        }
        table.toggleReady(player.getUniqueId());
        GamePlayer gp = table.get(player.getUniqueId());
        player.sendMessage(Component.text(gp.ready ? "你已準備。" : "你取消了準備。", NamedTextColor.YELLOW));
    }

    private void handleStart(Player player) {
        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table == null) { player.sendMessage(Component.text("你不在任何桌子。", NamedTextColor.RED)); return; }
        if (!table.id.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("只有桌主能開始遊戲。", NamedTextColor.RED));
            return;
        }
        if (!table.canStart()) {
            player.sendMessage(Component.text("需要滿 4 人且全部準備完成才能開始（可用 /mahjong bot 補電腦位）。", NamedTextColor.RED));
            return;
        }
        table.startGame();
    }

    private void handleBot(Player player) {
        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table == null || !table.id.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("只有桌主能加入電腦玩家。", NamedTextColor.RED));
            return;
        }
        if (table.phase != MahjongTable.Phase.WAITING) {
            player.sendMessage(Component.text("遊戲已開始，無法加入電腦玩家。", NamedTextColor.RED));
            return;
        }
        boolean added = table.addPlayer(UUID.randomUUID(), "電腦" + (table.players.size()), true);
        player.sendMessage(added
                ? Component.text("已加入一位電腦玩家。", NamedTextColor.GREEN)
                : Component.text("桌子已滿。", NamedTextColor.RED));
    }

    private void handleDestroy(Player player, String[] args) {
        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table == null || !table.id.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("只有桌主能解散桌子。", NamedTextColor.RED));
            return;
        }

        boolean confirming = args.length > 1
                && (args[1].equalsIgnoreCase("confirm") || args[1].equals("確認") || args[1].equals("确认"));

        if (table.phase == MahjongTable.Phase.PLAYING) {
            if (!confirming) {
                pendingDestroyConfirm.put(table.id, System.currentTimeMillis());
                player.sendMessage(Component.text("⚠ 遊戲正在進行中，解散會讓所有玩家的手牌/戰績直接消失。", NamedTextColor.RED));
                player.sendMessage(Component.text("[確認解散]", NamedTextColor.RED)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/mahjong destroy confirm")));
                return;
            }
            Long requestedAt = pendingDestroyConfirm.remove(table.id);
            if (requestedAt == null || System.currentTimeMillis() - requestedAt > DESTROY_CONFIRM_WINDOW_MS) {
                player.sendMessage(Component.text("確認已逾時，請重新輸入 /mahjong destroy。", NamedTextColor.RED));
                return;
            }
        }

        manager.destroy(table.id);
        player.sendMessage(Component.text("已解散麻將桌。", NamedTextColor.YELLOW));
    }

    private void handleInfo(Player player) {
        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table == null) { player.sendMessage(Component.text("你不在任何桌子。", NamedTextColor.RED)); return; }
        player.sendMessage(Component.text("=== 麻將桌資訊 ===", NamedTextColor.GOLD));
        for (GamePlayer p : table.players) {
            player.sendMessage(Component.text((p.isBot ? "[電腦] " : "") + p.name
                    + (table.phase == MahjongTable.Phase.WAITING ? (p.ready ? " (已準備)" : " (未準備)") : " " + p.score + "點"),
                    NamedTextColor.WHITE));
        }

        if (table.phase == MahjongTable.Phase.PLAYING) {
            Tile lastDiscard = table.getLastDiscard();
            var lastDiscarder = table.getLastDiscarder();
            if (lastDiscard != null && lastDiscarder != null) {
                player.sendMessage(Component.text("最新棄牌: ", NamedTextColor.AQUA)
                        .append(Component.text(table.render(lastDiscard, player.getUniqueId()) + "（" + lastDiscarder.name + "打出）")));
            }
            var doraIndicators = table.getDoraIndicators();
            if (!doraIndicators.isEmpty()) {
                Component dora = Component.text("寶牌指標: ", NamedTextColor.AQUA);
                for (Tile t : doraIndicators) dora = dora.append(Component.text(table.render(t, player.getUniqueId()) + " "));
                player.sendMessage(dora);
            }
            player.sendMessage(Component.text("牌山剩餘: " + table.getRemainingTiles() + "張", NamedTextColor.AQUA));
            var current = table.getCurrentPlayer();
            if (current != null) {
                player.sendMessage(Component.text("目前輪到: " + current.name
                        + (current.uuid.equals(player.getUniqueId()) ? "（就是你！）" : ""), NamedTextColor.AQUA));
            }
            GamePlayer me = table.get(player.getUniqueId());
            if (me != null) {
                Component hand = Component.text("你的手牌: ", NamedTextColor.YELLOW);
                for (Tile t : me.hand) hand = hand.append(Component.text(table.render(t, player.getUniqueId()) + " "));
                player.sendMessage(hand);
            }

            for (GamePlayer p : table.players) {
                if (!p.melds.isEmpty()) {
                    Component melds = Component.text(p.name + " 的明牌: ", NamedTextColor.AQUA);
                    for (var meld : p.melds) melds = melds.append(Component.text("[" + meld.display() + "] "));
                    player.sendMessage(melds);
                }
            }

            for (GamePlayer p : table.players) {
                if (!p.discards.isEmpty()) {
                    Component river = Component.text(p.name + " 的棄牌: ", NamedTextColor.GRAY);
                    for (Tile t : p.discards) river = river.append(Component.text(table.render(t, player.getUniqueId()) + " "));
                    player.sendMessage(river);
                }
            }
        }
    }

    private void handleList(Player player) {
        var tables = manager.all();
        if (tables.isEmpty()) {
            player.sendMessage(Component.text("目前沒有任何麻將桌。", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text("=== 麻將桌列表 ===", NamedTextColor.GOLD));
        tables.values().stream()
                .sorted((a, b) -> Long.compare(a.createdAt, b.createdAt))
                .forEach(t -> {
                    String hostName = t.players.get(0).name;
                    boolean joinable = t.phase == MahjongTable.Phase.WAITING && t.players.size() < 4;
                    Component line = Component.text(hostName + " 的桌子 — "
                            + t.players.size() + "/4 人 — " + t.phase, NamedTextColor.WHITE);
                    if (joinable) {
                        line = line.append(Component.text("  [加入]", NamedTextColor.GREEN)
                                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/mahjong join " + hostName))
                                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("點擊加入這桌"))));
                    }
                    player.sendMessage(line);
                });
    }

    private void handleAction(Player player, String[] args) {
        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table == null || table.phase != MahjongTable.Phase.PLAYING) return;
        GamePlayer gp = table.get(player.getUniqueId());
        if (gp == null) return;
        if (args.length < 2) return;

        switch (args[1].toLowerCase()) {
            case "discard" -> {
                if (args.length < 3) return;
                Tile t = parseTile(args[2]);
                if (t != null) table.discard(gp, findInHand(gp, t), false);
            }
            case "riichi" -> {
                if (args.length < 3) return;
                Tile t = parseTile(args[2]);
                if (t == null) return;
                if (!gp.menzen) {
                    player.sendMessage(Component.text("手牌不是門前清，無法立直。", NamedTextColor.RED));
                    return;
                }
                if (gp.score < 1000) {
                    player.sendMessage(Component.text("分數不足 1000 點，無法立直。", NamedTextColor.RED));
                    return;
                }
                java.util.List<Tile> afterDiscard = new java.util.ArrayList<>(gp.hand);
                Tile actual = findInHand(gp, t);
                afterDiscard.remove(actual);
                if (!com.fkc.game.mahjong.score.WinChecker.isTenpai(afterDiscard, gp.melds)) {
                    player.sendMessage(Component.text("打出這張牌後不會聽牌，無法立直。", NamedTextColor.RED));
                    return;
                }
                table.discard(gp, actual, true);
            }
            case "tsumo" -> table.declareTsumo(gp);
            case "ankan" -> {
                if (args.length < 3) return;
                Tile t = parseTile(args[2]);
                if (t != null) table.declareAnkan(gp, t);
            }
            case "call" -> {
                if (args.length < 4) return;
                String verb = args[2];
                String genArg = args[3];
                String extra = args.length > 4 ? args[4] : "";
                if ("auto".equals(genArg)) {
                    table.handleCallAction(player.getUniqueId(), verb, extra);
                } else {
                    try {
                        int gen = Integer.parseInt(genArg);
                        table.handleCallAction(player.getUniqueId(), verb, gen, extra);
                    } catch (NumberFormatException ignored) {
                        // malformed generation token — treat as a no-op rather than guessing
                    }
                }
            }
            case "pass" -> {
                String genArg = args.length > 2 ? args[2] : "auto";
                if ("auto".equals(genArg)) {
                    table.handleCallAction(player.getUniqueId(), "pass", "");
                } else {
                    try {
                        int gen = Integer.parseInt(genArg);
                        table.handleCallAction(player.getUniqueId(), "pass", gen, "");
                    } catch (NumberFormatException ignored) {
                        // malformed generation token — treat as a no-op rather than guessing
                    }
                }
            }
            case "exchange" -> {
                if (args.length < 3) return;
                Tile t = parseTile(args[2]);
                if (t != null) table.toggleExchangeTile(gp, t);
            }
            case "exchangeconfirm" -> table.confirmExchange(gp);
            default -> {}
        }
    }

    /**
     * Lets players type short top-level commands like "/mj 碰" or "/mj pon"
     * instead of the full "/mahjong action call pon". Just rewrites into
     * the equivalent action-command args and delegates to handleAction, so
     * there's only one place (handleAction) that actually validates and
     * executes each move.
     */
    private void handleShorthand(Player player, String[] args) {
        String token = args[0];
        String param1 = args.length > 1 ? args[1] : null;

        String[] rewritten = switch (token) {
            case "胡", "和", "榮和", "荣和", "ron" -> new String[]{"action", "call", "ron", "auto"};
            case "碰", "pon" -> new String[]{"action", "call", "pon", "auto"};
            case "槓", "杠", "kan", "minkan" -> new String[]{"action", "call", "minkan", "auto"};
            case "吃", "chi" -> new String[]{"action", "call", "chi", "auto", param1 != null ? param1 : "0"};
            case "跳過", "跳过", "過", "过", "pass" -> new String[]{"action", "pass", "auto"};
            case "自摸", "tsumo" -> new String[]{"action", "tsumo"};
            case "打", "discard" -> param1 == null ? null : new String[]{"action", "discard", param1};
            case "立直", "riichi" -> param1 == null ? null : new String[]{"action", "riichi", param1};
            case "暗槓", "暗杠", "ankan" -> param1 == null ? null : new String[]{"action", "ankan", param1};
            case "確認換牌", "确认换牌", "exchangeconfirm" -> new String[]{"action", "exchangeconfirm"};
            case "換牌", "换牌" -> param1 == null ? null : new String[]{"action", "exchange", param1};
            default -> null;
        };

        if (rewritten == null) {
            player.sendMessage(Component.text("未知的子指令。", NamedTextColor.RED));
            return;
        }
        handleAction(player, rewritten);
    }

    private Tile parseTile(String s) {
        return Tile.parse(s);
    }

    private Tile findInHand(GamePlayer p, Tile type) {
        for (Tile t : p.hand) if (t.equals(type)) return t;
        return type;
    }

    private static final List<String> TOP_LEVEL = List.of(
            "create", "join", "leave", "ready", "start", "bot", "destroy", "info", "list",
            "exchange", "reload", "action",
            "開桌", "加入", "離開", "準備", "開始", "電腦", "解散", "查看", "列表", "換三張", "重載", "排行", "面板", "聽牌", "託管", "圖示",
            "胡", "碰", "槓", "吃", "跳過", "自摸", "打", "立直", "暗槓", "確認換牌", "換牌"
    );
    private static final List<String> ACTION_VERBS = List.of(
            "discard", "riichi", "tsumo", "ankan", "call", "pass", "exchange", "exchangeconfirm"
    );
    private static final List<String> CALL_VERBS = List.of("ron", "pon", "minkan", "chi", "胡", "碰", "槓", "吃");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();

        if (args.length == 1) {
            return filterPrefix(TOP_LEVEL, args[0]);
        }

        String first = args[0].toLowerCase();

        if ((first.equals("join") || first.equals("加入")) && args.length == 2) {
            return filterPrefix(onlinePlayerNames(), args[1]);
        }

        if ((first.equals("create") || first.equals("開桌") || first.equals("开桌") || first.equals("建桌")) && args.length == 2) {
            return filterPrefix(List.of("riichi", "taiwan", "台麻", "台灣", "日麻"), args[1]);
        }

        if ((first.equals("destroy") || first.equals("解散")) && args.length == 2) {
            return filterPrefix(List.of("confirm", "確認"), args[1]);
        }

        // Top-level shorthand tile actions: "/mj 打 <tile>", "/mj 立直 <tile>",
        // "/mj 暗槓 <tile>", "/mj 換牌 <tile>" all take a tile as arg 2.
        if (List.of("打", "立直", "暗槓", "換牌", "discard", "riichi", "ankan", "exchange").contains(first) && args.length == 2) {
            return filterPrefix(handTileLabels(player), args[1]);
        }

        if (first.equals("action")) {
            if (args.length == 2) return filterPrefix(ACTION_VERBS, args[1]);
            String verb = args[1].toLowerCase();
            if (args.length == 3 && List.of("discard", "riichi", "ankan", "exchange").contains(verb)) {
                return filterPrefix(handTileLabels(player), args[2]);
            }
            if (args.length == 3 && verb.equals("call")) {
                return filterPrefix(CALL_VERBS, args[2]);
            }
        }

        return List.of();
    }

    private List<String> handTileLabels(Player player) {
        MahjongTable table = manager.tableOf(player.getUniqueId());
        if (table == null || table.phase != MahjongTable.Phase.PLAYING) return List.of();
        GamePlayer gp = table.get(player.getUniqueId());
        if (gp == null) return List.of();
        List<String> labels = new ArrayList<>();
        for (Tile t : gp.hand) {
            String label = t.label();
            if (!labels.contains(label)) labels.add(label);
        }
        return labels;
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getServer().getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    private List<String> filterPrefix(List<String> options, String typed) {
        String lower = typed.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase().startsWith(lower) || o.startsWith(typed)) result.add(o);
        }
        return result;
    }
}
