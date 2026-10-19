// Stage 1 (javac + dotc's Java parser): read back from classfiles by stage 2.
package jlib;

import java.lang.annotation.*;
import java.util.*;
import java.util.function.*;

public class JLib_1 {
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
  public @interface Ann {
    int i() default 1;
    long l() default 2L;
    String s() default "s";
    Class<?> c() default Object.class;
    ElementType e() default ElementType.TYPE;
    String[] arr() default {"a", "b"};
    Deprecated nested() default @Deprecated;
    double d() default 1.5;
    char ch() default 'x';
    boolean b() default true;
  }

  @Ann(i = 3, arr = {}, c = String.class)
  public static final int CONST = 42;
  public static final String SCONST = "const";
  public static final long LCONST = 1L << 40;
  public static int mutableStatic = 0;
  protected int prot = 1;
  int pkgPrivate = 2;
  public transient volatile int tv;

  public JLib_1() {}
  public JLib_1(int x, String... rest) {}

  public static <T extends Comparable<? super T>> T max(Collection<? extends T> xs) { return Collections.max(xs); }
  public <K, V extends List<K>> Map<K, V> generic(K k, V v) { return new HashMap<>(); }
  public static int sum(int... xs) { int s = 0; for (int x : xs) s += x; return s; }
  public static String join(String sep, Object... xs) { return ""; }
  @Deprecated public void old() {}
  public void throwsIt() throws java.io.IOException, InterruptedException {}
  public int[][] arrays(String[] a, int[]... b) { return new int[0][0]; }
  @SuppressWarnings("rawtypes") public List raw() { return new ArrayList(); }
  public static <A, B> Function<A, B> fn(Function<? super A, ? extends B> f) { return f::apply; }
  public final synchronized native void nat();

  public static class StaticNested<T> {
    public T value;
    public static class Deeper {}
  }
  public class Inner { public int outerProt() { return prot; } }

  public interface WithDefaults<T> {
    T get();
    default T getOr(T t) { return get() == null ? t : get(); }
    static <T> WithDefaults<T> of(T t) { return () -> t; }
    private void priv() {}
  }

  @FunctionalInterface public interface IntOp { int apply(int a, int b); }

  public enum Planet implements IntOp {
    MERCURY(3.3e23) { @Override public int apply(int a, int b) { return a; } },
    EARTH(5.9e24);
    private final double mass;
    Planet(double m) { mass = m; }
    public double mass() { return mass; }
    public int apply(int a, int b) { return a + b; }
  }

  public record Point(int x, int y) implements Comparable<Point> {
    public Point { if (x < 0) throw new IllegalArgumentException(); }
    public Point(int x) { this(x, 0); }
    public static Point origin() { return new Point(0, 0); }
    public int compareTo(Point o) { return Integer.compare(x, o.x); }
  }

  public sealed interface Shape permits Circle, Square {}
  public static final class Circle implements Shape { public double r; }
  public static non-sealed class Square implements Shape {}

  public static abstract class AbstractBase<Self extends AbstractBase<Self>> {
    protected abstract Self self();
    public Self touch() { return self(); }
  }

  public static Optional<String> opt() { return Optional.empty(); }
  public static <T extends Number & Comparable<T>> T bounded(T t) { return t; }
  public static void consume(Consumer<? super String> c, Supplier<String> s, BiFunction<Integer, Integer, Integer> f) {}
}
