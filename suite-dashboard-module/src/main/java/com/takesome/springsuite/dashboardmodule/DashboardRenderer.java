package com.takesome.springsuite.dashboardmodule;

import com.takesome.springsuite.module.SuiteModuleManifest;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class DashboardRenderer {
    private static final int BAR_WIDTH = 28;
    private final SuiteModuleManifest manifest;

    public DashboardRenderer(SuiteModuleManifest manifest) {
        this.manifest = manifest;
    }

    public String render() {
        return render(false);
    }

    public String render(boolean live) {
        Runtime runtime = Runtime.getRuntime();
        long maxHeap = normalizeMax(runtime.maxMemory());
        long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long nonHeapUsed = Math.max(0, nonHeap.getUsed());
        long nonHeapMax = nonHeap.getMax() > 0 ? nonHeap.getMax() : Math.max(nonHeapUsed, 1L);
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        int daemonThreads = ManagementFactory.getThreadMXBean().getDaemonThreadCount();
        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        int processors = runtime.availableProcessors();
        long modulesCount = parseLong(System.getProperty("suite.modules.count", "0"), 0);
        Path runtimeRoot = Paths.get(System.getProperty("suite.project.root", System.getProperty("user.dir"))).toAbsolutePath().normalize();
        long diskTotal = 1;
        long diskUsed = 0;
        try {
            var store = Files.getFileStore(runtimeRoot);
            diskTotal = Math.max(1L, store.getTotalSpace());
            diskUsed = Math.max(0L, store.getTotalSpace() - store.getUsableSpace());
        } catch (Exception ignored) {
            // Keep dashboard rendering deterministic even when the file store cannot be queried.
        }

        StringBuilder out = new StringBuilder(4096);
        out.append("\n");
        out.append(TerminalAnsi.color(TerminalAnsi.BRIGHT_CYAN, "+--------------------------------------------------------------------------------+")).append("\n");
        out.append(TerminalAnsi.color(TerminalAnsi.BRIGHT_CYAN, "| "))
                .append(TerminalAnsi.color(TerminalAnsi.BOLD + TerminalAnsi.BRIGHT_MAGENTA, "SpringSuite Dashboard"))
                .append(TerminalAnsi.color(TerminalAnsi.BRIGHT_CYAN, " :: UNIX CLI mode                                         |"))
                .append("\n");
        out.append(TerminalAnsi.color(TerminalAnsi.BRIGHT_CYAN, "+--------------------------------------------------------------------------------+")).append("\n");
        out.append(kv("module", TerminalAnsi.color(TerminalAnsi.BRIGHT_GREEN, manifest.id() + " " + manifest.version()))).append("\n");
        out.append(kv("time", TerminalAnsi.color(TerminalAnsi.BRIGHT_BLUE, DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atZone(ZoneId.systemDefault()))))).append("\n");
        out.append(kv("root", TerminalAnsi.color(TerminalAnsi.CYAN, runtimeRoot.toString()))).append("\n");
        out.append(kv("java", Runtime.version().toString())).append("\n");
        out.append(kv("pid", String.valueOf(ManagementFactory.getRuntimeMXBean().getPid()))).append("\n");
        out.append("\n");
        out.append(metric("heap", usedHeap, maxHeap, bytes(usedHeap) + " / " + bytes(maxHeap), false)).append("\n");
        out.append(metric("nonheap", nonHeapUsed, nonHeapMax, bytes(nonHeapUsed) + " / " + bytes(nonHeapMax), false)).append("\n");
        out.append(metric("threads", threads, 256, threads + " total / " + daemonThreads + " daemon", false)).append("\n");
        out.append(metric("modules", modulesCount, 10, modulesCount + " external jars seen at bootstrap", true)).append("\n");
        out.append(metric("disk", diskUsed, diskTotal, bytes(diskUsed) + " / " + bytes(diskTotal), false)).append("\n");
        out.append(metric("cores", processors, Math.max(processors, 1), processors + " available processors", true)).append("\n");
        out.append("\n");
        out.append(kv("uptime", TerminalAnsi.color(TerminalAnsi.BRIGHT_YELLOW, humanDuration(Duration.ofMillis(uptimeMillis))))).append("\n");
        out.append(kv(live ? "hotkeys" : "mode", TerminalAnsi.color(
                TerminalAnsi.BRIGHT_GREEN,
                live ? "live refresh: 1s | q / Esc / Enter -> main CLI; Ctrl+C -> interrupt" : "static snapshot; use dashboard watch for live mode"
        ))).append("\n");
        out.append(TerminalAnsi.color(TerminalAnsi.BRIGHT_CYAN, "+--------------------------------------------------------------------------------+")).append("\n");
        return out.toString();
    }

    public Map<String, Object> snapshot() {
        Runtime runtime = Runtime.getRuntime();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("module", manifest.id());
        data.put("version", manifest.version());
        data.put("pid", ManagementFactory.getRuntimeMXBean().getPid());
        data.put("uptime", humanDuration(Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime())));
        data.put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
        data.put("heapMaxBytes", normalizeMax(runtime.maxMemory()));
        data.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        data.put("modulesCount", parseLong(System.getProperty("suite.modules.count", "0"), 0));
        data.put("runtimeRoot", System.getProperty("suite.project.root", System.getProperty("user.dir")));
        return data;
    }

    private String metric(String label, long value, long max, String suffix, boolean goodWhenHigh) {
        int pct = percent(value, max);
        String color = goodWhenHigh ? TerminalAnsi.BRIGHT_GREEN : TerminalAnsi.healthColor(pct);
        String percent = TerminalAnsi.color(color, String.format(Locale.ROOT, "%3d%%", pct));
        return String.format(Locale.ROOT, "  %s %s %s  %s",
                TerminalAnsi.color(TerminalAnsi.BRIGHT_BLUE, String.format(Locale.ROOT, "%-9s", label)),
                bar(value, max, color),
                percent,
                suffix);
    }

    private String bar(long value, long max, String color) {
        int pct = percent(value, max);
        int filled = Math.max(0, Math.min(BAR_WIDTH, Math.round((pct / 100.0f) * BAR_WIDTH)));
        return TerminalAnsi.color(TerminalAnsi.BRIGHT_BLACK, "[")
                + TerminalAnsi.color(color, "#".repeat(filled))
                + TerminalAnsi.color(TerminalAnsi.BRIGHT_BLACK, "-".repeat(BAR_WIDTH - filled) + "]");
    }

    private int percent(long value, long max) {
        if (max <= 0) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, Math.round((value * 100.0) / max)));
    }

    private String kv(String key, String value) {
        return String.format(Locale.ROOT, "  %s %s", TerminalAnsi.color(TerminalAnsi.BRIGHT_BLUE, String.format(Locale.ROOT, "%-9s", key + ":")), value == null ? "" : value);
    }

    private String bytes(long value) {
        double current = Math.max(0, value);
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (current >= 1024 && unit < units.length - 1) {
            current /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, unit == 0 ? "%.0f %s" : "%.1f %s", current, units[unit]);
    }

    private long normalizeMax(long value) {
        return value <= 0 ? Math.max(1L, Runtime.getRuntime().totalMemory()) : value;
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String humanDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        if (days > 0) {
            return String.format(Locale.ROOT, "%dd %02dh %02dm %02ds", days, hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02dh %02dm %02ds", hours, minutes, seconds);
    }
}
