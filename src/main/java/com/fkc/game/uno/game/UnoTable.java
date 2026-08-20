package com.fkc.game.uno.game;

import com.fkc.game.uno.model.Card;
import com.fkc.game.uno.model.Deck;
import com.fkc.game.uno.model.UnoPlayer;
import com.fkc.game.uno.model.UnoSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UnoTable {

    public enum Phase { WAITING, PLAYING, ENDED }
    public enum BroadcastCategory { ROUND_START, PLAY, DRAW, SPECIAL, WIN, GAME_END }

    public final UUID id;
    public final Plugin plugin;
    public final com.fkc.game.core.Leaderboard leaderboard;
    private final com.fkc.game.core.PlayerPreferenceStore sidebarPrefs;
    public com.fkc.game.core.PlayerPreferenceStore cardDisplayPrefs;
    public com.fkc.game.core.RewardItemStore rewardStore;
    public final long createdAt = System.currentTimeMillis();
    public long waitingSince = System.currentTimeMillis();

    public final List<UnoPlayer> players = new ArrayList<>();
    public final UnoSettings settings = new UnoSettings();
    public Phase phase = Phase.WAITING;

    public final java.util.Map<BroadcastCategory, Boolean> serverBroadcast = new java.util.EnumMap<>(BroadcastCategory.class);
    {
        for (BroadcastCategory c : BroadcastCategory.values()) serverBroadcast.put(c, false);
    }

    private Deck deck;
    private int currentIndex = 0;
    private int direction = 1;
    private Card.Color currentColor;
    private boolean hasDrawnThisTurn = false;
    private int turnGeneration = 0;

    public UnoTable(Plugin plugin, UUID id, com.fkc.game.core.Leaderboard leaderboard, com.fkc.game.core.PlayerPreferenceStore sidebarPrefs) {
        this.plugin = plugin;
        this.id = id;
        this.leaderboard = leaderboard;
        this.sidebarPrefs = sidebarPrefs;
    }

    // -----------------------------------------------------------
    // Lobby management
    // -----------------------------------------------------------

    public boolean addPlayer(UUID uuid, String name, boolean bot) {
        if (players.size() >= 8) return false;
        if (players.stream().anyMatch(p -> p.uuid.equals(uuid))) return false;
        UnoPlayer up = new UnoPlayer(uuid, name, bot);
        up.ready = bot;
        players.add(up);
        return true;
    }

    public synchronized void removePlayer(UUID uuid) {
        players.removeIf(p -> p.uuid.equals(uuid));
    }

    public UnoPlayer get(UUID uuid) {
        return players.stream().filter(p -> p.uuid.equals(uuid)).findFirst().orElse(null);
    }

    public synchronized void toggleReady(UUID uuid) {
        UnoPlayer p = get(uuid);
        if (p != null) p.ready = !p.ready;
    }

    public boolean canStart() {
        return players.size() >= 2 && players.stream().allMatch(p -> p.ready);
    }

    // -----------------------------------------------------------
    // Round lifecycle
    // -----------------------------------------------------------

    public synchronized void startGame() {
        phase = Phase.PLAYING;
        for (UnoPlayer p : players) p.score = 0;
        startRound();
    }

    private void startRound() {
        deck = new Deck();
        for (UnoPlayer p : players) {
            p.hand.clear();
            p.unoCalled = false;
            for (int i = 0; i < settings.startingHandSize; i++) {
                p.hand.add(deck.draw());
            }
        }
        currentIndex = 0;
        direction = 1;
        hasDrawnThisTurn = false;

        Card first = deck.draw();
        // Standard UNO rule: if the flipped starter card is Wild Draw Four,
        // shuffle it back in and draw again instead.
        while (first.type == Card.Type.WILD_DRAW_FOUR) {
            deck.discard(first);
            first = deck.draw();
        }
        deck.discard(first);
        currentColor = first.isWild() ? randomColor() : first.color;

        Card finalFirst = first;
        broadcast(viewerUuid -> Component.text("=== 新的一輪開始！起始牌: ", NamedTextColor.GOLD)
                .append(renderComponent(finalFirst, viewerUuid)), BroadcastCategory.ROUND_START);

        // Apply the starting card's effect, if it's an action card.
        switch (first.type) {
            case SKIP -> currentIndex = nextIndex(currentIndex);
            case REVERSE -> direction = -1;
            case DRAW_TWO -> {
                UnoPlayer victim = players.get(currentIndex);
                victim.hand.add(deck.draw());
                victim.hand.add(deck.draw());
                broadcast(Component.text(victim.name + " 因起始牌被罰摸 2 張。", NamedTextColor.YELLOW), BroadcastCategory.SPECIAL);
                currentIndex = nextIndex(currentIndex);
            }
            case WILD -> broadcast(Component.text("起始顏色隨機決定為 " + colorName(currentColor), NamedTextColor.LIGHT_PURPLE), BroadcastCategory.ROUND_START);
            default -> { /* number card, nothing extra */ }
        }

        sendInitialHands();
        promptTurn();
    }

    /**
     * Shows every human player their starting hand right after dealing —
     * otherwise only whoever's turn comes up first would see their hand,
     * and everyone else would have no idea what they're holding until
     * their own first turn.
     */
    private void sendInitialHands() {
        for (UnoPlayer p : players) {
            if (p.isBot) continue;
            Player bukkit = Bukkit.getPlayer(p.uuid);
            if (bukkit == null) continue;
            Component hand = Component.text("你的起手牌: ", NamedTextColor.WHITE);
            for (Card c : p.hand) hand = hand.append(renderComponent(c, p.uuid)).append(Component.text(" "));
            bukkit.sendMessage(hand);
        }
    }

    /**
     * Builds this card's display Component for one specific viewer, honoring
     * their personal text/image preference (see cardDisplayPrefs, toggled via
     * /uno tiles). Always sets an explicit color rather than leaving it to
     * inherit from whatever parent Component this gets appended into — for
     * image mode this is critical: an inherited non-white color would tint
     * the card's bitmap image (same bug class mahjong tile rendering hit).
     */
    public Component renderComponent(Card c, UUID viewerUuid) {
        boolean serverDefault = plugin.getConfig().getBoolean("uno.tiles-image-default", false);
        boolean image = cardDisplayPrefs != null && cardDisplayPrefs.isEnabled(viewerUuid, serverDefault);
        return image
                ? Component.text(c.imageGlyph(), NamedTextColor.WHITE)
                : Component.text(c.display(), colorToNamedText(c.color));
    }

    private Card.Color randomColor() {
        Card.Color[] colors = {Card.Color.RED, Card.Color.YELLOW, Card.Color.GREEN, Card.Color.BLUE};
        return colors[(int) (Math.random() * colors.length)];
    }

    private UnoPlayer currentPlayer() {
        return players.get(currentIndex);
    }

    /** Public accessor so /uno info can show whose turn it is. Returns null if the table isn't playing. */
    public UnoPlayer getCurrentPlayer() {
        if (phase != Phase.PLAYING || players.isEmpty() || currentIndex < 0 || currentIndex >= players.size()) return null;
        return players.get(currentIndex);
    }

    /** Public accessor so /uno info can show the current top-of-discard card. Returns null if not playing. */
    public Card getTopCard() {
        if (phase != Phase.PLAYING || deck == null) return null;
        return deck.topOfDiscard();
    }

    /** How many cards are left in the draw pile. */
    public int getRemainingCards() {
        return deck != null ? deck.drawPileSize() : 0;
    }

    /** Public accessor so /uno info can show the currently active color (matters after a wild card is played). */
    public Card.Color getCurrentColor() {
        return phase == Phase.PLAYING ? currentColor : null;
    }

    private int nextIndex(int i) {
        int size = players.size();
        return ((i + direction) % size + size) % size;
    }

    // -----------------------------------------------------------
    // Turn actions
    // -----------------------------------------------------------

    public boolean canPlay(Card top, Card.Color activeColor, Card candidate) {
        if (candidate.isWild()) return true;
        if (candidate.color == activeColor) return true;
        if (top.type == Card.Type.NUMBER && candidate.type == Card.Type.NUMBER && top.number == candidate.number) return true;
        return top.type == candidate.type && top.type != Card.Type.NUMBER;
    }

    /** @return null on success, or an error message. */
    public synchronized String playCard(UnoPlayer p, Card cardInHand, Card.Color chosenColor) {
        if (currentPlayer() != p) return "還沒輪到你。";
        Card top = deck.topOfDiscard();
        if (!canPlay(top, currentColor, cardInHand)) return "這張牌不能出（顏色/數字/類型都對不上）。";
        if (cardInHand.isWild() && chosenColor == null) return "出萬能牌要指定顏色。";

        p.hand.remove(cardInHand);
        deck.discard(cardInHand);
        currentColor = cardInHand.isWild() ? chosenColor : cardInHand.color;
        hasDrawnThisTurn = false;

        broadcast(viewerUuid -> Component.text(p.name + " 出了 ", NamedTextColor.WHITE)
                .append(renderComponent(cardInHand, viewerUuid)), BroadcastCategory.PLAY);
        if (cardInHand.isWild()) {
            broadcast(Component.text(p.name + " 指定顏色為 " + colorName(currentColor), NamedTextColor.LIGHT_PURPLE), BroadcastCategory.SPECIAL);
        }

        if (p.hand.size() > 1) {
            p.unoCalled = false;
        }
        if (p.hand.isEmpty()) {
            resolveRoundWin(p);
            return null;
        }
        if (p.hand.size() == 1) {
            notifyUnoReminder(p);
        }

        applyEffectAndAdvance(cardInHand);
        return null;
    }

    private void applyEffectAndAdvance(Card played) {
        switch (played.type) {
            case SKIP -> {
                currentIndex = nextIndex(currentIndex);
                broadcast(Component.text(currentPlayer().name + " 被跳過了。", NamedTextColor.YELLOW), BroadcastCategory.SPECIAL);
                currentIndex = nextIndex(currentIndex);
            }
            case REVERSE -> {
                direction = -direction;
                if (players.size() == 2) {
                    // With only 2 players, flipping direction is a no-op
                    // (nextIndex alternates 0<->1 either way), so official
                    // rules treat Reverse as a Skip: advance once here to
                    // "skip" the opponent, then the unconditional advance
                    // below brings it right back to the player who just
                    // played — i.e. they go again.
                    currentIndex = nextIndex(currentIndex);
                }
                currentIndex = nextIndex(currentIndex);
            }
            case DRAW_TWO -> {
                int victimIndex = nextIndex(currentIndex);
                UnoPlayer victim = players.get(victimIndex);
                victim.hand.add(deck.draw());
                victim.hand.add(deck.draw());
                victim.unoCalled = false;
                broadcast(Component.text(victim.name + " 被罰摸 2 張並跳過。", NamedTextColor.YELLOW), BroadcastCategory.SPECIAL);
                currentIndex = nextIndex(victimIndex);
            }
            case WILD_DRAW_FOUR -> {
                int victimIndex = nextIndex(currentIndex);
                UnoPlayer victim = players.get(victimIndex);
                for (int i = 0; i < 4; i++) victim.hand.add(deck.draw());
                victim.unoCalled = false;
                broadcast(Component.text(victim.name + " 被罰摸 4 張並跳過。", NamedTextColor.YELLOW), BroadcastCategory.SPECIAL);
                currentIndex = nextIndex(victimIndex);
            }
            default -> currentIndex = nextIndex(currentIndex);
        }
        hasDrawnThisTurn = false;
        promptTurn();
    }

    public synchronized String drawCard(UnoPlayer p) {
        if (currentPlayer() != p) return "還沒輪到你。";
        if (hasDrawnThisTurn) return "你這回合已經摸過牌了，請出牌或跳過。";
        Card drawn = deck.draw();
        p.hand.add(drawn);
        if (p.hand.size() > 1) {
            p.unoCalled = false;
        }
        hasDrawnThisTurn = true;
        Player bukkit = Bukkit.getPlayer(p.uuid);
        if (bukkit != null) {
            bukkit.sendMessage(Component.text("你摸到了 ", NamedTextColor.AQUA).append(renderComponent(drawn, p.uuid)));
            Card top = deck.topOfDiscard();
            if (canPlay(top, currentColor, drawn)) {
                bukkit.sendMessage(Component.text("這張牌可以出，", NamedTextColor.GREEN)
                        .append(cardButton(drawn, p.uuid))
                        .append(Component.text(" 或選擇 ", NamedTextColor.GREEN))
                        .append(Component.text("[跳過]", NamedTextColor.GRAY).clickEvent(ClickEvent.runCommand("/uno action pass"))));
            } else {
                bukkit.sendMessage(Component.text("這張牌不能出，", NamedTextColor.GRAY)
                        .append(Component.text("[跳過]", NamedTextColor.GRAY).clickEvent(ClickEvent.runCommand("/uno action pass"))));
            }
        }
        return null;
    }

    public synchronized String pass(UnoPlayer p) {
        if (currentPlayer() != p) return "還沒輪到你。";
        if (!hasDrawnThisTurn) return "你要先摸一張牌才能跳過。";
        broadcast(Component.text(p.name + " 跳過了這回合。", NamedTextColor.GRAY), BroadcastCategory.SPECIAL);
        currentIndex = nextIndex(currentIndex);
        hasDrawnThisTurn = false;
        promptTurn();
        return null;
    }

    public synchronized void callUno(UnoPlayer p) {
        if (p.hand.size() == 1 && !p.unoCalled) {
            p.unoCalled = true;
            broadcast(Component.text(p.name + " 喊了 UNO！", NamedTextColor.GOLD), BroadcastCategory.SPECIAL);
        }
        // Already called (or hand size isn't 1) — silently no-op, don't
        // re-broadcast on every repeated click of the [喊UNO] button.
    }

    public synchronized String catchUno(UnoPlayer accuser, UnoPlayer target) {
        if (target.hand.size() != 1 || target.unoCalled) {
            return "別急!還不到時機。";
        }
        for (int i = 0; i < settings.unoCatchPenalty; i++) target.hand.add(deck.draw());
        broadcast(Component.text(accuser.name + " 抓到 " + target.name + " 忘記喊 UNO，罰摸 "
                + settings.unoCatchPenalty + " 張！", NamedTextColor.GOLD), BroadcastCategory.SPECIAL);
        target.unoCalled = false;
        return null;
    }

    private void notifyUnoReminder(UnoPlayer p) {
        Player bukkit = Bukkit.getPlayer(p.uuid);
        if (bukkit != null) {
            bukkit.sendMessage(Component.text("你只剩 1 張牌了！記得 ", NamedTextColor.GOLD)
                    .append(Component.text("[喊UNO]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/uno action calluno")))
                    .append(Component.text("，不然可能被別人抓包罰牌。", NamedTextColor.GOLD)));
        }
        for (UnoPlayer other : players) {
            if (other.uuid.equals(p.uuid) || other.isBot) continue;
            Player otherBukkit = Bukkit.getPlayer(other.uuid);
            if (otherBukkit == null) continue;
            otherBukkit.sendMessage(Component.text(p.name + " 只剩 1 張牌了！如果他忘記喊 UNO，", NamedTextColor.GOLD)
                    .append(Component.text("[抓他!]", NamedTextColor.RED)
                            .clickEvent(ClickEvent.runCommand("/uno action catch " + p.name))
                            .hoverEvent(HoverEvent.showText(Component.text("點擊抓 " + p.name + " 忘記喊 UNO 的漏洞（如果他其實已經喊過了，點擊會沒有效果）")))));
        }
    }

    // -----------------------------------------------------------
    // Round / match end
    // -----------------------------------------------------------

    private void resolveRoundWin(UnoPlayer winner) {
        int points = 0;
        for (UnoPlayer p : players) {
            if (p == winner) continue;
            for (Card c : p.hand) points += c.pointValue();
        }
        winner.score += points;
        broadcast(Component.text(winner.name + " 出完手牌獲勝！這輪拿到 " + points + " 分，總分 "
                + winner.score + " 分。", NamedTextColor.GOLD), BroadcastCategory.WIN);

        if (winner.score >= settings.targetScore) {
            endMatch();
        } else {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> startRound(), 20L * 3);
        }
    }

    private void endMatch() {
        phase = Phase.ENDED;
        players.sort((a, b) -> b.score - a.score);
        broadcast(Component.text("=== 對局結束 ===", NamedTextColor.GOLD), BroadcastCategory.GAME_END);
        for (int i = 0; i < players.size(); i++) {
            UnoPlayer p = players.get(i);
            broadcast(Component.text((i + 1) + "位: " + p.name + " " + p.score + "分", NamedTextColor.WHITE), BroadcastCategory.GAME_END);
        }
        recordLeaderboard();
        awardWinnerItem();
        broadcast(Component.text("5 秒後回到準備大廳，可以重新 /uno ready 開始下一場。", NamedTextColor.GRAY), BroadcastCategory.GAME_END);
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> resetToLobby(), 20L * 5);
    }

    /**
     * Gives this match's winner a clone of the admin-configured reward item
     * (see RewardItemStore / /uno setreward), if one has been set. Same
     * no-bot eligibility guard as recordLeaderboard().
     */
    private void awardWinnerItem() {
        if (rewardStore == null || !rewardStore.isSet()) return;
        if (!plugin.getConfig().getBoolean("uno.reward-enabled", true)) return;
        if (players.stream().anyMatch(p -> p.isBot)) return;
        int dailyLimit = plugin.getConfig().getInt("uno.reward-daily-limit", -1);
        if (!rewardStore.tryConsumeDailyAllowance("uno", dailyLimit)) return;
        UnoPlayer winner = players.get(0); // already sorted descending by score above
        Player bukkit = Bukkit.getPlayer(winner.uuid);
        if (bukkit == null) return;
        org.bukkit.inventory.ItemStack item = rewardStore.get();
        if (item == null) return;
        var leftover = bukkit.getInventory().addItem(item);
        for (org.bukkit.inventory.ItemStack extra : leftover.values()) {
            bukkit.getWorld().dropItem(bukkit.getLocation(), extra);
        }
        bukkit.sendMessage(leftover.isEmpty()
                ? Component.text("🏆 恭喜獲得本場第一名獎品！", NamedTextColor.GOLD)
                : Component.text("🏆 恭喜獲得本場第一名獎品！背包放不下，已經掉在你腳邊了。", NamedTextColor.GOLD));
        if (plugin.getConfig().getBoolean("uno.reward-announce", true)) {
            Component announcement = Component.text("🏆 " + winner.name + " 在 UNO 拿到本場第一名，獲得獎品！", NamedTextColor.GOLD);
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                online.sendMessage(announcement);
            }
        }
    }

    /**
     * Records leaderboard scores at match end. UNO isn't naturally zero-sum
     * the way mahjong is (a losing player's score never goes negative on
     * its own), so two colluding accounts could otherwise farm one of them
     * for free by always losing on purpose. Two extra guards compensate:
     * <ul>
     *   <li>requires a configurable MINIMUM number of real players (default
     *       3) — coordinating 3+ accounts to always let one win is a much
     *       higher bar than a simple 2-account loop;</li>
     *   <li>every non-winning human player takes a small, configurable
     *       leaderboard penalty (default -20) — "always losing on purpose"
     *       now has a real, if modest, cost instead of being free.</li>
     * </ul>
     * Matches with ANY bot seated don't get recorded at all.
     */
    private void recordLeaderboard() {
        if (leaderboard == null) return;
        if (players.stream().anyMatch(p -> p.isBot)) {
            plugin.getLogger().info("[uno] 桌 " + id + " 對局結束但有電腦玩家在桌，不寫入排行榜。");
            return;
        }
        int minPlayers = plugin.getConfig().getInt("uno.leaderboard.min-players-for-ranking", 3);
        if (players.size() < minPlayers) {
            plugin.getLogger().info("[uno] 桌 " + id + " 對局結束但人數(" + players.size() + ")低於最低門檻(" + minPlayers + ")，不寫入排行榜。");
            return;
        }

        int maxPerDay = plugin.getConfig().getInt("uno.leaderboard.max-ranked-matches-per-day", 20);
        long loserPenalty = plugin.getConfig().getInt("uno.leaderboard.loser-penalty", 20);
        UnoPlayer winner = players.get(0); // already sorted descending by score above

        for (UnoPlayer p : players) {
            long delta = (p == winner) ? p.score : -loserPenalty;
            leaderboard.addScore(p.uuid, p.name, delta, maxPerDay);
        }
        plugin.getLogger().info("[uno] 桌 " + id + " 對局正常結束，已寫入排行榜。贏家: " + winner.name + " (" + winner.score + " 分)");
    }

    private void resetToLobby() {
        if (phase != Phase.ENDED) return;
        clearSidebars();
        phase = Phase.WAITING;
        waitingSince = System.currentTimeMillis();
        for (UnoPlayer p : players) {
            p.ready = p.isBot || p.uuid.equals(id); // host defaults to ready again, same courtesy as table creation
            p.hand.clear();
            p.score = 0;
            p.unoCalled = false;
        }
        broadcast(Component.text("牌桌已重置，等待所有人 /uno ready 準備下一場。", NamedTextColor.YELLOW), BroadcastCategory.GAME_END);
    }

    // -----------------------------------------------------------
    // Display helpers
    // -----------------------------------------------------------

    private void promptTurn() {
        turnGeneration++;
        int myGeneration = turnGeneration;
        UnoPlayer p = currentPlayer();
        if (p.isBot) {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> botTurn(p), 20L);
            return;
        }
        Player bukkit = Bukkit.getPlayer(p.uuid);
        if (bukkit == null) {
            // Disconnected human — just draw+pass to keep the game moving.
            drawCard(p);
            pass(p);
            return;
        }
        Card top = deck.topOfDiscard();
        bukkit.sendMessage(Component.text("輪到你了！目前牌面: ", NamedTextColor.AQUA)
                .append(renderComponent(top, p.uuid))
                .append(Component.text(" 顏色: " + colorName(currentColor), NamedTextColor.AQUA)));
        Component hand = Component.text("你的手牌: ", NamedTextColor.WHITE);
        for (Card c : p.hand) {
            hand = hand.append(cardButton(c, p.uuid)).append(Component.text(" "));
        }
        bukkit.sendMessage(hand);
        bukkit.sendMessage(Component.text("沒有牌可出的話，", NamedTextColor.GRAY)
                .append(Component.text("[摸牌]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/uno action draw"))));

        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (myGeneration == turnGeneration) remindSlowPlayer(p);
        }, 100L); // 100 ticks = 5 seconds
    }

    /** Called 5 seconds after promptTurn() if the current player still hasn't acted — nudges the whole table so everyone knows why the game looks stalled. */
    private void remindSlowPlayer(UnoPlayer p) {
        broadcast(Component.text("⏳ 等待 " + p.name + " 出牌中...", NamedTextColor.GRAY), BroadcastCategory.SPECIAL);
    }

    private Component cardButton(Card c, UUID viewerUuid) {
        String cmd = c.isWild()
                ? "/uno action wildcolor " + c.code()
                : "/uno action play " + c.code();
        return renderComponent(c, viewerUuid)
                .clickEvent(ClickEvent.runCommand(cmd))
                .hoverEvent(HoverEvent.showText(Component.text(c.isWild() ? "點擊選擇要出的萬能牌類型，接著再選顏色" : "點擊出這張牌")));
    }

    /** Called after clicking a wild-card button — shows the 4 color choices. */
    public synchronized void promptColorChoice(UnoPlayer p, Card wildCard) {
        Player bukkit = Bukkit.getPlayer(p.uuid);
        if (bukkit == null) return;
        Component msg = Component.text("選擇顏色: ", NamedTextColor.YELLOW);
        for (Card.Color color : new Card.Color[]{Card.Color.RED, Card.Color.YELLOW, Card.Color.GREEN, Card.Color.BLUE}) {
            msg = msg.append(Component.text("[" + colorName(color) + "]", colorToNamedText(color))
                    .clickEvent(ClickEvent.runCommand("/uno action play " + wildCard.code() + " " + color.name())))
                    .append(Component.text(" "));
        }
        bukkit.sendMessage(msg);
    }

    private void botTurn(UnoPlayer bot) {
        if (currentPlayer() != bot) return;
        Card top = deck.topOfDiscard();
        Card playable = bot.hand.stream().filter(c -> canPlay(top, currentColor, c)).findFirst().orElse(null);
        if (playable != null) {
            Card.Color chosen = playable.isWild() ? randomColor() : null;
            playCard(bot, playable, chosen);
        } else {
            drawCard(bot);
            Card drawn = bot.hand.get(bot.hand.size() - 1);
            if (canPlay(top, currentColor, drawn)) {
                Card.Color chosen = drawn.isWild() ? randomColor() : null;
                playCard(bot, drawn, chosen);
            } else {
                pass(bot);
            }
        }
    }

    private static String colorName(Card.Color c) {
        return switch (c) {
            case RED -> "紅";
            case YELLOW -> "黃";
            case GREEN -> "綠";
            case BLUE -> "藍";
            case WILD -> "萬能";
        };
    }

    private static NamedTextColor colorToNamedText(Card.Color c) {
        return switch (c) {
            case RED -> NamedTextColor.RED;
            case YELLOW -> NamedTextColor.YELLOW;
            case GREEN -> NamedTextColor.GREEN;
            case BLUE -> NamedTextColor.BLUE;
            case WILD -> NamedTextColor.LIGHT_PURPLE;
        };
    }

    public synchronized void broadcast(Component msg, BroadcastCategory category) {
        broadcast(viewerUuid -> msg, category);
    }

    /**
     * Like broadcast(Component, BroadcastCategory), but builds a separate
     * Component per recipient — needed for messages embedding a card glyph,
     * since each viewer independently chooses text or image display via
     * /uno tiles.
     */
    public synchronized void broadcast(java.util.function.Function<UUID, Component> msgBuilder, BroadcastCategory category) {
        for (UnoPlayer p : players) {
            if (p.isBot) continue;
            Player bukkit = Bukkit.getPlayer(p.uuid);
            if (bukkit != null) bukkit.sendMessage(msgBuilder.apply(p.uuid));
        }
        if (Boolean.TRUE.equals(serverBroadcast.get(category))) {
            for (Player online : Bukkit.getServer().getOnlinePlayers()) {
                boolean alreadySent = players.stream().anyMatch(p -> !p.isBot && p.uuid.equals(online.getUniqueId()));
                if (!alreadySent) online.sendMessage(msgBuilder.apply(online.getUniqueId()));
            }
        }
        // Defense-in-depth: never let a sidebar-refresh failure interrupt
        // whatever core game logic called broadcast() (see the matching
        // comment in MahjongTable — same reasoning applies here).
        try {
            refreshSidebars();
        } catch (Throwable t) {
            plugin.getLogger().warning("[uno] refreshSidebars() 發生非預期例外，已略過（不影響遊戲本身）: " + t);
        }
    }

    /** Rebuilds and re-sends the live sidebar for every online human player at this table. */
    public synchronized void refreshSidebars() {
        if (phase != Phase.PLAYING) return;
        boolean serverDefault = plugin.getConfig().getBoolean("uno.sidebar-enabled", false);
        Card top = getTopCard();
        Card.Color color = getCurrentColor();
        UnoPlayer current = getCurrentPlayer();
        for (UnoPlayer p : players) {
            if (p.isBot) continue;
            Player bukkit = Bukkit.getPlayer(p.uuid);
            if (bukkit == null) continue;
            if (!sidebarPrefs.isEnabled(p.uuid, serverDefault)) continue;

            java.util.List<String> lines = new java.util.ArrayList<>();
            if (top != null) lines.add("牌面: " + top.display());
            if (color != null) lines.add("顏色: " + colorName(color));
            if (current != null) lines.add("輪到: " + (current == p ? "你" : current.name));
            lines.add("牌堆剩餘: " + getRemainingCards() + "張");
            lines.add("你的手牌: " + p.hand.size() + "張");
            lines.add("你的分數: " + p.score);
            for (UnoPlayer other : players) {
                if (other == p) continue;
                lines.add(other.name + ": " + other.hand.size() + "張");
            }

            com.fkc.game.core.ScoreboardUtil.show(bukkit, "UNO", lines);
        }
    }

    /** Removes the sidebar for every human player at this table. */
    public synchronized void clearSidebars() {
        for (UnoPlayer p : players) {
            if (p.isBot) continue;
            Player bukkit = Bukkit.getPlayer(p.uuid);
            if (bukkit != null) com.fkc.game.core.ScoreboardUtil.clear(bukkit);
        }
    }
}
