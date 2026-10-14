package dotty.tools.benchlab.audit;

import java.io.IOException;
import java.io.Writer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The runtime of caller audits (see {@code bench-lab/doc/caller-audit.md}): which callers reach a point in the program,
 * how often, and, through tags, which code made the objects they work on. {@link AuditAgent} weaves calls to
 * {@link #hit} into the audited methods; nothing here runs unless the agent is loaded.
 *
 * Counts are {@link LongAdder}s in concurrent maps, so threads do not contend on a lock. A probe with {@code sample=N}
 * records the site of one hit in N (its total is still exact). The report, JSON, is written at exit.
 */
public final class Audit {
    private Audit() {}

    /** A probe, as declared in the spec (see {@link Spec}). */
    static final class Probe {
        final Spec.Line spec;
        final LongAdder hits = new LongAdder();
        final ConcurrentHashMap<String, LongAdder> sites = new ConcurrentHashMap<>();
        final Pattern skip;
        volatile MethodHandle when;  // resolved on first use, from the audited code's class loader
        volatile boolean whenFailed;

        Probe(Spec.Line spec) {
            this.spec = spec;
            this.skip = spec.skip() == null ? null : Pattern.compile(spec.skip());
        }
    }

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final String SELF = Audit.class.getName();
    static final List<Probe> probes = new ArrayList<>();
    private static final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private static final Tags tags = new Tags();
    private static Path out;

    static synchronized int register(Spec.Line line) {
        probes.add(new Probe(line));
        return probes.size() - 1;
    }

    static void start(Path report) {
        out = report;
        Runtime.getRuntime().addShutdownHook(new Thread(Audit::writeReport, "audit-report"));
    }

    /** Called from woven code: the probe `id` was reached, with `self` the receiver (null in a static method). */
    public static void hit(int id, Object self) {
        Probe p = probes.get(id);
        if (p.spec.from() != null && !calledFrom(p.spec.from())) return;
        if (p.spec.when() != null && !when(p, self)) return;
        p.hits.increment();
        if (p.spec.kind() == Spec.Kind.COUNT) {
            counters.computeIfAbsent(p.spec.name() + classify(p), k -> new LongAdder()).increment();
            return;
        }
        int n = p.spec.sample();
        if (n > 1 && ThreadLocalRandom.current().nextInt(n) != 0) return;
        String site = site(p);
        if (p.spec.kind() == Spec.Kind.TAG) {
            if (self != null) tags.put(self, site);
            return;
        }
        String q = qualifier(p, self);
        p.sites.computeIfAbsent(q.isEmpty() ? site : site + "  [" + q + "]", k -> new LongAdder()).increment();
    }

    // The method woven with the probe is the first frame below Audit; `from` names its caller, `Class#method`.
    private static boolean calledFrom(String from) {
        int hash = from.indexOf('#');
        String cls = from.substring(0, hash), method = from.substring(hash + 1);
        return WALKER.walk(s -> s.filter(f -> !f.getClassName().equals(SELF)).skip(1).findFirst()
            .map(f -> f.getClassName().equals(cls) && f.getMethodName().equals(method)).orElse(false));
    }

    private static boolean when(Probe p, Object self) {
        if (p.whenFailed || self == null) return false;
        try {
            MethodHandle h = p.when;
            if (h == null) {
                String w = p.spec.when();
                int hash = w.indexOf('#');
                Class<?> c = Class.forName(w.substring(0, hash), true, self.getClass().getClassLoader());
                h = MethodHandles.publicLookup().findStatic(c, w.substring(hash + 1), MethodType.methodType(boolean.class, Object.class));
                p.when = h;
            }
            return (boolean) h.invoke(self);
        } catch (Throwable t) {
            p.whenFailed = true;
            System.err.println("[audit] " + p.spec.name() + ": when=" + p.spec.when() + " failed, probe disabled: " + t);
            return false;
        }
    }

    // `onStack=label:prefix,...`: the first label whose class prefix is on the stack, as ".label", else ".other"
    private static String classify(Probe p) {
        if (p.spec.onStack().isEmpty()) return "";
        for (Map.Entry<String, String> e : p.spec.onStack().entrySet()) {
            String prefix = e.getValue();
            if (WALKER.walk(s -> s.anyMatch(f -> f.getClassName().startsWith(prefix)))) return "." + e.getKey();
        }
        return ".other";
    }

    private static String site(Probe p) {
        return WALKER.walk(s -> s
            .filter(f -> !f.getClassName().equals(SELF))
            .filter(f -> p.skip == null || !p.skip.matcher(f.getClassName() + "." + f.getMethodName()).find())
            .limit(p.spec.depth())
            .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
            .collect(Collectors.joining(" <- ")));
    }

    private static String qualifier(Probe p, Object self) {
        List<String> parts = new ArrayList<>();
        for (String q : p.spec.qualify()) {
            switch (q) {
                case "class" -> parts.add(self == null ? "static" : self.getClass().getSimpleName());
                case "tag" -> parts.add("from " + (self == null ? "?" : tags.get(self)));
                default -> {}
            }
        }
        String c = classify(p);
        if (!c.isEmpty()) parts.add(c.substring(1));
        return String.join(", ", parts);
    }

    /** Objects tagged with a site, by identity and weakly. */
    static final class Tags {
        private static final class Key extends WeakReference<Object> {
            final int hash;
            Key(Object o, ReferenceQueue<Object> q) { super(o, q); hash = System.identityHashCode(o); }
            @Override public int hashCode() { return hash; }
            @Override public boolean equals(Object x) { return x instanceof Key k && (k == this || k.get() != null && k.get() == get()); }
        }
        private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
        private final ConcurrentHashMap<Key, String> map = new ConcurrentHashMap<>();

        void put(Object o, String site) {
            for (Object k; (k = queue.poll()) != null; ) map.remove(k);
            map.put(new Key(o, queue), site);
        }

        String get(Object o) {
            String s = map.get(new Key(o, null));
            return s == null ? "untagged" : s;
        }
    }

    /** JSON: {"counters": {name: n}, "probes": {name: {"hits": n, "sample": n, "sites": {site: n}}}}. */
    private static synchronized void writeReport() {
        if (out == null) return;
        StringBuilder sb = new StringBuilder("{\n  \"counters\": {");
        String sep = "\n";
        for (Map.Entry<String, LongAdder> e : new TreeMap<>(counters).entrySet()) {
            sb.append(sep).append("    ").append(str(e.getKey())).append(": ").append(e.getValue().sum());
            sep = ",\n";
        }
        sb.append("\n  },\n  \"probes\": {");
        sep = "\n";
        for (Probe p : probes) {
            if (p.spec.kind() == Spec.Kind.COUNT) continue;
            sb.append(sep).append("    ").append(str(p.spec.name())).append(": {\"kind\": ").append(str(p.spec.kind().name().toLowerCase()))
              .append(", \"hits\": ").append(p.hits.sum()).append(", \"sample\": ").append(p.spec.sample()).append(", \"sites\": {");
            String s2 = "\n";
            var es = new ArrayList<>(p.sites.entrySet());
            es.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
            for (var e : es) {
                sb.append(s2).append("      ").append(str(e.getKey())).append(": ").append(e.getValue().sum());
                s2 = ",\n";
            }
            sb.append("\n    }}");
            sep = ",\n";
        }
        sb.append("\n  }\n}\n");
        try (Writer w = Files.newBufferedWriter(out)) {
            w.write(sb.toString());
        } catch (IOException e) {
            System.err.println("[audit] cannot write " + out + ": " + e);
        }
    }

    private static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
