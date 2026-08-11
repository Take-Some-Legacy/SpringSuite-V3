package com.takesome.springsuite.core.process;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns an operating-system process and its observed descendants.
 *
 * <p>The registry continuously snapshots the process tree because a child can be
 * re-parented after its direct parent exits. Managed processes are reaped on
 * normal completion, timeout, owner failure and JVM shutdown.</p>
 */
public final class ManagedProcess implements AutoCloseable {
    private static final Duration DEFAULT_GRACEFUL_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_FORCE_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration SHUTDOWN_GRACEFUL_TIMEOUT = Duration.ofMillis(250);
    private static final Duration SHUTDOWN_FORCE_TIMEOUT = Duration.ofSeconds(2);
    private static final long POLL_MILLIS = 25L;
    private static final Set<ManagedProcess> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService MONITOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "spring-suite-process-ownership-monitor");
        thread.setDaemon(true);
        return thread;
    });

    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(ManagedProcess::shutdownAll,
                    "spring-suite-process-ownership-shutdown"));
        } catch (IllegalStateException ignored) {
            // The JVM is already shutting down. Newly adopted processes are still
            // terminated by their local owner paths.
        }
    }

    private final Process process;
    private final String owner;
    private final Duration gracefulTimeout;
    private final Duration forceTimeout;
    private final Map<Long, TrackedProcess> observed = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean released = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> monitorTask;

    private ManagedProcess(Process process, String owner, Duration gracefulTimeout, Duration forceTimeout) {
        this.process = Objects.requireNonNull(process, "process");
        this.owner = normalizeOwner(owner);
        this.gracefulTimeout = normalizeTimeout(gracefulTimeout, DEFAULT_GRACEFUL_TIMEOUT);
        this.forceTimeout = normalizeTimeout(forceTimeout, DEFAULT_FORCE_TIMEOUT);
        captureTree();
        ACTIVE.add(this);
        this.monitorTask = MONITOR.scheduleWithFixedDelay(this::captureTreeSafely, 0L, 100L, TimeUnit.MILLISECONDS);
        process.onExit().thenRun(this::onRootExit);
    }

    public static ManagedProcess start(ProcessBuilder builder, String owner) throws IOException {
        return start(builder, owner, DEFAULT_GRACEFUL_TIMEOUT, DEFAULT_FORCE_TIMEOUT);
    }

    public static ManagedProcess start(
            ProcessBuilder builder,
            String owner,
            Duration gracefulTimeout,
            Duration forceTimeout
    ) throws IOException {
        Objects.requireNonNull(builder, "builder");
        Map<String, String> environment = builder.environment();
        environment.putIfAbsent("SPRING_SUITE_PROCESS_OWNED", "1");
        environment.putIfAbsent("SPRING_SUITE_PARENT_PID", Long.toString(ProcessHandle.current().pid()));
        environment.putIfAbsent("SPRING_SUITE_PROCESS_OWNER", normalizeOwner(owner));
        return adopt(builder.start(), owner, gracefulTimeout, forceTimeout);
    }

    public static ManagedProcess adopt(Process process, String owner) {
        return adopt(process, owner, DEFAULT_GRACEFUL_TIMEOUT, DEFAULT_FORCE_TIMEOUT);
    }

    public static ManagedProcess adopt(
            Process process,
            String owner,
            Duration gracefulTimeout,
            Duration forceTimeout
    ) {
        return new ManagedProcess(process, owner, gracefulTimeout, forceTimeout);
    }

    public Process process() {
        return process;
    }

    public long pid() {
        return process.pid();
    }

    public String owner() {
        return owner;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Marks a normally awaited root process as complete and reaps any background
     * descendants that attempted to outlive it.
     */
    public TerminationReport complete() {
        if (process.isAlive()) {
            return terminate();
        }
        if (!stopping.compareAndSet(false, true)) {
            return report(false);
        }
        captureTreeSafely();
        TerminationReport result = terminateHandles(Duration.ZERO, forceTimeout, false);
        releaseRegistration();
        return result;
    }

    public TerminationReport terminate() {
        return terminate(gracefulTimeout, forceTimeout);
    }

    public TerminationReport terminate(Duration graceful, Duration force) {
        if (!stopping.compareAndSet(false, true)) {
            return report(false);
        }
        captureTreeSafely();
        TerminationReport result = terminateHandles(
                normalizeTimeout(graceful, Duration.ZERO),
                normalizeTimeout(force, DEFAULT_FORCE_TIMEOUT),
                true
        );
        closeProcessStreams();
        releaseRegistration();
        return result;
    }

    /**
     * Explicitly transfers ownership to an external supervisor. No process is
     * terminated by this instance after release.
     */
    public void release() {
        stopping.set(true);
        releaseRegistration();
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            terminate();
        } else {
            complete();
        }
    }

    private void onRootExit() {
        captureTreeSafely();
        if (stopping.compareAndSet(false, true)) {
            terminateHandles(Duration.ZERO, forceTimeout, false);
        }
        releaseRegistration();
    }

    private void captureTreeSafely() {
        if (released.get()) {
            return;
        }
        try {
            captureTree();
        } catch (RuntimeException ignored) {
            // A process may disappear while ProcessHandle is enumerating it.
        }
    }

    private void captureTree() {
        track(process.toHandle());
        List<ProcessHandle> roots = new ArrayList<>();
        roots.add(process.toHandle());
        for (TrackedProcess tracked : observed.values()) {
            ProcessHandle handle = tracked.handle();
            if (sameProcess(tracked) && handle.isAlive()) {
                roots.add(handle);
            }
        }
        for (ProcessHandle root : roots) {
            try {
                root.descendants().forEach(this::track);
            } catch (RuntimeException ignored) {
                // Best effort; the next monitor pass retries while the handle lives.
            }
        }
    }

    private void track(ProcessHandle handle) {
        if (handle == null || handle.pid() <= 0) {
            return;
        }
        observed.computeIfAbsent(handle.pid(), ignored -> new TrackedProcess(
                handle,
                handle.info().startInstant().orElse(null)
        ));
    }

    private TerminationReport terminateHandles(Duration graceful, Duration force, boolean includeRoot) {
        List<TrackedProcess> snapshot = new ArrayList<>(observed.values());
        TrackedProcess root = observed.get(process.pid());
        List<ProcessHandle> targets = aliveTargets(snapshot, includeRoot);
        boolean forced = false;

        if (!targets.isEmpty() && !graceful.isZero() && !graceful.isNegative()) {
            destroyDeepestFirst(targets, false, root);
            waitForExit(targets, graceful);
        }

        List<ProcessHandle> survivors = alive(targets);
        if (!survivors.isEmpty()) {
            forced = true;
            if (includeRoot && isWindows() && process.isAlive()) {
                taskkillTree(process.pid());
            }
            destroyDeepestFirst(survivors, true, root);
            waitForExit(survivors, force);
        }

        List<Long> remaining = alive(targets).stream()
                .map(ProcessHandle::pid)
                .distinct()
                .sorted()
                .toList();
        List<Long> observedPids = snapshot.stream()
                .filter(this::sameProcess)
                .map(tracked -> tracked.handle().pid())
                .distinct()
                .sorted()
                .toList();
        return new TerminationReport(
                process.pid(),
                owner,
                observedPids,
                remaining,
                forced,
                Thread.currentThread().isInterrupted()
        );
    }

    private List<ProcessHandle> aliveTargets(Collection<TrackedProcess> tracked, boolean includeRoot) {
        LinkedHashSet<ProcessHandle> result = new LinkedHashSet<>();
        for (TrackedProcess candidate : tracked) {
            if (!sameProcess(candidate) || !candidate.handle().isAlive()) {
                continue;
            }
            if (!includeRoot && candidate.handle().pid() == process.pid()) {
                continue;
            }
            result.add(candidate.handle());
        }
        if (includeRoot && process.isAlive()) {
            result.add(process.toHandle());
        }
        return new ArrayList<>(result);
    }

    private boolean sameProcess(TrackedProcess tracked) {
        if (tracked == null) {
            return false;
        }
        Instant expected = tracked.startedAt();
        if (expected == null) {
            return true;
        }
        return tracked.handle().info().startInstant().map(expected::equals).orElse(false);
    }

    private void destroyDeepestFirst(List<ProcessHandle> handles, boolean force, TrackedProcess root) {
        Map<Long, ProcessHandle> byPid = new HashMap<>();
        for (ProcessHandle handle : handles) {
            byPid.put(handle.pid(), handle);
        }
        List<ProcessHandle> ordered = new ArrayList<>(handles);
        long rootPid = root == null ? -1L : root.handle().pid();
        ordered.sort((left, right) -> {
            if (left.pid() == rootPid) {
                return 1;
            }
            if (right.pid() == rootPid) {
                return -1;
            }
            return Integer.compare(depth(right, byPid), depth(left, byPid));
        });
        for (ProcessHandle handle : ordered) {
            if (!handle.isAlive()) {
                continue;
            }
            try {
                if (force) {
                    handle.destroyForcibly();
                } else {
                    handle.destroy();
                }
            } catch (RuntimeException ignored) {
                // Continue reaping the remaining tree.
            }
        }
    }

    private int depth(ProcessHandle handle, Map<Long, ProcessHandle> tracked) {
        int depth = 0;
        ProcessHandle current = handle;
        Set<Long> visited = new LinkedHashSet<>();
        while (depth < 64 && visited.add(current.pid())) {
            ProcessHandle parent = current.parent().orElse(null);
            if (parent == null || !tracked.containsKey(parent.pid())) {
                break;
            }
            depth++;
            current = parent;
        }
        return depth;
    }

    private boolean waitForExit(List<ProcessHandle> handles, Duration timeout) {
        long timeoutNanos = Math.max(0L, timeout.toNanos());
        long deadline = System.nanoTime() + timeoutNanos;
        while (!alive(handles).isEmpty()) {
            if (timeoutNanos == 0L || System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private List<ProcessHandle> alive(Collection<ProcessHandle> handles) {
        return handles.stream().filter(ProcessHandle::isAlive).toList();
    }

    private void taskkillTree(long rootPid) {
        Process killer = null;
        try {
            killer = new ProcessBuilder(
                    "taskkill.exe", "/PID", Long.toString(rootPid), "/T", "/F"
            ).redirectErrorStream(true).start();
            killer.getOutputStream().close();
            if (!killer.waitFor(5, TimeUnit.SECONDS)) {
                killer.destroyForcibly();
            }
        } catch (Exception ignored) {
            if (killer != null && killer.isAlive()) {
                killer.destroyForcibly();
            }
        }
    }

    private void closeProcessStreams() {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
        }
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
        }
    }

    private TerminationReport report(boolean forced) {
        List<Long> survivors = observed.values().stream()
                .filter(this::sameProcess)
                .map(TrackedProcess::handle)
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .distinct()
                .sorted()
                .toList();
        return new TerminationReport(process.pid(), owner, observed.keySet().stream().sorted().toList(), survivors, forced, false);
    }

    private void releaseRegistration() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> task = monitorTask;
        if (task != null) {
            task.cancel(false);
        }
        ACTIVE.remove(this);
    }

    private static void shutdownAll() {
        MONITOR.shutdownNow();
        List<ManagedProcess> snapshot = new ArrayList<>(ACTIVE);
        for (ManagedProcess managed : snapshot) {
            try {
                managed.terminate(SHUTDOWN_GRACEFUL_TIMEOUT, SHUTDOWN_FORCE_TIMEOUT);
            } catch (RuntimeException ignored) {
                // Shutdown must continue through every owned process.
            }
        }
    }

    private static Duration normalizeTimeout(Duration value, Duration fallback) {
        if (value == null) {
            return fallback;
        }
        return value.isNegative() ? Duration.ZERO : value;
    }

    private static String normalizeOwner(String owner) {
        String normalized = owner == null ? "" : owner.trim();
        if (normalized.isBlank()) {
            return "spring-suite";
        }
        return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private record TrackedProcess(ProcessHandle handle, Instant startedAt) {
    }

    public record TerminationReport(
            long rootPid,
            String owner,
            List<Long> observedPids,
            List<Long> survivingPids,
            boolean forced,
            boolean interrupted
    ) {
        public TerminationReport {
            owner = owner == null ? "" : owner;
            observedPids = observedPids == null ? List.of() : List.copyOf(observedPids);
            survivingPids = survivingPids == null ? List.of() : List.copyOf(survivingPids);
        }

        public boolean clean() {
            return survivingPids.isEmpty();
        }
    }
}
