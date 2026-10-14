package dotty.tools.dotc.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Records the distinct call sites that reach a probe, with counts: "which callers rely on X?"
 *
 * Off unless the JVM runs with {@code -Ddotty.audit=<file>}; then a report of every probe is
 * written to that file at exit. {@link #ENABLED} is a static final, so a guarded probe costs
 * nothing when off. Not thread-aware beyond being safe: counts from concurrent compilers mix.
 *
 * A site is the first {@code depth} frames of the stack after dropping the frames {@code skip}
 * matches, as {@code Class.method:line} joined by {@code <-}. Objects can be tagged with a site
 * when created (keyed by identity, held weakly) and the tag read back at the probe, to pair the
 * code that made an object with the code that later relied on it.
 */
public final class CallerAudit {
  public static final boolean ENABLED = System.getProperty("dotty.audit") != null;

  private static final StackWalker WALKER = StackWalker.getInstance();
  private static final Map<String, Probe> probes = new HashMap<>();
  private static final Map<String, long[]> counters = new HashMap<>();
  private static final WeakHashMap<Object, String> tags = new WeakHashMap<>();

  static {
    if (ENABLED) Runtime.getRuntime().addShutdownHook(new Thread(CallerAudit::writeReport));
  }

  private CallerAudit() {}

  public static synchronized Probe probe(String name, int depth, Predicate<StackWalker.StackFrame> skip) {
    return probes.computeIfAbsent(name, n -> new Probe(n, depth, skip));
  }

  /** Bump a named counter, for denominators. */
  public static synchronized void count(String name) {
    counters.computeIfAbsent(name, n -> new long[1])[0]++;
  }

  /** Is a method of a class whose name starts with `classPrefix` on the stack? */
  public static boolean onStack(String classPrefix) {
    return WALKER.walk(s -> s.anyMatch(f -> f.getClassName().startsWith(classPrefix)));
  }

  /** Tag `obj` (compared by `equals`, so use objects with identity equality). */
  public static synchronized void tag(Object obj, String site) { tags.put(obj, site); }

  public static synchronized String tagOf(Object obj) {
    String t = tags.get(obj);
    return t == null ? "?" : t;
  }

  public static final class Probe {
    final String name;
    final int depth;
    final Predicate<StackWalker.StackFrame> skip;
    final Map<String, long[]> sites = new HashMap<>();
    long total;

    Probe(String name, int depth, Predicate<StackWalker.StackFrame> skip) {
      this.name = name; this.depth = depth; this.skip = skip;
    }

    /** The current call site, as this probe sees it. */
    public String site() {
      return WALKER.walk(s -> s
        .filter(f -> !f.getClassName().startsWith(CallerAudit.class.getName()))
        .filter(skip.negate())
        .limit(depth)
        .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
        .collect(Collectors.joining(" <- ")));
    }

    /** Record the current call site, qualified by `extra` (e.g. a node class or an origin tag). */
    public void record(String extra) { add(extra.isEmpty() ? site() : site() + "  [" + extra + "]"); }

    public synchronized void add(String key) {
      total++;
      sites.computeIfAbsent(key, k -> new long[1])[0]++;
    }
  }

  private static synchronized void writeReport() {
    Path out = Path.of(System.getProperty("dotty.audit"));
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
      w.println("# counters");
      counters.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(e -> w.println(e.getValue()[0] + "\t" + e.getKey()));
      for (Probe p : probes.values()) {
        synchronized (p) {
          w.println();
          w.println("# probe " + p.name + ": " + p.total + " hits, " + p.sites.size() + " distinct sites");
          var es = new ArrayList<>(p.sites.entrySet());
          es.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
          for (var e : es) w.println(e.getValue()[0] + "\t" + e.getKey());
        }
      }
    } catch (IOException e) {
      System.err.println("CallerAudit: cannot write " + out + ": " + e);
    }
  }
}
