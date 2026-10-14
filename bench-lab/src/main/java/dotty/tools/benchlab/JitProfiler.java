package dotty.tools.benchlab;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordingStream;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;
import org.openjdk.jmh.runner.IterationType;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Is the JIT still busy when JMH measures? Streams JFR's compilation and deoptimization events in the fork and buckets
 * them by iteration, by timestamp. Reports, over all measurement iterations and in the last warmup iteration: the
 * number of compilations, their compile time and the number of deoptimizations; prints per iteration counts and the
 * methods compiled or deoptimized while measuring. A steady state has (nearly) none in either.
 *
 * Events reach the stream with a delay (JFR flushes about once a second), so results are computed after the last
 * measurement iteration, after waiting for a flush.
 */
public class JitProfiler implements InternalProfiler {
    // Results come from the last measurement iteration only; JMH would average them with the empty ones.
    static final AggregationPolicy POLICY = AggregationPolicy.MAX;

    private record Event(Instant time, boolean deopt, long durationNs, String method, int level, boolean osr) {}
    private record Window(IterationType type, int index, Instant start, Instant end) {}

    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
    private final List<Window> windows = new ArrayList<>();
    private Instant start;
    private int seen;

    // JMH also instantiates profilers in the host VM, so the stream starts with the first iteration, in the fork:
    // its threads are not daemons and would keep the host VM alive.
    private RecordingStream stream;

    private void startStream() {
        stream = new RecordingStream();
        stream.enable("jdk.Compilation").withThreshold(Duration.ZERO);
        stream.enable("jdk.Deoptimization").withStackTrace();
        stream.onEvent("jdk.Compilation", e -> record(e, () -> new Event(e.getEndTime(), false, e.getDuration().toNanos(),
                method(e.getValue("method")), e.getInt("compileLevel"), e.getBoolean("isOsr"))));
        stream.onEvent("jdk.Deoptimization", e -> record(e, () -> new Event(e.getEndTime(), true, 0,
                method(e.getValue("method")) + " (" + field(e, "compiler") + ": " + field(e, "reason") + ", " + field(e, "action") + ")",
                0, false)));
        stream.startAsync();
    }

    // An event whose fields differ from what we expect (they vary by JDK) is reported, not allowed to stop the stream.
    private void record(RecordedEvent e, java.util.function.Supplier<Event> f) {
        try {
            events.add(f.get());
        } catch (RuntimeException ex) {
            System.err.println("[jit] skipped " + e.getEventType().getName() + ": " + ex);
        }
    }

    private static String field(RecordedEvent e, String name) {
        return e.hasField(name) ? String.valueOf((Object) e.getValue(name)) : "?";
    }

    private static String method(RecordedMethod m) {
        return m == null ? "?" : m.getType().getName() + "." + m.getName();
    }

    @Override
    public String getDescription() {
        return "JIT compilations and deoptimizations per iteration (JFR): is the benchmark in a steady state?";
    }

    @Override
    public void beforeIteration(BenchmarkParams bp, IterationParams ip) {
        if (stream == null) startStream();
        start = Instant.now();
    }

    @Override
    public Collection<? extends Result> afterIteration(BenchmarkParams bp, IterationParams ip, IterationResult ir) {
        windows.add(new Window(ip.getType(), windows.size(), start, Instant.now()));
        if (ip.getType() != IterationType.MEASUREMENT || ++seen < ip.getCount()) return List.of();
        try {
            Thread.sleep(1500); // let JFR flush the last events
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stream.close();
        return results(bp);
    }

    private Collection<? extends Result> results(BenchmarkParams bp) {
        List<Event> all;
        synchronized (events) { all = new ArrayList<>(events); }
        Window lastWarmup = null;
        List<Window> measured = new ArrayList<>();
        for (Window w : windows)
            if (w.type == IterationType.WARMUP) lastWarmup = w; else measured.add(w);

        StringBuilder sb = new StringBuilder("\n[jit] per iteration: compilations / compile ms / deopts\n");
        for (Window w : windows) {
            long[] c = count(all, List.of(w));
            sb.append(String.format("  %-11s %3d: %5d / %7.1f / %4d%n", w.type, w.index + 1, c[0], c[1] / 1e6, c[2]));
        }
        Map<String, Integer> compiled = new TreeMap<>(), deopted = new TreeMap<>();
        for (Event e : all)
            if (in(e, measured))
                (e.deopt ? deopted : compiled).merge(e.deopt ? e.method : e.method + " (tier " + e.level + (e.osr ? ", osr" : "") + ")", 1, Integer::sum);
        top(sb, "compiled while measuring", compiled);
        top(sb, "deoptimized while measuring", deopted);
        System.err.print(sb);
        // stderr of the fork is easily lost (sbt in the background); keep a copy
        try {
            java.nio.file.Path f = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                    "benchlab-jit-" + bp.getBenchmark().replaceAll(".*\\.", "") + "-" + ProcessHandle.current().pid() + ".txt");
            java.nio.file.Files.writeString(f, bp.getParam("corpus") + "\n" + sb);
            System.err.println("[jit] report: " + f);
        } catch (Exception e) {
            System.err.println("[jit] could not write report: " + e);
        }

        long[] m = count(all, measured);
        long[] w = lastWarmup == null ? new long[3] : count(all, List.of(lastWarmup));
        return List.of(
                new ScalarResult("jit.compilations", m[0], "#", POLICY),
                new ScalarResult("jit.compileTime", m[1] / 1e6, "ms", POLICY),
                new ScalarResult("jit.deopts", m[2], "#", POLICY),
                new ScalarResult("jit.lastWarmup.compilations", w[0], "#", POLICY),
                new ScalarResult("jit.lastWarmup.deopts", w[2], "#", POLICY));
    }

    private static boolean in(Event e, List<Window> ws) {
        for (Window w : ws)
            if (!e.time.isBefore(w.start) && !e.time.isAfter(w.end)) return true;
        return false;
    }

    /** [compilations, compile ns, deopts] of the events in the windows. */
    private static long[] count(List<Event> all, List<Window> ws) {
        long[] c = new long[3];
        for (Event e : all)
            if (in(e, ws)) {
                if (e.deopt) c[2]++;
                else { c[0]++; c[1] += e.durationNs; }
            }
        return c;
    }

    private static void top(StringBuilder sb, String title, Map<String, Integer> counts) {
        if (counts.isEmpty()) return;
        sb.append("[jit] ").append(title).append(":\n");
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(15)
                .forEach(e -> sb.append(String.format("  %4d  %s%n", e.getValue(), e.getKey())));
    }
}
