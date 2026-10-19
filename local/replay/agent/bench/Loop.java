// LLM-assisted (Claude), see LLM_POLICY.md.
// usage: java ... Loop <iterations> <dotc args...>   Runs dotc.Main.process in one JVM, prints ms per run.
public class Loop {
  public static void main(String[] a) {
    int n = Integer.parseInt(a[0]);
    String[] args = java.util.Arrays.copyOfRange(a, 1, a.length);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      long t0 = System.nanoTime();
      var r = dotty.tools.dotc.Main.process(args);
      long ms = (System.nanoTime() - t0) / 1_000_000;
      if (r.hasErrors()) throw new IllegalStateException("errors in run " + i);
      sb.append(ms).append(i + 1 < n ? " " : "");
    }
    System.out.println(sb);
  }
}
