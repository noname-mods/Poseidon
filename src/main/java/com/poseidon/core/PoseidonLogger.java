package com.poseidon.core;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class PoseidonLogger {

    public static final int LEVEL_DEBUG = 0;
    public static final int LEVEL_INFO  = 1;
    public static final int LEVEL_WARN  = 2;
    public static final int LEVEL_ERROR = 3;

    private static final PoseidonLogger INSTANCE = new PoseidonLogger();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_RECENT = 50;

    private final Path logFile = FabricLoader.getInstance()
            .getConfigDir().resolve("poseidon/poseidon.log");
    private final Deque<String> recentLines = new ArrayDeque<>();
    private PrintWriter writer;
    private int logLevel = LEVEL_INFO;

    private PoseidonLogger() {
        try {
            Files.createDirectories(logFile.getParent());
            writer = new PrintWriter(Files.newBufferedWriter(logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
        } catch (IOException e) {
            System.err.println("[Poseidon] Could not open log file: " + e.getMessage());
        }
    }

    public static PoseidonLogger getInstance() { return INSTANCE; }

    public void setLogLevel(int level) { logLevel = level; }

    public void logDebug(String msg) { log(LEVEL_DEBUG, "DEBUG", msg); }
    public void logInfo(String msg)  { log(LEVEL_INFO,  "INFO",  msg); }
    public void logWarn(String msg)  { log(LEVEL_WARN,  "WARN",  msg); }
    public void logError(String msg) { log(LEVEL_ERROR, "ERROR", msg); }

    private void log(int level, String tag, String msg) {
        if (level < logLevel) return;
        String line = "[" + LocalTime.now().format(TIME_FMT) + "] [" + tag + "] " + msg;
        synchronized (recentLines) {
            recentLines.addLast(line);
            if (recentLines.size() > MAX_RECENT) recentLines.removeFirst();
        }
        if (writer != null) writer.println(line);
    }

    public List<String> getRecentLines() {
        synchronized (recentLines) {
            return new ArrayList<>(recentLines);
        }
    }
}
