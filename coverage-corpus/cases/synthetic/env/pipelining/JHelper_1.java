package pipe;

public class JHelper_1 {
  public static final int CONST = 7;
  public int help(int x) { return x; }
  public static <T extends Comparable<T>> T max(T a, T b) { return a.compareTo(b) > 0 ? a : b; }
  public enum Kind { A, B }
  public interface Cb<R> { R call(String s) throws Exception; }
  public record Pt(int x, int y) {}
}
