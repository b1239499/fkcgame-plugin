package com.fkc.game.mahjong.game;

import com.fkc.game.mahjong.model.GamePlayer;
import com.fkc.game.mahjong.model.GameSettings;
import com.fkc.game.mahjong.model.Meld;
import com.fkc.game.mahjong.model.Tile;
import com.fkc.game.mahjong.model.Wall;
import com.fkc.game.mahjong.score.HandEvaluator;
import com.fkc.game.mahjong.score.TaiwanHandEvaluator;
import com.fkc.game.mahjong.score.WinChecker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MahjongTable {

    public enum Phase { WAITING, PLAYING, ENDED }

    public final UUID id;
    public final Plugin plugin;
    public final com.fkc.game.core.Leaderboard leaderboard;
    private final com.fkc.game.core.PlayerPreferenceStore sidebarPrefs;
    /** Per-player text-vs-image tile display preference. Defaults to a fresh, private instance so existing callers/tests that don't wire this up still compile and work fine (text mode); TableManager overrides this with its own shared instance so the preference persists across tables in the same server session. */
    public com.fkc.game.core.PlayerPreferenceStore tileDisplayPrefs = new com.fkc.game.core.PlayerPreferenceStore();
    public com.fkc.game.core.RewardItemStore rewardStore;
    public final long createdAt = System.currentTimeMillis();
    /** Updated every time the table (re)enters WAITING — used for the idle-timeout check,
     *  kept separate from createdAt (which stays fixed for /mj list sort order). */
    public long waitingSince = System.currentTimeMillis();
    public final List<GamePlayer> players = new ArrayList<>();
    public final GameSettings settings = new GameSettings();
    public final TaiwanHandEvaluator.TaiSettings taiSettings = new TaiwanHandEvaluator.TaiSettings();
    public Phase phase = Phase.WAITING;
    public boolean exchangeThreeEnabled = false;

    public enum BroadcastCategory { ROUND_START, DISCARD, CALL, WIN, DRAW, GAME_END, EXCHANGE }
    /** Which categories of table messages also get echoed to every online player, not just the table. */
    public final java.util.Map<BroadcastCategory, Boolean> serverBroadcast = new java.util.EnumMap<>(BroadcastCategory.class);
    {
        for (BroadcastCategory c : BroadcastCategory.values()) serverBroadcast.put(c, false);
    }

    private Wall wall;
    private int dealerIndex = 0;
    private int dealerRotations = 0;
    private int currentIndex = 0;
    private int roundWind = 1;
    private int honba = 0;
    private Tile lastDiscard;
    private GamePlayer lastDiscarder;
    private boolean lastDiscardFromKan = false;

    private final Map<UUID, List<PossibleCall>> pendingCalls = new ConcurrentHashMap<>();
    private final Map<UUID, PossibleCall> chosenCalls = new ConcurrentHashMap<>();
    private boolean windowOpen = false;
    /**
     * Incremented every time a new call window opens. Embedded into every
     * clickable pon/chi/kan/ron/pass button's command so a stale click from
     * an already-resolved window (e.g. a player mashing the [碰] button
     * several times) can be detected and silently ignored instead of
     * accidentally being applied to whatever NEW call window happens to be
     * open by the time the stale click is actually processed.
     */
    private int callWindowGeneration = 0;

    private boolean inExchangePhase = false;
    private final Map<UUID, List<Tile>> exchangeSelections = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> exchangeConfirmed = ConcurrentHashMap.newKeySet();

    public MahjongTable(Plugin plugin, UUID id, com.fkc.game.core.Leaderboard leaderboard, com.fkc.game.core.PlayerPreferenceStore sidebarPrefs) {
        this.plugin = plugin;
        this.id = id;
        this.leaderboard = leaderboard;
        this.sidebarPrefs = sidebarPrefs;
    }

    // -----------------------------------------------------------
    // Lobby management
    // -----------------------------------------------------------

    public boolean addPlayer(UUID uuid, String name, boolean bot) {
        if (players.size() >= 4) return false;
        if (players.stream().anyMatch(p -> p.uuid.equals(uuid))) return false;
        GamePlayer gp = new GamePlayer(uuid, name, bot);
        gp.ready = bot;
        players.add(gp);
        return true;
    }

    public synchronized void removePlayer(UUID uuid) {
        players.removeIf(p -> p.uuid.equals(uuid));
    }

    public GamePlayer get(UUID uuid) {
        return players.stream().filter(p -> p.uuid.equals(uuid)).findFirst().orElse(null);
    }

    public synchronized void toggleReady(UUID uuid) {
        GamePlayer p = get(uuid);
        if (p != null) p.ready = !p.ready;
    }

    public boolean canStart() {
        return players.size() == 4 && players.stream().allMatch(p -> p.ready);
    }

    // -----------------------------------------------------------
    // Hand lifecycle
    // -----------------------------------------------------------

    public synchronized void startGame() {
        phase = Phase.PLAYING;
        dealerIndex = 0;
        dealerRotations = 0;
        roundWind = 1;
        honba = 0;
        int startScore = settings.ruleSet == GameSettings.RuleSet.TAIWANESE ? settings.taiwanStartingScore : settings.startingScore;
        for (GamePlayer p : players) p.score = startScore;
        startHand();
    }

    private void startHand() {
        boolean taiwan = settings.ruleSet == GameSettings.RuleSet.TAIWANESE;
        wall = taiwan ? new Wall(0, true) : new Wall(settings.redFiveCountPerSuit);
        int dealSize = taiwan ? 16 : 13;
        for (int i = 0; i < players.size(); i++) {
            GamePlayer p = players.get(i);
            p.hand.clear();
            p.melds.clear();
            p.discards.clear();
            p.flowers.clear();
            p.riichi = false;
            p.doubleRiichi = false;
            p.ippatsuEligible = false;
            p.menzen = true;
            p.seatWind = ((i - dealerIndex + 4) % 4) + 1;
            for (int t = 0; t < dealSize; t++) {
                Tile drawn = drawReplacingFlowers(p);
                if (drawn != null) p.hand.add(drawn);
            }
            p.sortHand();
        }
        currentIndex = dealerIndex;
        broadcast(Component.text("=== 新的一局開始！東" + (dealerIndex + 1) + "局 " + (honba) + "本場 ===", NamedTextColor.GOLD), BroadcastCategory.ROUND_START);

        if (exchangeThreeEnabled) {
            beginExchangePhase();
        } else {
            sendInitialHands();
            drawForCurrentPlayer();
        }
    }

    /**
     * Shows every human player their starting hand right after dealing,
     * instead of only the dealer (who sees theirs immediately anyway
     * because they draw first) — otherwise the other 3 players wouldn't
     * see their own hand at all until their first turn came around.
     */
    private void sendInitialHands() {
        for (GamePlayer p : players) {
            if (p.isBot) continue;
            Player bukkit = Bukkit.getPlayer(p.uuid);
            if (bukkit == null) continue;
            Component hand = Component.text("你的起手牌: ", NamedTextColor.WHITE);
            for (Tile t : p.hand) hand = hand.append(Component.text(render(t, p.uuid) + " ", NamedTextColor.WHITE));
            bukkit.sendMessage(hand);
        }
    }

    /**
     * Draws a tile for the given player, automatically setting aside and
     * replacing any flower tiles drawn along the way (補花). In Riichi mode
     * the wall never contains flower tiles, so this behaves exactly like a
     * plain draw() there — same method is safe to use everywhere for both
     * rulesets.
     */
    private Tile drawReplacingFlowers(GamePlayer p) {
        Tile drawn = wall.draw();
        while (drawn != null && drawn.isFlower()) {
            p.flowers.add(drawn);
            drawn = wall.draw();
        }
        return drawn;
    }

    // -----------------------------------------------------------
    // Exchange-three-tiles (換三張)
    // -----------------------------------------------------------

    private void beginExchangePhase() {
        inExchangePhase = true;
        exchangeSelections.clear();
        exchangeConfirmed.clear();
        broadcast(Component.text("本局開啟換三張！請選出 3 張牌交給下家。", NamedTextColor.LIGHT_PURPLE), BroadcastCategory.EXCHANGE);

        for (GamePlayer p : players) {
            exchangeSelections.put(p.uuid, new ArrayList<>());
            if (p.isBot) {
                List<Tile> pick = new ArrayList<>(p.hand.subList(0, 3));
                exchangeSelections.put(p.uuid, pick);
                exchangeConfirmed.add(p.uuid);
                continue;
            }
            sendExchangeHand(p);
        }
        maybeResolveExchange();
    }

    private void sendExchangeHand(GamePlayer p) {
        Player bukkit = Bukkit.getPlayer(p.uuid);
        if (bukkit == null) return;
        List<Tile> selected = exchangeSelections.get(p.uuid);
        Component hand = Component.text("你的手牌 (點選 3 張要換出的牌，已選 "
                + selected.size() + "/3): ", NamedTextColor.YELLOW);
        for (Tile t : p.hand) {
            boolean isSelected = false;
            for (Tile s : selected) if (s == t) { isSelected = true; break; }
            Component tileText = isSelected
                    ? Component.text("[", NamedTextColor.GREEN)
                            .append(Component.text(render(t, p.uuid), NamedTextColor.WHITE))
                            .append(Component.text("]", NamedTextColor.GREEN))
                    : Component.text(render(t, p.uuid), NamedTextColor.WHITE);
            hand = hand.append(tileText
                    .clickEvent(ClickEvent.runCommand("/mahjong action exchange " + t.typeIndex()))
                    .hoverEvent(HoverEvent.showText(Component.text("點擊切換選取 " + t.label()))))
                    .append(Component.text(" "));
        }
        bukkit.sendMessage(hand);
        if (selected.size() == 3) {
            bukkit.sendMessage(Component.text("[確認換牌]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/mahjong action exchangeconfirm")));
        }
    }

    public synchronized void toggleExchangeTile(GamePlayer p, Tile type) {
        if (!inExchangePhase || exchangeConfirmed.contains(p.uuid)) return;
        List<Tile> selected = exchangeSelections.get(p.uuid);
        Tile existing = null;
        for (Tile t : selected) if (t.equals(type)) { existing = t; break; }
        if (existing != null) {
            selected.remove(existing);
        } else if (selected.size() < 3 && count(p.hand, type) > 0) {
            selected.add(findActualTile(p, type));
        }
        sendExchangeHand(p);
    }

    private Tile findActualTile(GamePlayer p, Tile type) {
        for (Tile t : p.hand) if (t.equals(type)) return t;
        return type;
    }

    public synchronized void confirmExchange(GamePlayer p) {
        if (!inExchangePhase) return;
        Player bukkit = Bukkit.getPlayer(p.uuid);
        if (exchangeConfirmed.contains(p.uuid)) {
            if (bukkit != null) {
                bukkit.sendMessage(Component.text("你已經確認過換牌選擇了，正在等待其他人確認中...", NamedTextColor.GRAY));
            }
            return;
        }
        List<Tile> selected = exchangeSelections.get(p.uuid);
        if (selected.size() != 3) {
            if (bukkit != null) bukkit.sendMessage(Component.text("要剛好選 3 張才能確認。", NamedTextColor.RED));
            return;
        }
        exchangeConfirmed.add(p.uuid);
        if (bukkit != null) {
            bukkit.sendMessage(Component.text("✅ 已確認換牌選擇（" + exchangeConfirmed.size() + "/" + players.size()
                    + "），正在等待其他人確認中...", NamedTextColor.GREEN));
        }
        maybeResolveExchange();
    }

    private void maybeResolveExchange() {
        if (exchangeConfirmed.size() < players.size()) return;
        inExchangePhase = false;

        // Fixed direction: everyone passes 3 tiles to the next player (seat order),
        // and receives 3 tiles from the previous player. (Simplification — real
        // Taiwanese mahjong often rotates the direction each hand; not implemented.)
        Map<UUID, List<Tile>> incoming = new ConcurrentHashMap<>();
        for (int i = 0; i < players.size(); i++) {
            GamePlayer giver = players.get(i);
            GamePlayer receiver = players.get(nextIndex(i));
            List<Tile> given = exchangeSelections.get(giver.uuid);
            for (Tile t : given) giver.hand.remove(t);
            incoming.put(receiver.uuid, given);
        }
        for (GamePlayer p : players) {
            p.hand.addAll(incoming.get(p.uuid));
            p.sortHand();
        }
        broadcast(Component.text("換三張完成，開始正式對局！", NamedTextColor.GOLD), BroadcastCategory.EXCHANGE);
        for (GamePlayer p : players) {
            Player bukkit = Bukkit.getPlayer(p.uuid);
            if (bukkit == null) continue;
            Component hand = Component.text("換牌後你的手牌: ", NamedTextColor.WHITE);
            for (Tile t : p.hand) hand = hand.append(Component.text(render(t, p.uuid) + " ", NamedTextColor.WHITE));
            bukkit.sendMessage(hand);
        }
        drawForCurrentPlayer();
    }

    private GamePlayer currentPlayer() {
        return players.get(currentIndex);
    }

    /** Public accessor so /mahjong info can show whose turn it is. Returns null if the table isn't playing. */
    public GamePlayer getCurrentPlayer() {
        if (phase != Phase.PLAYING || players.isEmpty() || currentIndex < 0 || currentIndex >= players.size()) return null;
        return players.get(currentIndex);
    }

    /** Whether a pon/chi/kan/ron call window is currently open and unresolved. */
    public boolean isCallWindowOpen() {
        return windowOpen;
    }

    /** Public accessor so /mahjong info can show the most recent discard. Returns null if none yet this hand. */
    public Tile getLastDiscard() {
        return lastDiscard;
    }

    public GamePlayer getLastDiscarder() {
        return lastDiscarder;
    }

    /**
     * Renders a tile for the given viewer according to THEIR personal
     * text/image display preference (see /mahjong tiles). Used for
     * "only-you-see-this" messages (your hand, tenpai, sidebar, reconnect
     * resync). Broadcast messages seen by the whole table (discards,
     * calls, wins) deliberately stay text-only for simplicity — building
     * a genuinely different Component per recipient for every broadcast
     * would be a much bigger change for comparatively little benefit,
     * since those are informational rather than something you need to
     * read your own tiles off of.
     */
    public String render(Tile t, UUID viewerUuid) {
        boolean serverDefault = plugin.getConfig().getBoolean("mahjong.tiles-image-default", false);
        return tileDisplayPrefs.isEnabled(viewerUuid, serverDefault) ? t.imageGlyph() : t.display();
    }

    /** Dora indicators currently revealed (Riichi mode only — always empty in Taiwanese mode, which has no dora concept). */
    public List<Tile> getDoraIndicators() {
        return wall != null ? wall.revealedDoraIndicators() : List.of();
    }

    /** How many tiles are left in the live wall to draw from. */
    public int getRemainingTiles() {
        return wall != null ? wall.remaining() : 0;
    }

    /**
     * How many copies of this tile type are still "live" (not visibly
     * accounted for) from this viewer's perspective — 4 total per tile type
     * minus whatever this viewer can actually see: their own hand, every
     * player's discard pile, every OPEN meld (chi/pon/minkan/kakan), and
     * revealed dora indicators. Deliberately excludes other players'
     * concealed kan (ankan) tiles from the visible count — an opponent's
     * ankan must not leak information even indirectly through a remaining-
     * tile-count hint, matching the same concealment principle used
     * elsewhere for ankan.
     */
    public int remainingCount(Tile t, GamePlayer viewer) {
        int visible = count(viewer.hand, t);
        for (GamePlayer p : players) {
            visible += count(p.discards, t);
            for (Meld m : p.melds) {
                if (m.isConcealed() && !p.uuid.equals(viewer.uuid)) continue; // hide opponents' ankan
                visible += count(m.tiles, t);
            }
        }
        visible += count(getDoraIndicators(), t);
        return Math.max(0, 4 - visible);
    }

    private int nextIndex(int i) {
        return (i + 1) % 4;
    }

    void drawForCurrentPlayer() {
        if (wall.isEmpty()) {
            endHandDraw();
            return;
        }
        GamePlayer p = currentPlayer();
        Tile drawn = drawReplacingFlowers(p);
        if (drawn == null) {
            endHandDraw();
            return;
        }
        p.hand.add(drawn);
        p.sortHand();

        if (p.isBot) {
            botTurn(p, drawn);
            return;
        }

        Player bukkitPlayer = Bukkit.getPlayer(p.uuid);
        if (bukkitPlayer == null) {
            // Disconnected human — auto-discard the drawn tile to keep the game moving.
            discard(p, drawn, false);
            return;
        }

        boolean canTsumo = meetsMinimumToWin(p, p.hand, true);

        if (canTsumo && p.autoWin) {
            bukkitPlayer.sendMessage(Component.text("你摸到了 ", NamedTextColor.GOLD)
                    .append(Component.text(render(drawn, p.uuid), NamedTextColor.WHITE))
                    .append(Component.text("——託管中，自動自摸！", NamedTextColor.GOLD)));
            declareTsumo(p);
            return;
        }

        if (p.autoWin) {
            bukkitPlayer.sendMessage(Component.text("你摸到了 ", NamedTextColor.AQUA)
                    .append(Component.text(render(drawn, p.uuid), NamedTextColor.WHITE))
                    .append(Component.text("——託管中，自動打出剛摸到的牌。", NamedTextColor.GRAY)));
            discard(p, drawn, false);
            return;
        }

        Component msg = Component.text("你摸到了 ", NamedTextColor.AQUA).append(Component.text(render(drawn, p.uuid), NamedTextColor.WHITE));
        bukkitPlayer.sendMessage(msg);

        if (!p.flowers.isEmpty()) {
            Component flowerMsg = Component.text("你的花牌: ", NamedTextColor.LIGHT_PURPLE);
            for (Tile f : p.flowers) flowerMsg = flowerMsg.append(Component.text(render(f, p.uuid) + " ", NamedTextColor.WHITE));
            bukkitPlayer.sendMessage(flowerMsg);
        }

        Component hand = Component.text("你的手牌: ", NamedTextColor.WHITE);
        for (Tile t : p.hand) {
            hand = hand.append(clickableDiscard(t, p.uuid));
            hand = hand.append(Component.text(" "));
        }
        bukkitPlayer.sendMessage(hand);

        if (canTsumo) {
            bukkitPlayer.sendMessage(Component.text("[自摸胡牌]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/mahjong action tsumo")));
        }
        if (canAnkan(p) != null) {
            bukkitPlayer.sendMessage(Component.text("你可以暗槓，輸入 /mahjong action ankan <牌> 宣告", NamedTextColor.GRAY));
        }
        if (settings.ruleSet == GameSettings.RuleSet.RIICHI && p.menzen && !p.riichi && p.score >= 1000) {
            List<Tile> riichiOptions = riichiEligibleDiscards(p);
            if (!riichiOptions.isEmpty()) {
                Component riichiMsg = Component.text("可立直: ", NamedTextColor.LIGHT_PURPLE);
                for (Tile t : riichiOptions) {
                    riichiMsg = riichiMsg.append(Component.text("[立直打", NamedTextColor.GREEN)
                            .append(Component.text(render(t, p.uuid), NamedTextColor.WHITE))
                            .append(Component.text("]", NamedTextColor.GREEN))
                            .clickEvent(ClickEvent.runCommand("/mahjong action riichi " + t.typeIndex())))
                            .append(Component.text(" "));
                }
                bukkitPlayer.sendMessage(riichiMsg);
            }
        }
    }

    /** Waits for the given (already "waiting-sized") hand, dispatching to whichever ruleset's engine this table uses. */
    public List<Tile> tenpaiWaits(List<Tile> hand, List<Meld> melds) {
        if (settings.ruleSet == GameSettings.RuleSet.TAIWANESE) {
            return TaiwanHandEvaluator.tenpaiWaits(hand, melds);
        }
        return WinChecker.tenpaiWaits(hand, melds);
    }

    /**
     * Expected concealed hand size when NOT mid-turn (i.e. the "waiting"
     * state a tenpai check normally applies to): 13-3*melds for Riichi,
     * 16-3*melds for Taiwanese.
     */
    public int waitingHandSize(GamePlayer p) {
        int base = settings.ruleSet == GameSettings.RuleSet.TAIWANESE ? 16 : 13;
        return base - 3 * p.melds.size();
    }

    /**
     * For a player who just drew (hand is one tile larger than the normal
     * "waiting" size) — for each distinct tile they could discard, what
     * would they end up waiting on? Only discards that keep them tenpai
     * are included.
     */
    public java.util.LinkedHashMap<Tile, List<Tile>> discardOptionsForTenpai(GamePlayer p) {
        java.util.LinkedHashMap<Tile, List<Tile>> result = new java.util.LinkedHashMap<>();
        List<Tile> distinct = new ArrayList<>();
        for (Tile t : p.hand) {
            boolean seen = false;
            for (Tile d : distinct) if (d.equals(t)) { seen = true; break; }
            if (!seen) distinct.add(t);
        }
        for (Tile candidate : distinct) {
            List<Tile> test = new ArrayList<>(p.hand);
            test.remove(candidate);
            List<Tile> waits = tenpaiWaits(test, p.melds);
            if (!waits.isEmpty()) result.put(candidate, waits);
        }
        return result;
    }

    private List<Tile> riichiEligibleDiscards(GamePlayer p) {
        List<Tile> options = new ArrayList<>();
        List<Tile> distinct = new ArrayList<>();
        for (Tile t : p.hand) {
            boolean seen = false;
            for (Tile d : distinct) if (d.equals(t)) { seen = true; break; }
            if (!seen) distinct.add(t);
        }
        for (Tile candidate : distinct) {
            List<Tile> test = new ArrayList<>(p.hand);
            test.remove(candidate);
            if (WinChecker.isTenpai(test, p.melds)) {
                options.add(candidate);
            }
        }
        return options;
    }

    private void botTurn(GamePlayer bot, Tile drawn) {
        if (meetsMinimumToWin(bot, bot.hand, true)) {
            resolveTsumo(bot);
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> discard(bot, drawn, false), 20L);
    }

    private Component clickableDiscard(Tile t, UUID viewerUuid) {
        return Component.text(render(t, viewerUuid), NamedTextColor.WHITE)
                .clickEvent(ClickEvent.runCommand("/mahjong action discard " + t.typeIndex()))
                .hoverEvent(HoverEvent.showText(Component.text("打出 " + t.label())));
    }

    /**
     * Builds a "你的手牌: [clickable tiles]" Component for the given player,
     * same clickable-discard buttons used during their actual turn. Safe to
     * show even when it isn't their turn — discard() itself already rejects
     * out-of-turn attempts with a clear error message, so clicking one early
     * (e.g. from /mahjong info) just fails gracefully instead of doing
     * anything harmful.
     */
    public Component renderClickableHand(GamePlayer p) {
        Component hand = Component.text("你的手牌: ", NamedTextColor.WHITE);
        for (Tile t : p.hand) {
            hand = hand.append(clickableDiscard(t, p.uuid)).append(Component.text(" "));
        }
        return hand;
    }

    // -----------------------------------------------------------
    // Discard + call window
    // -----------------------------------------------------------

    public synchronized void discard(GamePlayer p, Tile tile, boolean declareRiichi) {
        if (inExchangePhase) return; // exchange-three hasn't fully resolved yet — discarding must wait until real play begins
        if (players.get(currentIndex) != p) return; // not this player's turn — likely a stale button click from chat scrollback
        if (windowOpen) return; // a call window from an earlier discard hasn't resolved yet — rapid double-discard by the same player
        if (!p.hand.remove(tile)) return;
        p.sortHand();
        p.discards.add(tile);
        lastDiscard = tile;
        lastDiscarder = p;
        lastDiscardFromKan = false;
        if (declareRiichi) {
            p.riichi = true;
            p.ippatsuEligible = true;
        } else {
            p.ippatsuEligible = false;
        }

        broadcast(viewerUuid -> Component.text(p.name + " 打出了 ", NamedTextColor.WHITE)
                .append(Component.text(render(tile, viewerUuid), NamedTextColor.WHITE)), BroadcastCategory.DISCARD);
        openCallWindow(tile, p);
    }

    private void openCallWindow(Tile discardTile, GamePlayer discarder) {
        pendingCalls.clear();
        chosenCalls.clear();
        windowOpen = true;
        int myGeneration = ++callWindowGeneration;
        List<GamePlayer> autoRonPlayers = new ArrayList<>();

        for (GamePlayer p : players) {
            if (p.uuid.equals(discarder.uuid) || p.isBot) continue;
            List<PossibleCall> options = new ArrayList<>();

            List<Tile> testHand = new ArrayList<>(p.hand);
            testHand.add(discardTile);
            if (meetsMinimumToWin(p, testHand, false)) {
                options.add(new PossibleCall(p.uuid, PossibleCall.Type.RON, List.of()));
            }
            if (count(p.hand, discardTile) >= 2) {
                options.add(new PossibleCall(p.uuid, PossibleCall.Type.PON, List.of()));
            }
            if (count(p.hand, discardTile) >= 3) {
                options.add(new PossibleCall(p.uuid, PossibleCall.Type.MINKAN, List.of()));
            }
            if (players.get(nextIndex(indexOf(discarder))).uuid.equals(p.uuid)) {
                options.addAll(chiOptions(p, discardTile));
            }

            if (!options.isEmpty()) {
                pendingCalls.put(p.uuid, options);
                boolean hasRon = options.stream().anyMatch(c -> c.type == PossibleCall.Type.RON);
                if (hasRon && p.autoWin) {
                    // 託管: defer the actual call until AFTER this whole
                    // window has been fully built (see below) — resolving
                    // mid-loop here would risk clearing pendingCalls/
                    // chosenCalls while still building options for OTHER
                    // players later in this same loop.
                    autoRonPlayers.add(p);
                    continue;
                }
                Player bukkit = Bukkit.getPlayer(p.uuid);
                if (bukkit != null) {
                    Component msg = Component.text(discarder.name + " 打出 " + discardTile.display() + "，你可以: ", NamedTextColor.LIGHT_PURPLE);
                    bukkit.sendMessage(msg);
                    for (PossibleCall call : options) {
                        String cmd = "/mahjong action call " + call.type.name().toLowerCase() + " " + myGeneration
                                + (call.type == PossibleCall.Type.CHI ? " " + options.indexOf(call) : "");
                        bukkit.sendMessage(Component.text("[" + call.label() + "]", NamedTextColor.GREEN)
                                .clickEvent(ClickEvent.runCommand(cmd)));
                    }
                    bukkit.sendMessage(Component.text("[跳過]", NamedTextColor.GRAY)
                            .clickEvent(ClickEvent.runCommand("/mahjong action pass " + myGeneration)));
                }
            }
        }

        if (pendingCalls.isEmpty()) {
            windowOpen = false;
            advanceTurnAfterDiscard();
            return;
        }

        // Now that the window is fully built, process any 託管 auto-ron
        // responses. This may itself resolve the whole window (e.g. if
        // this was the only pending player) — that's fine, everything
        // needed is already in place.
        for (GamePlayer p : autoRonPlayers) {
            if (!windowOpen) break; // window already resolved by an earlier auto-ron in this same list
            Player bukkit = Bukkit.getPlayer(p.uuid);
            if (bukkit != null) {
                bukkit.sendMessage(Component.text(discarder.name + " 打出 " + discardTile.display()
                        + "——託管中，自動胡！", NamedTextColor.GOLD));
            }
            handleCallAction(p.uuid, "ron", myGeneration, "");
        }

        if (!windowOpen) return; // fully resolved via auto-ron already

        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,
                task -> {
                    // This task was scheduled 15 (configurable) seconds ago
                    // for THIS specific window (myGeneration). If that
                    // window already resolved early (nobody had options,
                    // or everyone responded quickly) and a NEWER window
                    // has since opened, callWindowGeneration will have
                    // moved past myGeneration — in that case this is a
                    // leftover, orphaned timeout and must NOT touch the
                    // new window at all. Without this check, a late-firing
                    // old timeout could prematurely cut a brand new
                    // window's time short, sometimes almost immediately —
                    // exactly the "no time to click" symptom reported.
                    if (myGeneration == callWindowGeneration) resolveCallWindow();
                }, 20L * settings.thinkingTimeSeconds);
    }

    private List<PossibleCall> chiOptions(GamePlayer p, Tile discardTile) {
        List<PossibleCall> result = new ArrayList<>();
        if (!discardTile.isNumber()) return result;
        int n = discardTile.number;
        int[][] patterns = {{-2, -1}, {-1, 1}, {1, 2}};
        for (int[] pat : patterns) {
            int a = n + pat[0], b = n + pat[1];
            if (a < 1 || a > 9 || b < 1 || b > 9) continue;
            Tile ta = new Tile(discardTile.suit, a);
            Tile tb = new Tile(discardTile.suit, b);
            if (contains(p.hand, ta) && contains(p.hand, tb)) {
                result.add(new PossibleCall(p.uuid, PossibleCall.Type.CHI, List.of(ta, tb)));
            }
        }
        return result;
    }

    /** Convenience overload for typed shorthand commands (e.g. "/mj 碰") that don't carry an explicit generation — those are freshly typed in the moment, so they always target whatever window is currently open. */
    public synchronized void handleCallAction(UUID uuid, String rawVerb, String arg) {
        handleCallAction(uuid, rawVerb, callWindowGeneration, arg);
    }

    private void notifyCallRejected(UUID uuid, String reason) {
        Player bukkit = Bukkit.getPlayer(uuid);
        if (bukkit != null) bukkit.sendMessage(Component.text(reason, NamedTextColor.RED));
    }

    public synchronized void handleCallAction(UUID uuid, String rawVerb, int generation, String arg) {
        GamePlayer actor = get(uuid);
        String actorName = actor != null ? actor.name : uuid.toString();

        if (!windowOpen) {
            notifyCallRejected(uuid, "剛才那個叫牌時機已經結束了（視窗已關閉），慢了一步。");
            plugin.getLogger().info("[mahjong] " + actorName + " 的 " + rawVerb + " 被拒絕：視窗已關閉（世代 " + generation + "，目前世代 " + callWindowGeneration + "）");
            return;
        }
        if (generation != callWindowGeneration) {
            // Stale click from an older, already-resolved window — most
            // likely a genuine timing race (e.g. another player's response
            // closed this exact window a moment before this click was
            // processed) rather than a truly "old" leftover button.
            notifyCallRejected(uuid, "剛才那個叫牌時機已經結束了（可能剛好被別人同時關閉），慢了一步。");
            plugin.getLogger().info("[mahjong] " + actorName + " 的 " + rawVerb + " 被拒絕：世代不符（送出時 " + generation + "，目前世代 " + callWindowGeneration + "）");
            return;
        }
        List<PossibleCall> options = pendingCalls.get(uuid);
        if (options == null) {
            notifyCallRejected(uuid, "你目前沒有可以回應的叫牌選項。");
            plugin.getLogger().info("[mahjong] " + actorName + " 的 " + rawVerb + " 被拒絕：不在待回應名單中（世代 " + generation + "）");
            return;
        }

        String verb = switch (rawVerb) {
            case "胡", "和", "榮和", "荣和" -> "ron";
            case "碰" -> "pon";
            case "槓", "杠" -> "minkan";
            case "吃" -> "chi";
            case "跳過", "跳过", "過", "过" -> "pass";
            default -> rawVerb.toLowerCase();
        };

        if (verb.equals("pass")) {
            pendingCalls.remove(uuid);
        } else {
            PossibleCall.Type type;
            try {
                type = PossibleCall.Type.valueOf(verb.toUpperCase());
            } catch (IllegalArgumentException e) {
                return;
            }
            PossibleCall chosen = null;
            for (PossibleCall c : options) {
                if (c.type != type) continue;
                if (type == PossibleCall.Type.CHI) {
                    int idx = -1;
                    try { idx = Integer.parseInt(arg); } catch (Exception ignored) {}
                    if (idx >= 0 && idx < options.size() && options.get(idx) == c) { chosen = c; break; }
                } else {
                    chosen = c;
                    break;
                }
            }
            if (chosen != null) {
                chosenCalls.put(uuid, chosen);
                pendingCalls.remove(uuid);
            } else {
                notifyCallRejected(uuid, "這個選項目前對你無效（可能牌型/座位不符合資格）。");
                plugin.getLogger().info("[mahjong] " + actorName + " 的 " + rawVerb + " 被拒絕：不是有效選項（世代 " + generation + "）");
            }
        }

        if (pendingCalls.isEmpty()) {
            resolveCallWindow();
        }
    }

    private synchronized void resolveCallWindow() {
        if (!windowOpen) return;
        windowOpen = false;
        pendingCalls.clear();

        PossibleCall ron = chosenCalls.values().stream().filter(c -> c.type == PossibleCall.Type.RON).findFirst().orElse(null);
        if (ron != null) {
            GamePlayer winner = get(ron.player);
            resolveRon(winner, lastDiscarder);
            chosenCalls.clear();
            return;
        }

        PossibleCall kan = chosenCalls.values().stream().filter(c -> c.type == PossibleCall.Type.MINKAN).findFirst().orElse(null);
        if (kan != null) {
            performMinkan(get(kan.player));
            chosenCalls.clear();
            return;
        }

        PossibleCall pon = chosenCalls.values().stream().filter(c -> c.type == PossibleCall.Type.PON).findFirst().orElse(null);
        if (pon != null) {
            performPon(get(pon.player));
            chosenCalls.clear();
            return;
        }

        PossibleCall chi = chosenCalls.values().stream().filter(c -> c.type == PossibleCall.Type.CHI).findFirst().orElse(null);
        if (chi != null) {
            performChi(get(chi.player), chi);
            chosenCalls.clear();
            return;
        }

        chosenCalls.clear();
        advanceTurnAfterDiscard();
    }

    private void performPon(GamePlayer p) {
        List<Tile> tiles = new ArrayList<>();
        p.hand.remove(lastDiscard);
        p.hand.remove(lastDiscard);
        tiles.add(lastDiscard); tiles.add(lastDiscard); tiles.add(lastDiscard);
        p.melds.add(new Meld(Meld.Type.PON, tiles, lastDiscarder.uuid));
        p.menzen = false;
        broadcast(viewerUuid -> Component.text(p.name + " 碰了 ", NamedTextColor.YELLOW)
                .append(Component.text(render(lastDiscard, viewerUuid), NamedTextColor.WHITE)), BroadcastCategory.CALL);
        setTurnTo(p);
        promptDiscardOnly(p);
    }

    private void performMinkan(GamePlayer p) {
        List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < 3; i++) p.hand.remove(lastDiscard);
        tiles.add(lastDiscard); tiles.add(lastDiscard); tiles.add(lastDiscard); tiles.add(lastDiscard);
        p.melds.add(new Meld(Meld.Type.MINKAN, tiles, lastDiscarder.uuid));
        p.menzen = false;
        wall.revealNextDoraIndicator();
        broadcast(viewerUuid -> Component.text(p.name + " 明槓了 ", NamedTextColor.YELLOW)
                .append(Component.text(render(lastDiscard, viewerUuid), NamedTextColor.WHITE)), BroadcastCategory.CALL);
        setTurnTo(p);
        Tile replacement = drawReplacingFlowers(p);
        if (replacement != null) {
            p.hand.add(replacement);
            p.sortHand();
            Player bukkitReplacement = Bukkit.getPlayer(p.uuid);
            if (bukkitReplacement != null) {
                bukkitReplacement.sendMessage(Component.text("你摸到了嶺上牌 ", NamedTextColor.AQUA)
                        .append(Component.text(render(replacement, p.uuid), NamedTextColor.WHITE)));
            }
        }
        promptDiscardOnly(p);
    }

    private void performChi(GamePlayer p, PossibleCall chi) {
        List<Tile> tiles = new ArrayList<>(chi.handTilesUsed);
        for (Tile t : chi.handTilesUsed) p.hand.remove(t);
        tiles.add(lastDiscard);
        p.melds.add(new Meld(Meld.Type.CHI, tiles, lastDiscarder.uuid));
        p.menzen = false;
        broadcast(viewerUuid -> Component.text(p.name + " 吃了 ", NamedTextColor.YELLOW)
                .append(Component.text(render(lastDiscard, viewerUuid), NamedTextColor.WHITE)), BroadcastCategory.CALL);
        setTurnTo(p);
        promptDiscardOnly(p);
    }

    private void promptDiscardOnly(GamePlayer p) {
        Player bukkit = Bukkit.getPlayer(p.uuid);
        if (bukkit == null || p.isBot) {
            if (p.isBot && !p.hand.isEmpty()) {
                Tile t = p.hand.get(0);
                plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> discard(p, t, false), 20L);
            }
            return;
        }
        if (p.autoWin && !p.hand.isEmpty()) {
            // 託管 also applies here — pon/minkan/ankan all funnel through this
            // method, and none of them go through drawForCurrentPlayer()'s own
            // auto-discard branch, so without this an autoWin player would get
            // stuck waiting for a manual discard right after a call or kan.
            // There's no single "just drawn" tile to tsumogiri after a PON
            // specifically (no new tile enters hand there), so this uses the
            // same simple, deterministic strategy the bot auto-discard logic
            // already uses for consistency, rather than inventing a smarter
            // one just for this path.
            Tile t = p.hand.get(0);
            bukkit.sendMessage(Component.text("託管中，自動打出 ", NamedTextColor.GRAY)
                    .append(Component.text(render(t, p.uuid), NamedTextColor.WHITE))
                    .append(Component.text("。", NamedTextColor.GRAY)));
            discard(p, t, false);
            return;
        }
        Component hand = Component.text("你的手牌: ", NamedTextColor.WHITE);
        for (Tile t : p.hand) {
            hand = hand.append(clickableDiscard(t, p.uuid)).append(Component.text(" "));
        }
        bukkit.sendMessage(hand);
    }

    private void setTurnTo(GamePlayer p) {
        currentIndex = indexOf(p);
    }

    private void advanceTurnAfterDiscard() {
        currentIndex = nextIndex(indexOf(lastDiscarder));
        drawForCurrentPlayer();
    }

    // -----------------------------------------------------------
    // Win / draw resolution
    // -----------------------------------------------------------

    /**
     * Full check for whether a declared win is actually ALLOWED to be
     * claimed — shape AND minimum tai/han threshold. This is deliberately
     * separate from {@link #canWin}, which only checks shape (used for the
     * general "would this tile complete your hand" question, e.g. offering
     * a ron option) — canWin alone is NOT enough to decide whether an
     * actual tsumo/ron declaration should be accepted, because a
     * shape-complete hand can still fall short of the minimum tai/han
     * needed to legally win. Mixing these up previously caused a real bug:
     * a shape-valid-but-under-minimum Taiwanese hand would pass the
     * shape-only check, let the player declare tsumo, and then get
     * rejected deep inside the resolution code — which incorrectly ended
     * the entire hand as a forced draw instead of just telling the player
     * their declaration didn't meet the minimum and letting play continue.
     */
    private boolean meetsMinimumToWin(GamePlayer p, List<Tile> hand, boolean tsumo) {
        if (settings.ruleSet == GameSettings.RuleSet.TAIWANESE) {
            TaiwanHandEvaluator.WinResult result = TaiwanHandEvaluator.evaluate(hand, p.melds, p.flowers, taiwanContextFor(p, tsumo), taiSettings);
            return result.valid && result.totalTai >= settings.minTai;
        }
        HandEvaluator.WinResult result = HandEvaluator.evaluate(hand, p.melds, contextFor(p, tsumo));
        return result.valid && result.totalHan >= settings.minHan;
    }

    public synchronized void declareTsumo(GamePlayer p) {
        if (inExchangePhase) return;
        if (players.get(currentIndex) != p) return;
        if (windowOpen) return; // a call window from an earlier discard hasn't resolved yet
        if (!canWin(p, p.hand, true)) {
            Player bukkit = Bukkit.getPlayer(p.uuid);
            if (bukkit != null) bukkit.sendMessage(Component.text("目前牌型還不能自摸胡牌。", NamedTextColor.RED));
            return;
        }
        if (!meetsMinimumToWin(p, p.hand, true)) {
            Player bukkit = Bukkit.getPlayer(p.uuid);
            String unit = settings.ruleSet == GameSettings.RuleSet.TAIWANESE ? "台" : "翻";
            if (bukkit != null) {
                bukkit.sendMessage(Component.text("牌型已經湊齊，但沒有役（不夠最低" + unit + "數），還不能胡牌，請繼續正常打牌。", NamedTextColor.RED));
            }
            return; // reject the declaration only — the hand/table continues normally, nobody else is affected
        }
        resolveTsumo(p);
    }

    private void resolveTsumo(GamePlayer winner) {
        if (settings.ruleSet == GameSettings.RuleSet.TAIWANESE) {
            resolveTsumoTaiwan(winner);
            return;
        }
        HandEvaluator.WinContext ctx = contextFor(winner, true);
        HandEvaluator.WinResult result = HandEvaluator.evaluate(winner.hand, winner.melds, ctx);
        if (!result.valid || result.totalHan < settings.minHan) {
            // Should be unreachable — declareTsumo() already validates this
            // before ever calling resolveTsumo(). Kept as a safety net: if
            // it somehow still happens, reject quietly rather than forcing
            // a draw for the whole table (that was the actual bug here).
            Player bukkit = Bukkit.getPlayer(winner.uuid);
            if (bukkit != null) bukkit.sendMessage(Component.text("胡牌條件不足，請繼續打牌。", NamedTextColor.RED));
            return;
        }
        broadcast(Component.text(winner.name + " 自摸！ " + result.summary(), NamedTextColor.GOLD), BroadcastCategory.WIN);
        distributeTsumoPoints(winner, result.points);
        finishHand(winner == players.get(dealerIndex));
    }

    private void resolveTsumoTaiwan(GamePlayer winner) {
        TaiwanHandEvaluator.WinContext ctx = taiwanContextFor(winner, true);
        TaiwanHandEvaluator.WinResult result = TaiwanHandEvaluator.evaluate(winner.hand, winner.melds, winner.flowers, ctx, taiSettings);
        if (!result.valid || result.totalTai < settings.minTai) {
            // Should be unreachable — declareTsumo() already validates this
            // before ever calling resolveTsumo(). Kept as a safety net: if
            // it somehow still happens, reject quietly rather than forcing
            // a draw for the whole table (that was the actual bug here —
            // it used to call endHandDraw(), silently ending the hand for
            // everyone whenever a shape-complete-but-under-minimum-tai
            // hand got this far).
            Player bukkit = Bukkit.getPlayer(winner.uuid);
            if (bukkit != null) bukkit.sendMessage(Component.text("胡牌條件不足，請繼續打牌。", NamedTextColor.RED));
            return;
        }
        broadcast(Component.text(winner.name + " 自摸！ " + result.summary(), NamedTextColor.GOLD), BroadcastCategory.WIN);
        // Taiwan convention used here: each of the 3 opponents pays the
        // computed points flat (which already includes the dealer
        // multiplier when the WINNER is dealer). Exact tsumo-payment
        // conventions vary by house rule; this is a simple, transparent
        // choice, not a claim of universal correctness.
        distributeTsumoPoints(winner, result.points);
        finishHand(winner == players.get(dealerIndex));
    }

    private void resolveRon(GamePlayer winner, GamePlayer loser) {
        if (settings.ruleSet == GameSettings.RuleSet.TAIWANESE) {
            resolveRonTaiwan(winner, loser);
            return;
        }
        List<Tile> hand = new ArrayList<>(winner.hand);
        hand.add(lastDiscard);
        HandEvaluator.WinContext ctx = contextFor(winner, false);
        HandEvaluator.WinResult result = HandEvaluator.evaluate(hand, winner.melds, ctx);
        if (!result.valid || result.totalHan < settings.minHan) {
            broadcast(Component.text("宣告失敗，牌型不成立或翻數不足", NamedTextColor.RED), BroadcastCategory.WIN);
            advanceTurnAfterDiscard();
            return;
        }
        broadcast(Component.text(winner.name + " 榮和 " + loser.name + "！ " + result.summary(), NamedTextColor.GOLD), BroadcastCategory.WIN);
        winner.score += result.points;
        loser.score -= result.points;
        finishHand(winner == players.get(dealerIndex));
    }

    private void resolveRonTaiwan(GamePlayer winner, GamePlayer loser) {
        List<Tile> hand = new ArrayList<>(winner.hand);
        hand.add(lastDiscard);
        TaiwanHandEvaluator.WinContext ctx = taiwanContextFor(winner, false);
        TaiwanHandEvaluator.WinResult result = TaiwanHandEvaluator.evaluate(hand, winner.melds, winner.flowers, ctx, taiSettings);
        if (!result.valid || result.totalTai < settings.minTai) {
            broadcast(Component.text("宣告失敗，牌型不成立或台數不足", NamedTextColor.RED), BroadcastCategory.WIN);
            advanceTurnAfterDiscard();
            return;
        }
        broadcast(Component.text(winner.name + " 胡 " + loser.name + "！ " + result.summary(), NamedTextColor.GOLD), BroadcastCategory.WIN);
        winner.score += result.points;
        loser.score -= result.points;
        finishHand(winner == players.get(dealerIndex));
    }

    private void distributeTsumoPoints(GamePlayer winner, long pointsPerPayer) {
        boolean dealerWin = winner == players.get(dealerIndex);
        if (settings.ruleSet == GameSettings.RuleSet.TAIWANESE) {
            // Flat: every other player pays the same computed amount.
            for (GamePlayer p : players) {
                if (p == winner) continue;
                p.score -= pointsPerPayer;
                winner.score += pointsPerPayer;
            }
            return;
        }
        int share = dealerWin ? (int) (pointsPerPayer / 3) : (int) (pointsPerPayer / 4);
        for (GamePlayer p : players) {
            if (p == winner) continue;
            int pay = (!dealerWin && p == players.get(dealerIndex)) ? share * 2 : share;
            p.score -= pay;
            winner.score += pay;
        }
    }

    private void finishHand(boolean dealerWon) {
        honba = dealerWon ? honba + 1 : 0;
        if (!dealerWon) {
            dealerIndex = nextIndex(dealerIndex);
            if (dealerIndex == 0) {
                dealerRotations++;
                if (settings.gameLength == GameSettings.GameLength.EAST_SOUTH && roundWind == 1) {
                    roundWind = 2;
                }
            }
        }
        if (shouldEndGame()) {
            endGame();
        } else {
            startHand();
        }
    }

    private void endHandDraw() {
        broadcast(Component.text("流局（牌山已摸完）", NamedTextColor.GRAY), BroadcastCategory.DRAW);
        dealerIndex = nextIndex(dealerIndex);
        if (dealerIndex == 0) {
            dealerRotations++;
            if (settings.gameLength == GameSettings.GameLength.EAST_SOUTH && roundWind == 1) {
                roundWind = 2;
            }
        }
        honba++;
        if (shouldEndGame()) {
            endGame();
        } else {
            startHand();
        }
    }

    private boolean shouldEndGame() {
        boolean anyBankrupt = players.stream().anyMatch(p -> p.score < 0);
        // Each "rotation" is 4 hands (one per seat getting a turn as dealer,
        // ignoring repeats/renchan which don't advance the dealer). East-only
        // = 1 rotation, East-South = 2. This is the actual round-count
        // termination — the roundWind field alone previously only ever
        // advanced for EAST_SOUTH mode, meaning EAST_ONLY games could
        // never end this way and would (with no bankruptcy) run forever.
        int rotationsNeeded = settings.gameLength == GameSettings.GameLength.EAST_ONLY ? 1 : 2;
        boolean pastRounds = dealerRotations >= rotationsNeeded;
        return anyBankrupt || pastRounds;
    }

    private void endGame() {
        phase = Phase.ENDED;
        players.sort((a, b) -> b.score - a.score);
        broadcast(Component.text("=== 對局結束 ===", NamedTextColor.GOLD), BroadcastCategory.GAME_END);
        for (int i = 0; i < players.size(); i++) {
            GamePlayer p = players.get(i);
            broadcast(Component.text((i + 1) + "位: " + p.name + " " + p.score + "點", NamedTextColor.WHITE), BroadcastCategory.GAME_END);
        }
        recordLeaderboard();
        awardWinnerItem();
        broadcast(Component.text("5 秒後回到準備大廳，可以重新 /mahjong ready 開始下一場。", NamedTextColor.GRAY), BroadcastCategory.GAME_END);
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> resetToLobby(), 20L * 5);
    }

    /**
     * Gives this match's top-scoring player a clone of the admin-configured
     * reward item (see RewardItemStore / /mahjong setreward), if one has
     * been set. Same eligibility guard as recordLeaderboard() — no reward
     * for bot-practice tables, so this can't be farmed for free items.
     */
    private void awardWinnerItem() {
        if (rewardStore == null || !rewardStore.isSet()) return;
        String rulePrefix = settings.ruleSet == GameSettings.RuleSet.TAIWANESE ? "taiwan" : "riichi";
        String configBase = "mahjong.reward." + rulePrefix + ".";
        if (!plugin.getConfig().getBoolean(configBase + "enabled", true)) return;
        if (players.stream().anyMatch(p -> p.isBot)) return;
        int dailyLimit = plugin.getConfig().getInt(configBase + "daily-limit", -1);
        if (!rewardStore.tryConsumeDailyAllowance("mahjong-" + rulePrefix, dailyLimit)) return;
        GamePlayer winner = players.get(0); // already sorted descending by score above
        Player bukkit = Bukkit.getPlayer(winner.uuid);
        if (bukkit == null) return; // offline — can't hand them an item right now
        org.bukkit.inventory.ItemStack item = rewardStore.get();
        if (item == null) return;
        var leftover = bukkit.getInventory().addItem(item);
        for (org.bukkit.inventory.ItemStack extra : leftover.values()) {
            bukkit.getWorld().dropItem(bukkit.getLocation(), extra);
        }
        bukkit.sendMessage(leftover.isEmpty()
                ? Component.text("🏆 恭喜獲得本場第一名獎品！", NamedTextColor.GOLD)
                : Component.text("🏆 恭喜獲得本場第一名獎品！背包放不下，已經掉在你腳邊了。", NamedTextColor.GOLD));
        if (plugin.getConfig().getBoolean(configBase + "announce", true)) {
            String ruleLabel = settings.ruleSet == GameSettings.RuleSet.TAIWANESE ? "台灣麻將" : "日本立直麻將";
            Component announcement = Component.text("🏆 " + winner.name + " 在" + ruleLabel + "拿到本場第一名，獲得獎品！", NamedTextColor.GOLD);
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                online.sendMessage(announcement);
            }
        }
    }

    /**
     * Records each real player's net score change (final - starting) to the
     * ruleset's leaderboard — deliberately the NET CHANGE, not the raw final
     * score, because mahjong is zero-sum: whatever one player gains, the
     * table collectively lost. That property is the main anti-farming
     * defense here — two colluding accounts trying to inflate one of them
     * for free would need the other to actually go negative, which is a
     * real cost, not free. Matches with ANY bot seated don't get recorded
     * at all (bots are trivially exploitable opponents).
     */
    private void recordLeaderboard() {
        if (leaderboard == null) return;
        if (players.stream().anyMatch(p -> p.isBot)) return;
        int maxPerDay = plugin.getConfig().getInt("mahjong.leaderboard.max-ranked-matches-per-day", 20);
        int startScore = settings.ruleSet == GameSettings.RuleSet.TAIWANESE ? settings.taiwanStartingScore : settings.startingScore;
        for (GamePlayer p : players) {
            long netChange = p.score - startScore;
            leaderboard.addScore(p.uuid, p.name, netChange, maxPerDay);
        }
    }

    /**
     * Brings the table (and everyone still in it) back to a fresh WAITING
     * state after a finished game, instead of forcing players to leave and
     * the host to /mahjong create a brand new table for a rematch. Bots
     * stay marked ready automatically, same as when they were first added.
     */
    private void resetToLobby() {
        if (phase != Phase.ENDED) return; // someone may have destroyed it in the meantime
        clearSidebars();
        phase = Phase.WAITING;
        waitingSince = System.currentTimeMillis();
        int startScore = settings.ruleSet == GameSettings.RuleSet.TAIWANESE ? settings.taiwanStartingScore : settings.startingScore;
        for (GamePlayer p : players) {
            p.ready = p.isBot || p.uuid.equals(id); // host defaults to ready again, same courtesy as table creation
            p.hand.clear();
            p.melds.clear();
            p.discards.clear();
            p.flowers.clear();
            p.riichi = false;
            p.doubleRiichi = false;
            p.ippatsuEligible = false;
            p.menzen = true;
            p.score = startScore;
        }
        broadcast(Component.text("牌桌已重置，等待所有人 /mahjong ready 準備下一場。", NamedTextColor.YELLOW), BroadcastCategory.GAME_END);
    }

    // -----------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------

    private Tile canAnkan(GamePlayer p) {
        for (Tile t : p.hand) {
            if (count(p.hand, t) == 4) return t;
        }
        return null;
    }

    public synchronized void declareAnkan(GamePlayer p, Tile tile) {
        if (inExchangePhase) return;
        if (players.get(currentIndex) != p) return; // not this player's turn
        if (windowOpen) return; // a call window from an earlier discard hasn't resolved yet
        if (tile == null || count(p.hand, tile) != 4) return;
        for (int i = 0; i < 4; i++) p.hand.remove(tile);
        p.melds.add(new Meld(Meld.Type.ANKAN, List.of(tile, tile, tile, tile), null));
        wall.revealNextDoraIndicator();
        broadcast(viewerUuid -> viewerUuid.equals(p.uuid)
                ? Component.text(p.name + " 暗槓了 ", NamedTextColor.YELLOW)
                        .append(Component.text(render(tile, viewerUuid), NamedTextColor.WHITE))
                : Component.text(p.name + " 暗槓了一組。", NamedTextColor.YELLOW), BroadcastCategory.CALL);
        Tile replacement = drawReplacingFlowers(p);
        if (replacement != null) {
            p.hand.add(replacement);
            p.sortHand();
            Player bukkitReplacement = Bukkit.getPlayer(p.uuid);
            if (bukkitReplacement != null) {
                bukkitReplacement.sendMessage(Component.text("你摸到了嶺上牌 ", NamedTextColor.AQUA)
                        .append(Component.text(render(replacement, p.uuid), NamedTextColor.WHITE)));
            }
        }
        promptDiscardOnly(p);
    }

    private HandEvaluator.WinContext contextFor(GamePlayer p, boolean tsumo) {
        HandEvaluator.WinContext ctx = new HandEvaluator.WinContext();
        ctx.tsumo = tsumo;
        ctx.riichi = p.riichi && !p.doubleRiichi;
        ctx.doubleRiichi = p.doubleRiichi;
        ctx.ippatsu = p.ippatsuEligible;
        ctx.seatWind = p.seatWind;
        ctx.roundWind = roundWind;
        ctx.doraTiles = wall != null ? wall.currentDoraTiles() : new ArrayList<>();
        ctx.isDealer = players.indexOf(p) == dealerIndex;
        return ctx;
    }

    private TaiwanHandEvaluator.WinContext taiwanContextFor(GamePlayer p, boolean tsumo) {
        TaiwanHandEvaluator.WinContext ctx = new TaiwanHandEvaluator.WinContext();
        ctx.tsumo = tsumo;
        ctx.seatWind = p.seatWind;
        ctx.roundWind = roundWind;
        ctx.isDealer = players.indexOf(p) == dealerIndex;
        ctx.kanCount = (int) p.melds.stream().filter(Meld::isKan).count();
        ctx.pointsPerTai = settings.pointsPerTai;
        ctx.basePoints = settings.taiwanBasePoints;
        ctx.dealerMultiplier = settings.dealerMultiplier;
        return ctx;
    }

    /** True if handWithWinTile (already including the winning tile) is a valid winning shape, under whichever ruleset this table uses. */
    private boolean canWin(GamePlayer p, List<Tile> handWithWinTile, boolean tsumo) {
        if (settings.ruleSet == GameSettings.RuleSet.TAIWANESE) {
            return TaiwanHandEvaluator.evaluate(handWithWinTile, p.melds, p.flowers, taiwanContextFor(p, tsumo), taiSettings).valid;
        }
        return WinChecker.isWinningShape(handWithWinTile, p.melds, contextFor(p, tsumo));
    }

    private int indexOf(GamePlayer p) {
        return players.indexOf(p);
    }

    private static int count(List<Tile> list, Tile t) {
        int c = 0;
        for (Tile x : list) if (x.equals(t)) c++;
        return c;
    }

    private static boolean contains(List<Tile> list, Tile t) {
        return count(list, t) > 0;
    }

    public synchronized void broadcast(Component msg, BroadcastCategory category) {
        broadcast(viewerUuid -> msg, category);
    }

    /**
     * Like broadcast(Component, BroadcastCategory), but builds a separate
     * Component per recipient. Needed for messages that embed a tile glyph
     * (e.g. "XXX 打出了 X"), since each viewer independently chooses text or
     * image display via /mahjong tiles — a single shared Component can't
     * satisfy both at once.
     */
    public synchronized void broadcast(java.util.function.Function<UUID, Component> msgBuilder, BroadcastCategory category) {
        for (GamePlayer p : players) {
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
        // Every broadcast() call corresponds to some meaningful game event
        // (discard, call, win, round start, etc.), so hooking the sidebar
        // refresh in here keeps it in sync everywhere without needing to
        // hunt down every individual call site by hand.
        //
        // Wrapped in try-catch as defense-in-depth: ScoreboardUtil already
        // protects itself internally, but broadcast() sits directly in the
        // middle of discard() -> openCallWindow() — if ANYTHING in the
        // sidebar refresh path ever throws unexpectedly and isn't caught
        // right here, it would abort discard() before openCallWindow() even
        // runs, silently breaking chi/pon/kan/ron for that discard (and
        // potentially every discard after it) without any visible error to
        // players — exactly the "suddenly can't chi or pon anymore"
        // symptom a real server hit. This must never be allowed to happen
        // again from ANY failure in this specific spot.
        try {
            refreshSidebars();
        } catch (Throwable t) {
            plugin.getLogger().warning("[mahjong] refreshSidebars() 發生非預期例外，已略過（不影響遊戲本身）: " + t);
        }
    }

    /**
     * Rebuilds and re-sends the live sidebar for every online human player
     * at this table. Cleared entirely once the table returns to the lobby
     * (see resetToLobby / removePlayer).
     */
    public synchronized void refreshSidebars() {
        if (phase != Phase.PLAYING) return;
        boolean serverDefault = plugin.getConfig().getBoolean("mahjong.sidebar-enabled", false);
        String title = settings.ruleSet == GameSettings.RuleSet.TAIWANESE ? "台灣麻將" : "立直麻將";
        GamePlayer current = getCurrentPlayer();
        for (GamePlayer p : players) {
            if (p.isBot) continue;
            Player bukkit = Bukkit.getPlayer(p.uuid);
            if (bukkit == null) continue;
            if (!sidebarPrefs.isEnabled(p.uuid, serverDefault)) continue;

            List<String> lines = new ArrayList<>();
            lines.add("東" + roundWind + (roundWind == 1 ? "" : "") + "局 " + honba + "本場");
            if (current != null) lines.add("輪到: " + (current == p ? "你" : current.name));
            lines.add("你的手牌: " + p.hand.size() + "張");
            if (lastDiscard != null && lastDiscarder != null) {
                lines.add("最新棄牌: " + render(lastDiscard, p.uuid));
                lines.add("(" + lastDiscarder.name + "打出)");
            }
            List<Tile> dora = getDoraIndicators();
            if (!dora.isEmpty()) {
                lines.add("寶牌指標: " + render(dora.get(dora.size() - 1), p.uuid));
            }
            lines.add("牌山剩餘: " + getRemainingTiles() + "張");
            lines.add("你的分數: " + p.score);

            com.fkc.game.core.ScoreboardUtil.show(bukkit, title, lines);
        }
    }

    /** Removes the sidebar for every human player at this table, e.g. when the table is destroyed or resets to lobby. */
    public synchronized void clearSidebars() {
        for (GamePlayer p : players) {
            if (p.isBot) continue;
            Player bukkit = Bukkit.getPlayer(p.uuid);
            if (bukkit != null) com.fkc.game.core.ScoreboardUtil.clear(bukkit);
        }
    }
}
