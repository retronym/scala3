package dotty.tools.benchlab.xctrace;
import java.nio.file.*; import java.util.*; import java.util.regex.*;
/** Instructions and cycles per thread name of one process, from a kept XctraceProfiler export.
 *  XctraceProfiler groups threads into bench/jit/gc/other; this shows what "other" is (e.g. the pickler executor).
 *  Thread ids are dropped and numbered names (Thread-12, GC Thread#3) folded, so short-lived per-op threads sum up.
 *
 *    javac -cp "$(sbt -batch "export scala3-bench-lab/Jmh/fullClasspath" | tail -1)" -d /tmp/bt bench-lab/tools/ByThread.java
 *    java -cp "<same cp>:/tmp/bt" dotty.tools.benchlab.xctrace.ByThread <trace>.counters.xml <trace>.toc.xml <pid>
 */
public class ByThread {
  public static void main(String[] a) throws Exception {
    Matcher m = Pattern.compile("pmc-events=\"([^\"]*)\"").matcher(Files.readString(Path.of(a[1])));
    m.find();
    List<String> ev = Arrays.asList(m.group(1).replace("&quot;", "").trim().split("\\s+"));
    int ins = ev.indexOf("INST_ALL"), cyc = ev.indexOf("CORE_ACTIVE_CYCLE");
    Map<String, long[]> by = new HashMap<>();
    XctraceProfiler.rows(Path.of(a[0]), row -> {
      String[] t = row.get("thread"), p = row.get("pmc-events");
      if (t == null || p == null || p[1] == null || t[0] == null || !t[0].contains("pid: " + a[2] + ")")) return;
      String[] xs = p[1].trim().split("\\s+");
      if (xs.length != ev.size()) return;
      String name = t[0].replaceAll(" \\(0x[0-9a-f]+\\)", "").replaceAll("#\\d+", "#N").replaceAll("Thread-\\d+", "Thread-N");
      long[] s = by.computeIfAbsent(name, k -> new long[2]);
      s[0] += Long.parseLong(xs[ins]); s[1] += Long.parseLong(xs[cyc]);
    });
    long tot = by.values().stream().mapToLong(s -> s[1]).sum();
    by.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1])).limit(60)
      .forEach(e -> System.out.printf("%5.1f%% cycles %7.2fG inst  %s%n", 100.0 * e.getValue()[1] / tot, e.getValue()[0] / 1e9, e.getKey()));
  }
}
