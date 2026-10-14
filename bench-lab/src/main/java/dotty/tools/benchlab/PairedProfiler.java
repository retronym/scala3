package dotty.tools.benchlab;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;
import org.openjdk.jmh.runner.IterationType;

import java.util.*;

/**
 * Time per variant for a benchmark that alternates variants within one fork ({@link IterationLog}); nothing otherwise.
 * JMH's own score mixes the variants. Reports, over the measurement iterations: each variant's mean score
 * ({@code paired.<variant>}, in the benchmark's unit), and for each variant but the baseline the paired difference, the
 * mean and standard deviation over cycles of {@code 100 * (variant / baseline - 1)}, where a cycle is the consecutive
 * iterations in which each variant ran once ({@code paired.<variant>.delta%}, {@code .delta%.sd}).
 */
public class PairedProfiler implements InternalProfiler {
    // Each result is emitted once per fork, after the last measurement iteration. Not MAX, which floors at zero and
    // so loses a negative difference.
    static final AggregationPolicy POLICY = AggregationPolicy.SUM;

    /** One measured iteration's value, e.g. its score or a counter per op. */
    public record Point(IterationLog.Entry entry, double score) {}

    private final List<Point> points = new ArrayList<>();
    private String unit = "";
    private int seen;

    @Override
    public String getDescription() {
        return "Time per variant, and paired differences, when variants alternate by iteration within the fork";
    }

    @Override
    public void beforeIteration(BenchmarkParams bp, IterationParams ip) {}

    @Override
    public Collection<? extends Result> afterIteration(BenchmarkParams bp, IterationParams ip, IterationResult ir) {
        if (!IterationLog.paired()) return List.of();
        IterationLog.Entry e = IterationLog.last();
        if (e != null && ip.getType() == IterationType.MEASUREMENT) {
            points.add(new Point(e, ir.getPrimaryResult().getScore()));
            unit = ir.getPrimaryResult().getScoreUnit();
        }
        if (ip.getType() != IterationType.MEASUREMENT || ++seen < ip.getCount()) return List.of();
        return results(IterationLog.variants(), points, unit, "paired.");
    }

    /** Mean per variant, and paired differences against the first variant, from per-iteration values. */
    public static List<Result> results(List<String> variants, List<Point> points, String unit, String prefix) {
        Map<String, List<Double>> by = new LinkedHashMap<>();
        Map<Integer, Map<String, Double>> cycles = new TreeMap<>();
        for (Point p : points) {
            by.computeIfAbsent(p.entry().variant(), k -> new ArrayList<>()).add(p.score());
            cycles.computeIfAbsent(p.entry().cycle(variants.size()), k -> new HashMap<>()).put(p.entry().variant(), p.score());
        }
        List<Result> out = new ArrayList<>();
        String base = variants.get(0);
        StringBuilder log = new StringBuilder("[bench-lab] paired " + prefix + " (" + unit + "):");
        for (String v : variants) {
            List<Double> xs = by.getOrDefault(v, List.of());
            if (xs.isEmpty()) continue;
            double mean = mean(xs);
            out.add(new ScalarResult(prefix + v, mean, unit, POLICY));
            log.append(String.format(" %s %.4g", v, mean));
            if (v.equals(base)) continue;
            List<Double> deltas = new ArrayList<>();
            for (Map<String, Double> c : cycles.values())
                if (c.containsKey(v) && c.containsKey(base) && c.get(base) != 0) deltas.add(100 * (c.get(v) / c.get(base) - 1));
            if (deltas.isEmpty()) continue;
            double d = mean(deltas), sd = sd(deltas, d);
            out.add(new ScalarResult(prefix + v + ".delta%", d, "%", POLICY));
            out.add(new ScalarResult(prefix + v + ".delta%.sd", sd, "%", POLICY));
            log.append(String.format(" (%+.2f%% ± %.2f, %d pairs)", d, sd, deltas.size()));
        }
        System.out.println(log);
        return out;
    }

    static double mean(List<Double> xs) {
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.size();
    }

    static double sd(List<Double> xs, double mean) {
        if (xs.size() < 2) return 0;
        double s = 0;
        for (double x : xs) s += (x - mean) * (x - mean);
        return Math.sqrt(s / (xs.size() - 1));
    }
}
