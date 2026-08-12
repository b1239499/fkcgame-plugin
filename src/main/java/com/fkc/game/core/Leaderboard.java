package com.fkc.game.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple persistent per-player score leaderboard, backed by a flat
 * {@code .properties} file (deliberately NOT using Bukkit's YamlConfiguration
 * — this only needs plain java.io/java.util, which keeps it dependency-free
 * and avoids any risk of guessing wrong about an unverified Bukkit API
 * surface).
 * <p>
 * One instance = one leaderboard (e.g. "UNO", "Riichi Mahjong", "Taiwanese
 * Mahjong" each get their own file/instance, since their scoring scales
 * aren't comparable to each other).
 */
public class Leaderboard {

    public static class Entry {
        public UUID uuid;
        public String name = "";
        public long score;
        public int matches;
        public String lastMatchDate = "";
        public int matchesToday;
    }

    private final File file;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public Leaderboard(File dataFolder, String fileName) {
        this.file = new File(dataFolder, fileName);
        load();
    }

    private synchronized void load() {
        if (!file.exists()) return;
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            return;
        }
        Map<UUID, Entry> byUuid = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot < 0) continue;
            String uuidPart = key.substring(0, dot);
            String field = key.substring(dot + 1);
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidPart);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Entry entry = byUuid.computeIfAbsent(uuid, u -> {
                Entry e = new Entry();
                e.uuid = u;
                return e;
            });
            String value = props.getProperty(key);
            switch (field) {
                case "name" -> entry.name = value;
                case "score" -> entry.score = parseLongSafe(value, 0);
                case "matches" -> entry.matches = (int) parseLongSafe(value, 0);
                case "lastMatchDate" -> entry.lastMatchDate = value;
                case "matchesToday" -> entry.matchesToday = (int) parseLongSafe(value, 0);
                default -> { /* unknown field, ignore */ }
            }
        }
        entries.clear();
        entries.putAll(byUuid);
    }

    private static long parseLongSafe(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private synchronized void save() {
        Properties props = new Properties();
        for (Entry e : entries.values()) {
            String prefix = e.uuid.toString() + ".";
            props.setProperty(prefix + "name", e.name == null ? "" : e.name);
            props.setProperty(prefix + "score", String.valueOf(e.score));
            props.setProperty(prefix + "matches", String.valueOf(e.matches));
            props.setProperty(prefix + "lastMatchDate", e.lastMatchDate == null ? "" : e.lastMatchDate);
            props.setProperty(prefix + "matchesToday", String.valueOf(e.matchesToday));
        }
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            props.store(out, "FkcGame leaderboard \u2014 machine-generated, avoid hand-editing while the server is running");
        } catch (IOException ignored) {
            // Best-effort persistence; a failed save just means this update
            // isn't durable yet, not worth crashing the game over.
        }
    }

    /**
     * Records a score change for one player.
     * @return true if it was recorded, false if blocked by the daily
     *         ranked-match cap (maxMatchesPerDay &lt;= 0 means unlimited).
     */
    public synchronized boolean addScore(UUID uuid, String name, long delta, int maxMatchesPerDay) {
        Entry entry = entries.computeIfAbsent(uuid, u -> {
            Entry e = new Entry();
            e.uuid = u;
            return e;
        });
        entry.name = name;
        String today = LocalDate.now().toString();
        if (!today.equals(entry.lastMatchDate)) {
            entry.lastMatchDate = today;
            entry.matchesToday = 0;
        }
        if (maxMatchesPerDay > 0 && entry.matchesToday >= maxMatchesPerDay) {
            return false;
        }
        entry.score += delta;
        entry.matches++;
        entry.matchesToday++;
        save();
        return true;
    }

    public synchronized List<Entry> top(int n) {
        List<Entry> list = new ArrayList<>(entries.values());
        list.sort((a, b) -> Long.compare(b.score, a.score));
        return list.subList(0, Math.min(n, list.size()));
    }

    public synchronized Entry get(UUID uuid) {
        return entries.get(uuid);
    }
}
