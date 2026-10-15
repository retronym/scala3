package dotty.tools.benchlab;

import java.util.ArrayList;
import java.util.List;

/**
 * The iterations of a benchmark that alternates variants within one fork (see {@code CompileBenchmark.variants}): which
 * variant each iteration ran, and when, by the wall clock. The benchmark writes it; profilers in the same fork read it to
 * split their measurements by variant (time from {@link PairedProfiler}, counters from {@code XctraceProfiler}).
 */
public final class IterationLog {
    /** One iteration: its position among all iterations of the trial, warmup included, so consecutive iterations
     *  {@code index / variants().size()} form a cycle in which each variant ran once; its wall-clock span; and the time
     *  the JVM spent in collection pauses during it. */
    public record Entry(int index, String variant, boolean measured, long startMillis, long endMillis, long gcMillis) {
        public int cycle(int variants) { return index / variants; }
    }

    private static final List<Entry> entries = new ArrayList<>();
    private static List<String> variants = List.of();
    private static Entry open;
    private static long gcAtStart;

    /** Total time of collection pauses so far, by the GC MXBeans. Not G1's "Concurrent GC", which counts marking that
     *  runs beside the application rather than stopping it. */
    static long gcMillis() {
        long t = 0;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans())
            if (!gc.getName().contains("Concurrent")) t += Math.max(0, gc.getCollectionTime());
        return t;
    }

    private IterationLog() {}

    /** Starts a trial with these variants, in order; the first is the baseline. */
    public static synchronized void reset(List<String> names) {
        entries.clear();
        variants = List.copyOf(names);
        open = null;
    }

    /** Is the current trial alternating variants? */
    public static synchronized boolean paired() { return variants.size() > 1; }

    public static synchronized List<String> variants() { return variants; }

    public static synchronized void begin(String variant, boolean measured) {
        gcAtStart = gcMillis();
        open = new Entry(entries.size(), variant, measured, System.currentTimeMillis(), -1, 0);
    }

    public static synchronized void end() {
        if (open == null) return;
        Entry e = new Entry(open.index(), open.variant(), open.measured(), open.startMillis(), System.currentTimeMillis(),
                gcMillis() - gcAtStart);
        entries.add(e);
        open = null;
    }

    /** The iteration that ended last, or null. */
    public static synchronized Entry last() { return entries.isEmpty() ? null : entries.get(entries.size() - 1); }

    public static synchronized List<Entry> entries() { return List.copyOf(entries); }
}
