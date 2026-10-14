package dotty.tools.benchlab.xctrace;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;
import org.openjdk.jmh.runner.IterationType;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dotty.tools.benchlab.IterationLog;
import dotty.tools.benchlab.PairedProfiler;

/**
 * Apple PMU counters for the benchmark threads only, over the measurement iterations only.
 *
 * JMH's own {@code xctracenorm} launches the fork under {@code xctrace record --launch}: the trace covers JVM start
 * and warmup, and the counts are summed over every thread (JIT, GC, ...). This profiler runs inside the fork and
 * attaches {@code xctrace record --attach <own pid>} before the first measurement iteration, stops it after the last,
 * and splits the samples by thread: the JMH worker threads are the benchmark; GC and JIT are reported beside them.
 *
 * Two kinds of template:
 *  - event sampling (a "counters-profile" table, e.g. JMH's bundled template or bench-lab/templates/*.template.xml):
 *    per-op counts of each event, IPC, per-kinst ratios;
 *  - Instruments' stock "CPU Counters" template (CPU Bottlenecks mode, a "CounterMetricByThread" table):
 *    Apple's top-down breakdown (useful / processing / delivery / discarded) per thread group.
 *
 * Options (JMH style, ';'-separated): template=<name or path>, dir=<keep traces here>,
 * top=<N methods per event to attribute, 0 to skip; event sampling only>.
 *
 * Attribution: each timer sample carries the leaf PC and each event's count since the previous sample on that core.
 * The JIT's perf map (dumped in-process just before stopping) names the compiled method around the PC, so each
 * event is split by the method that was running. Counts over a 1 ms window are charged to one PC: good for which
 * methods, not for which instruction (that needs PMI sampling, a template set up in Instruments).
 *   -prof dotty.tools.benchlab.xctrace.XctraceProfiler:template=/abs/ipc.template.xml
 *   -prof "dotty.tools.benchlab.xctrace.XctraceProfiler:template=CPU Counters;dir=out"
 */
public class XctraceProfiler implements InternalProfiler {
    static final String[] BOTTLENECK = {"useful", "processing", "delivery", "discarded"};
    // Results come from the last measurement iteration only; JMH would average them with the empty iterations.
    static final AggregationPolicy POLICY = AggregationPolicy.MAX;
    static final String SELF = "pid: " + ProcessHandle.current().pid() + ")";

    private final String template;
    private final Path dir;
    private final boolean keep;
    private final String xctrace;

    private Process proc;
    private Path trace;
    private int seen;
    private long ops;
    // when variants alternate within the fork (IterationLog): each measured iteration's ops, by its log index, and the
    // trace's start by the wall clock, to assign samples to iterations by their sample time
    private final Map<Integer, Long> opsByIteration = new HashMap<>();
    private long traceStartMillis = -1;
    private long sampleUs = 1000;
    private final int top;
    private PerfMap perfMap;

    public XctraceProfiler(String initLine) throws IOException, InterruptedException {
        Map<String, String> opts = new HashMap<>();
        for (String kv : initLine.split(";")) {
            if (kv.isBlank()) continue;
            int i = kv.indexOf('=');
            opts.put(i < 0 ? kv.trim() : kv.substring(0, i).trim(), i < 0 ? "" : kv.substring(i + 1).trim());
        }
        template = opts.getOrDefault("template", "CPU Counters");
        keep = opts.containsKey("dir");
        dir = keep ? Path.of(opts.get("dir")).toAbsolutePath() : Files.createTempDirectory("xctrace");
        Files.createDirectories(dir);
        xctrace = run(List.of("xcrun", "-f", "xctrace")).trim();
        top = Integer.parseInt(opts.getOrDefault("top", "12"));
    }

    @Override
    public String getDescription() {
        return "Apple PMU counters (xctrace --attach) for the JMH worker threads, measurement iterations only";
    }

    @Override
    public void beforeIteration(BenchmarkParams bp, IterationParams ip) {
        if (ip.getType() == IterationType.MEASUREMENT && proc == null) start(bp);
    }

    @Override
    public Collection<? extends Result> afterIteration(BenchmarkParams bp, IterationParams ip, IterationResult ir) {
        if (ip.getType() != IterationType.MEASUREMENT || proc == null) return List.of();
        ops += ir.getMetadata().getMeasuredOps();
        IterationLog.Entry last = IterationLog.last();
        if (IterationLog.paired() && last != null) opsByIteration.put(last.index(), ir.getMetadata().getMeasuredOps());
        if (++seen < ip.getCount()) return List.of();
        try {
            stop();
            return analyze();
        } catch (Exception e) {
            throw new IllegalStateException("xctrace analysis failed; trace kept in " + trace, e);
        }
    }

    private void start(BenchmarkParams bp) {
        String name = bp.getBenchmark().replaceAll(".*\\.", "") + "-" + ProcessHandle.current().pid();
        trace = dir.resolve(name + ".trace");
        try {
            ProcessBuilder pb = new ProcessBuilder(xctrace, "record", "--template", template,
                    "--attach", Long.toString(ProcessHandle.current().pid()), "--output", trace.toString());
            pb.environment().putIfAbsent("DEVELOPER_DIR", "/Applications/Xcode.app/Contents/Developer");
            pb.redirectErrorStream(true);
            proc = pb.start();
            CountDownLatch started = new CountDownLatch(1);
            // xctrace's output also goes to <trace>.log: the fork's stderr does not reach JMH's -o log, and when xctrace
            // dies mid-recording its last words are the only clue
            Path log = dir.resolve(trace.getFileName() + ".log");
            Thread pump = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                     java.io.PrintWriter w = new java.io.PrintWriter(Files.newBufferedWriter(log), true)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        w.println(line);
                        System.err.println("[xctrace] " + line);
                        if (line.contains("Ctrl-C to stop")) started.countDown();
                    }
                } catch (IOException ignored) {
                }
                started.countDown();
            }, "xctrace-output");
            pump.setDaemon(true);
            pump.start();
            if (!started.await(120, TimeUnit.SECONDS) || !proc.isAlive())
                throw new IllegalStateException("xctrace did not start recording");
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private void stop() throws IOException, InterruptedException {
        if (!proc.isAlive())
            throw new IllegalStateException("xctrace exited with status " + proc.exitValue() + " while recording; its output: "
                    + dir.resolve(trace.getFileName() + ".log"));
        if (top > 0) perfMap = PerfMap.dump();
        // xctrace saves the trace on SIGINT (as for Ctrl-C); Process.destroy would send SIGTERM.
        run(List.of("kill", "-INT", Long.toString(proc.pid())));
        if (!proc.waitFor(30, TimeUnit.MINUTES)) throw new IllegalStateException("xctrace did not finish saving");
    }

    private Collection<? extends Result> analyze() throws Exception {
        Path toc = dir.resolve(trace.getFileName() + ".toc.xml");
        export(List.of("--toc"), toc);
        String tocXml = Files.readString(toc);
        Matcher startDate = Pattern.compile("<start-date>([^<]+)</start-date>").matcher(tocXml);
        if (startDate.find()) traceStartMillis = java.time.OffsetDateTime.parse(startDate.group(1)).toInstant().toEpochMilli();
        List<Result> results = new ArrayList<>();
        Matcher counters = Pattern.compile("<table[^>]*schema=\"counters-profile\"[^>]*>").matcher(tocXml);
        if (counters.find() && counters.group().contains("pmc-events=")) {
            Matcher ev = Pattern.compile("pmc-events=\"([^\"]*)\"").matcher(counters.group());
            ev.find();
            List<String> events = new ArrayList<>();
            for (String e : ev.group(1).replace("&quot;", "").trim().split("\\s+")) events.add(e);
            Path table = dir.resolve(trace.getFileName() + ".counters.xml");
            export(List.of("--xpath", "/trace-toc/run[@number=\"1\"]/data/table[@schema=\"counters-profile\"]"), table);
            Matcher rate = Pattern.compile("sample-rate-micro-seconds=\"(\\d+)\"").matcher(counters.group());
            sampleUs = rate.find() ? Long.parseLong(rate.group(1)) : 1000;
            eventResults(table, events, results);
        } else if (tocXml.contains("schema=\"CounterMetricByThread\"")) {
            Path table = dir.resolve(trace.getFileName() + ".bythread.xml");
            export(List.of("--xpath", "/trace-toc/run[@number=\"1\"]/data/table[@schema=\"CounterMetricByThread\"]"), table);
            bottleneckResults(table, results);
        } else {
            throw new IllegalStateException("no counters-profile or CounterMetricByThread table in " + toc);
        }
        if (!keep) deleteRecursively(dir);
        return results;
    }

    static String group(String threadFmt) {
        String t = threadFmt == null ? "" : threadFmt;
        if (t.contains("jmh-worker")) return "bench";
        if (t.contains("CompilerThread")) return "jit";
        if (t.contains("GC Thread") || t.contains("G1 ") || t.contains("GC ")) return "gc";
        return "other";
    }

    /** Event sampling: each row is one timer sample on one core, with each event's count since that core's previous sample. */
    private void eventResults(Path table, List<String> events, List<Result> out) throws Exception {
        Map<String, long[]> sums = new TreeMap<>();
        Map<String, Integer> samples = new TreeMap<>();
        Map<String, long[]> byLeaf = new HashMap<>();
        Map<String, long[]> byThread = new HashMap<>();
        List<IterationLog.Entry> measured = IterationLog.paired() && traceStartMillis > 0
            ? IterationLog.entries().stream().filter(IterationLog.Entry::measured).toList() : List.of();
        Map<Integer, long[]> byIteration = new HashMap<>();
        rows(table, row -> {
            String[] t = row.get("thread");
            String[] m = row.get("pmc-events");
            String[] bt = row.containsKey("tagged-backtrace") ? row.get("tagged-backtrace") : row.get("backtrace");
            if (t == null || m == null || m[1] == null) return;
            if (t[0] == null || !t[0].contains(SELF)) return;
            String[] xs = m[1].trim().split("\\s+");
            if (xs.length != events.size()) return;
            String g = group(t[0]);
            long[] s = sums.computeIfAbsent(g, k -> new long[events.size()]);
            for (int i = 0; i < xs.length; i++) s[i] += Long.parseLong(xs[i]);
            samples.merge(g, 1, Integer::sum);
            if (!measured.isEmpty() && g.equals("bench")) {
                String[] st = row.get("sample-time");
                IterationLog.Entry it = st == null || st[1] == null ? null : iterationAt(measured, traceStartMillis + Long.parseLong(st[1].trim()) / 1_000_000);
                if (it != null) {
                    long[] c = byIteration.computeIfAbsent(it.index(), k -> new long[events.size()]);
                    for (int i = 0; i < xs.length; i++) c[i] += Long.parseLong(xs[i]);
                }
            }
            long[] th = byThread.computeIfAbsent(threadName(t[0]), k -> new long[events.size()]);
            for (int i = 0; i < xs.length; i++) th[i] += Long.parseLong(xs[i]);
            if (perfMap != null && g.equals("bench") && bt != null) {
                String leaf = perfMap.name(bt[2], bt[3]);
                long[] l = byLeaf.computeIfAbsent(leaf, k -> new long[events.size() + 1]);
                for (int i = 0; i < xs.length; i++) l[i] += Long.parseLong(xs[i]);
                l[events.size()]++;
            }
        });
        if (perfMap != null) attribution(byLeaf, events);
        threads(byThread, events);
        if (!measured.isEmpty()) out.addAll(perVariant(measured, byIteration, events));
        long[] bench = sums.getOrDefault("bench", new long[events.size()]);
        out.add(new ScalarResult("xctrace.bench.oncpu", samples.getOrDefault("bench", 0) * sampleUs / 1e6, "s", POLICY));
        int cyc = events.indexOf("CORE_ACTIVE_CYCLE"), ins = events.indexOf("INST_ALL");
        for (int i = 0; i < events.size(); i++)
            out.add(new ScalarResult("xctrace." + events.get(i), bench[i] / (double) ops, "#/op", POLICY));
        if (cyc >= 0 && ins >= 0) {
            for (Map.Entry<String, long[]> e : sums.entrySet())
                if (e.getValue()[cyc] > 0)
                    out.add(new ScalarResult("xctrace.IPC." + e.getKey(), e.getValue()[ins] / (double) e.getValue()[cyc], "insts/clk", POLICY));
            long allCycles = sums.values().stream().mapToLong(s -> s[cyc]).sum();
            for (Map.Entry<String, long[]> e : sums.entrySet())
                out.add(new ScalarResult("xctrace.cycles%." + e.getKey(), 100.0 * e.getValue()[cyc] / allCycles, "%", POLICY));
        }
        for (int i = 0; i < events.size(); i++) {
            if (i == cyc || i == ins) continue;
            String e = events.get(i);
            if (e.contains("WAITING") || e.contains("STALL") || e.contains("BUBBLE")) {
                if (cyc >= 0 && bench[cyc] > 0)
                    out.add(new ScalarResult("xctrace." + e + "/cycles", 100.0 * bench[i] / bench[cyc], "%", POLICY));
            } else if (ins >= 0 && bench[ins] > 0) {
                out.add(new ScalarResult("xctrace." + e + "/kinst", 1000.0 * bench[i] / bench[ins], "#/kinst", POLICY));
            }
        }
    }

    /** Writes, per event, the methods that were running when it counted; prints the top few to the fork's stderr. */
    /** The measured iteration whose wall-clock span contains `millis`, or null (between iterations). */
    static IterationLog.Entry iterationAt(List<IterationLog.Entry> measured, long millis) {
        int lo = 0, hi = measured.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            IterationLog.Entry e = measured.get(mid);
            if (millis < e.startMillis()) hi = mid - 1;
            else if (millis > e.endMillis()) lo = mid + 1;
            else return e;
        }
        return null;
    }

    /** Each event per op on the benchmark thread, per variant, with paired differences (see PairedProfiler):
     *  `xctrace.<event>.<variant>`, `xctrace.<event>.<variant>.delta%`. */
    private List<Result> perVariant(List<IterationLog.Entry> measured, Map<Integer, long[]> byIteration, List<String> events) {
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            List<PairedProfiler.Point> points = new ArrayList<>();
            for (IterationLog.Entry e : measured) {
                long[] c = byIteration.get(e.index());
                Long n = opsByIteration.get(e.index());
                if (c != null && n != null && n > 0) points.add(new PairedProfiler.Point(e, c[i] / (double) n));
            }
            if (!points.isEmpty()) out.addAll(PairedProfiler.results(IterationLog.variants(), points, "#/op", "xctrace." + events.get(i) + "."));
        }
        return out;
    }

    /** A thread name with its id dropped and numbered names folded (Thread-12, GC Thread#3), so short-lived threads
     *  started per op, such as the pickler's executor, add up. */
    static String threadName(String threadFmt) {
        return threadFmt.replaceAll(" \\(0x[0-9a-f]+\\)", "").replaceAll(" \\(pid: \\d+\\)", "")
            .replaceAll("#\\d+", "#N").replaceAll("Thread-\\d+", "Thread-N").trim();
    }

    /** `<trace>.threads.txt`: each thread's share of the process's cycles and its instructions per op, with its
     *  group. The groups' totals are in the results; this says what is in them, e.g. which threads are "other". */
    private void threads(Map<String, long[]> byThread, List<String> events) throws IOException {
        int cyc = events.indexOf("CORE_ACTIVE_CYCLE"), ins = events.indexOf("INST_ALL");
        if (cyc < 0) return;
        long all = byThread.values().stream().mapToLong(t -> t[cyc]).sum();
        if (all == 0) return;
        StringBuilder sb = new StringBuilder("cycles%  instructions/op  group  thread\n");
        byThread.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[cyc], a.getValue()[cyc])).limit(25)
            .forEach(e -> sb.append(String.format("%6.2f%%  %14s  %-5s  %s%n", 100.0 * e.getValue()[cyc] / all,
                ins < 0 ? "" : String.format("%.3fG", e.getValue()[ins] / (double) ops / 1e9), group(e.getKey()), e.getKey())));
        Files.writeString(dir.resolve(trace.getFileName() + ".threads.txt"), sb);
    }

    private void attribution(Map<String, long[]> byLeaf, List<String> events) throws IOException {
        StringBuilder sb = new StringBuilder();
        int n = events.size();
        long[] tot = new long[n + 1];
        for (long[] l : byLeaf.values()) for (int i = 0; i <= n; i++) tot[i] += l[i];
        List<String> cols = new ArrayList<>(events);
        cols.add("samples");
        for (int i = 0; i <= n; i++) {
            final int k = i;
            if (tot[k] == 0) continue;
            sb.append(String.format("%n== %s (bench threads)%n", cols.get(k)));
            byLeaf.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[k], a.getValue()[k])).limit(top)
                    .forEach(e -> sb.append(String.format("  %5.1f%%  %s%n", 100.0 * e.getValue()[k] / tot[k], e.getKey())));
        }
        Files.writeString(dir.resolve(trace.getFileName() + ".methods.txt"), sb);
        System.err.print(sb);
    }

    /** CPU Bottlenecks: each row is a slice of one thread on one core, with the four categories in basis points. */
    private void bottleneckResults(Path table, List<Result> out) throws Exception {
        Map<String, double[]> weighted = new TreeMap<>();
        Map<String, Long> time = new TreeMap<>();
        rows(table, row -> {
            String[] t = row.get("thread"), d = row.get("duration"), v = row.get("uint64-array");
            if (t == null || d == null || v == null || v[1] == null) return;
            String[] xs = v[1].trim().split("\\s+");
            if (xs.length != 4 || t[0] == null || !t[0].contains(SELF)) return;
            long ns = Long.parseLong(d[1].trim());
            String g = group(t[0]);
            double[] w = weighted.computeIfAbsent(g, k -> new double[4]);
            for (int i = 0; i < 4; i++) w[i] += ns * Long.parseLong(xs[i]) / 10000.0;
            time.merge(g, ns, Long::sum);
        });
        for (Map.Entry<String, double[]> e : weighted.entrySet()) {
            double[] w = e.getValue();
            double sum = Arrays.stream(w).sum();
            out.add(new ScalarResult("xctrace.oncpu." + e.getKey(), time.get(e.getKey()) / 1e9, "s", POLICY));
            for (int i = 0; i < 4; i++)
                out.add(new ScalarResult("xctrace." + BOTTLENECK[i] + "." + e.getKey(), 100.0 * w[i] / sum, "%", POLICY));
        }
    }

    /**
     * Replaces characters that XML 1.0 forbids with '?'. xctrace writes symbol names verbatim, and a JIT perf-map
     * name can contain control characters (seen: U+0001 in a frame name), which the StAX parser rejects.
     */
    static final class XmlCharFilter extends java.io.FilterReader {
        XmlCharFilter(Reader in) { super(in); }

        private static char clean(char c) {
            return c < 0x20 && c != '\t' && c != '\n' && c != '\r' || c == 0xFFFE || c == 0xFFFF ? '?' : c;
        }

        @Override public int read() throws IOException {
            int c = super.read();
            return c < 0 ? c : clean((char) c);
        }

        @Override public int read(char[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            for (int i = off; i < off + n; i++) buf[i] = clean(buf[i]);
            return n;
        }
    }

    /**
     * Streams an xctrace table export. Rows refer back to earlier elements ({@code ref="n"} for {@code id="n"}), so
     * every element with an id is remembered as (fmt, text, addr, name). Calls back with each row's top-level children
     * by tag; for a backtrace, addr and name are its leaf frame's.
     */
    static void rows(Path xml, java.util.function.Consumer<Map<String, String[]>> onRow) throws Exception {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        Map<String, String[]> ids = new HashMap<>();
        try (Reader in = new XmlCharFilter(Files.newBufferedReader(xml))) {
            XMLStreamReader r = f.createXMLStreamReader(in);
            Deque<String[]> stack = new ArrayDeque<>();   // [tag, id, fmt, text]
            Map<String, String[]> row = null;
            int rowDepth = -1;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String tag = r.getLocalName();
                    if (tag.equals("row")) { row = new HashMap<>(); rowDepth = stack.size(); }
                    String ref = r.getAttributeValue(null, "ref");
                    // [tag, id, fmt, text, addr, name]: addr/name are a frame's, or a backtrace's leaf frame's
                    String[] e = {tag, r.getAttributeValue(null, "id"), r.getAttributeValue(null, "fmt"), null,
                            r.getAttributeValue(null, "addr"), r.getAttributeValue(null, "name")};
                    if (ref != null && ids.containsKey(ref)) { String[] o = ids.get(ref); e[2] = o[0]; e[3] = o[1]; e[4] = o[2]; e[5] = o[3]; }
                    String[] parent = stack.peek();
                    if (tag.equals("frame") && parent != null && parent[0].endsWith("backtrace") && parent[4] == null) {
                        parent[4] = e[4]; parent[5] = e[5];
                    }
                    stack.push(e);
                } else if (ev == XMLStreamConstants.CHARACTERS && !stack.isEmpty()) {
                    String[] e = stack.peek();
                    e[3] = e[3] == null ? r.getText() : e[3] + r.getText();
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String[] e = stack.pop();
                    if (e[1] != null) ids.put(e[1], new String[]{e[2], e[3], e[4], e[5]});
                    if (row != null && stack.size() == rowDepth + 1) row.put(e[0], new String[]{e[2], e[3], e[4], e[5]});
                    if (e[0].equals("row") && row != null) { onRow.accept(row); row = null; }
                }
            }
        }
    }

    private void export(List<String> what, Path out) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(xctrace, "export", "--input", trace.toString(), "--output", out.toString()));
        cmd.addAll(what);
        run(cmd);
    }

    private static String run(List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().putIfAbsent("DEVELOPER_DIR", "/Applications/Xcode.app/Contents/Developer");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IOException(cmd + " failed: " + out);
        return out;
    }

    private static void deleteRecursively(Path p) throws IOException {
        try (var s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        }
    }
}
