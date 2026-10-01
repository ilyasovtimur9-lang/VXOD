package com.qyuro.joinflow.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qyuro.joinflow.QyuroJoinFlow;
import com.qyuro.joinflow.config.ServerConfig;
import com.qyuro.joinflow.profiler.JoinProfiler;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Small capped local history and optional full reports. All disk I/O uses JoinFlowIO. */
public final class HistoryStore {
    private static final int MAX_HISTORY_FILE_BYTES = 512 * 1024;
    private static final DateTimeFormatter REPORT_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
            .withZone(ZoneOffset.UTC);

    private final JoinFlowIO io;
    private final AtomicInteger reportSequence = new AtomicInteger();
    private final Path historyFile = FMLPaths.CONFIGDIR.get().resolve("qyurojoinflow/history/history.json");
    private final Path reportsDirectory = FMLPaths.CONFIGDIR.get().resolve("qyurojoinflow/reports");

    public HistoryStore(JoinFlowIO io) {
        this.io = io;
    }

    public void loadAsync(MinecraftServer server, Consumer<List<JoinProfiler.JoinRecord>> applyOnServerThread) {
        int historyLimit = ServerConfig.historySize();
        io.submit(() -> {
            List<JoinProfiler.JoinRecord> records = readHistory(historyLimit);
            try {
                server.execute(() -> applyOnServerThread.accept(records));
            } catch (RuntimeException exception) {
                QyuroJoinFlow.LOGGER.debug("[QyuroJoinFlow] History load result was discarded during shutdown", exception);
            }
        });
    }

    public void saveHistory(List<JoinProfiler.JoinRecord> records) {
        List<JoinProfiler.JoinRecord> snapshot = List.copyOf(records);
        int historyLimit = ServerConfig.historySize();
        io.submit(() -> writeHistory(snapshot, historyLimit));
    }

    public void writeReport(JoinProfiler.JoinRecord record) {
        io.submit(() -> {
            try {
                Files.createDirectories(reportsDirectory);
                String stamp = REPORT_STAMP.format(Instant.ofEpochMilli(record.timestampMillis()));
                String suffix = String.format(java.util.Locale.ROOT, "%03d", reportSequence.incrementAndGet() % 1000);
                Path target = reportsDirectory.resolve("join-" + stamp + "-" + suffix + ".json");
                writeJsonAtomically(target, recordJson(record).toString());
            } catch (IOException exception) {
                QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Could not write optional join report", exception);
            }
        });
    }

    private List<JoinProfiler.JoinRecord> readHistory(int historyLimit) {
        try {
            if (!Files.exists(historyFile)) return List.of();
            long size = Files.size(historyFile);
            if (size < 0L || size > MAX_HISTORY_FILE_BYTES) {
                QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Ignoring oversized JoinFlow history file ({} bytes)", size);
                return List.of();
            }
            JsonElement root = JsonParser.parseString(Files.readString(historyFile, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) return List.of();
            JsonArray array = root.getAsJsonArray();
            List<JoinProfiler.JoinRecord> records = new ArrayList<>();
            int first = Math.max(0, array.size() - historyLimit);
            for (int i = first; i < array.size(); i++) {
                JsonElement element = array.get(i);
                if (!element.isJsonObject()) continue;
                JsonObject json = element.getAsJsonObject();
                double duration = number(json, "durationMs");
                double peak = number(json, "observedPeakMspt");
                if (!Double.isFinite(duration) || duration < 0.0 || !Double.isFinite(peak) || peak < 0.0) continue;
                records.add(new JoinProfiler.JoinRecord(longNumber(json, "timestampMillis"), duration, peak,
                        (int) Math.max(0L, Math.min(1_000_000L, longNumber(json, "tasksCompleted"))),
                        (int) Math.max(0L, Math.min(1_000_000L, longNumber(json, "tasksFailed"))),
                        Math.max(0L, longNumber(json, "bytesSent")), Math.max(0L, longNumber(json, "bytesReceived")),
                        string(json, "loadState")));
            }
            return List.copyOf(records);
        } catch (IOException | RuntimeException exception) {
            QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Could not read JoinFlow history; continuing with an empty history", exception);
            return List.of();
        }
    }

    private void writeHistory(List<JoinProfiler.JoinRecord> records, int historyLimit) {
        try {
            Files.createDirectories(historyFile.getParent());
            JsonArray array = new JsonArray();
            int first = Math.max(0, records.size() - historyLimit);
            for (int i = first; i < records.size(); i++) array.add(recordJson(records.get(i)));
            writeJsonAtomically(historyFile, array.toString());
        } catch (IOException exception) {
            QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Could not persist small JoinFlow history", exception);
        }
    }

    private static void writeJsonAtomically(Path target, String json) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonObject recordJson(JoinProfiler.JoinRecord record) {
        JsonObject json = new JsonObject();
        json.addProperty("timestampMillis", record.timestampMillis());
        json.addProperty("durationMs", record.durationMs());
        json.addProperty("observedPeakMspt", record.observedPeakMspt());
        json.addProperty("tasksCompleted", record.tasksCompleted());
        json.addProperty("tasksFailed", record.tasksFailed());
        json.addProperty("bytesSent", record.bytesSent());
        json.addProperty("bytesReceived", record.bytesReceived());
        json.addProperty("loadState", record.loadState());
        return json;
    }

    private static double number(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsDouble() : 0.0;
    }

    private static long longNumber(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsLong() : 0L;
    }

    private static String string(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()) return "UNKNOWN";
        String value = json.get(key).getAsString();
        return value.length() > 32 ? value.substring(0, 32) : value;
    }
}
