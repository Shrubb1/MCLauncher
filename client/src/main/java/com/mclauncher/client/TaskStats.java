package com.mclauncher.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Automation history for the Dashboard page. The pro jar reports task
 * boundaries via begin()/end(); the free mod owns the counters and
 * persists them next to the launcher data so they survive updates and
 * restarts.
 */
public class TaskStats {

    public static class Event {
        public String kind, detail;
        public int seconds, count;
        public long ts;
    }

    public int tasksRun;
    public long taskSeconds;
    public int blocksMined;
    /** Placeholder - per-block pricing lands later. */
    public double moneyEarned;

    /** Live task while one runs - pro rewrites detail as phases change. */
    public String activeKind, activeDetail;
    public long activeStart;
    public final List<Event> log = new ArrayList<>();

    private static Path file() {
        return ClientSettings.file().getParent().resolve("taskstats.json");
    }

    public boolean active() {
        return activeKind != null;
    }

    public int activeSeconds() {
        return active() ? (int) ((System.currentTimeMillis()
                - activeStart) / 1000) : 0;
    }

    public void begin(String kind, String detail) {
        if (active()) {
            end(0); // don't leak the previous task's time
        }
        activeKind = kind;
        activeDetail = detail;
        activeStart = System.currentTimeMillis();
    }

    public void end(int count) {
        if (!active()) {
            return;
        }
        int secs = activeSeconds();
        tasksRun++;
        taskSeconds += secs;
        blocksMined += count;
        Event e = new Event();
        e.kind = activeKind;
        e.detail = activeDetail == null ? "" : activeDetail;
        e.seconds = secs;
        e.count = count;
        e.ts = System.currentTimeMillis();
        log.add(0, e);
        while (log.size() > 30) {
            log.remove(log.size() - 1);
        }
        activeKind = null;
        activeDetail = null;
        save();
    }

    public static TaskStats load() {
        TaskStats s = new TaskStats();
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) {
                return s;
            }
            JsonObject o;
            try (Reader r = Files.newBufferedReader(f)) {
                o = JsonParser.parseReader(r).getAsJsonObject();
            }
            s.tasksRun = o.has("tasksRun") ? o.get("tasksRun").getAsInt() : 0;
            s.taskSeconds = o.has("taskSeconds")
                    ? o.get("taskSeconds").getAsLong() : 0;
            s.blocksMined = o.has("blocksMined")
                    ? o.get("blocksMined").getAsInt() : 0;
            s.moneyEarned = o.has("moneyEarned")
                    ? o.get("moneyEarned").getAsDouble() : 0;
            if (o.has("log")) {
                for (var el : o.getAsJsonArray("log")) {
                    JsonObject j = el.getAsJsonObject();
                    Event e = new Event();
                    e.kind = j.has("k") ? j.get("k").getAsString() : "";
                    e.detail = j.has("d") ? j.get("d").getAsString() : "";
                    e.seconds = j.has("s") ? j.get("s").getAsInt() : 0;
                    e.count = j.has("c") ? j.get("c").getAsInt() : 0;
                    e.ts = j.has("t") ? j.get("t").getAsLong() : 0;
                    s.log.add(e);
                }
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    public void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("tasksRun", tasksRun);
            o.addProperty("taskSeconds", taskSeconds);
            o.addProperty("blocksMined", blocksMined);
            o.addProperty("moneyEarned", moneyEarned);
            JsonArray arr = new JsonArray();
            for (Event e : log) {
                JsonObject j = new JsonObject();
                j.addProperty("k", e.kind);
                j.addProperty("d", e.detail);
                j.addProperty("s", e.seconds);
                j.addProperty("c", e.count);
                j.addProperty("t", e.ts);
                arr.add(j);
            }
            o.add("log", arr);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), o.toString());
        } catch (Throwable ignored) {
        }
    }
}
