// Stage 1 Java: overridden and extended from Scala in stage 2 (bridges, protected accessors, SAM conversion, boxing at the boundary).
package jb;

import java.util.*;
import java.util.function.*;

public abstract class JBase_1<T extends Comparable<T>> implements Comparator<T> {
  protected int prot = 1;
  protected static int protStatic = 2;
  protected static int protStaticMethod(int x) { return x * 2; }
  protected abstract T make(int i);
  public abstract void consume(T t, int... extras);
  public int compare(T a, T b) { return a.compareTo(b); }
  public <R> R fold(R zero, BiFunction<R, ? super T, R> f, List<? extends T> xs) {
    R acc = zero; for (T x : xs) acc = f.apply(acc, x); return acc;
  }
  public static int applyInt(IntUnaryOperator op, int v) { return op.applyAsInt(v); }
  public static long applyLong(LongBinaryOperator op) { return op.applyAsLong(1L, 2L); }
  public static <A> A supply(Supplier<A> s) { return s.get(); }
  public static void run(Runnable r) { r.run(); }
  public static Void voidCall(Callable<Void> c) throws Exception { return c.call(); }
  public static boolean test(Predicate<String> p) { return p.test("x"); }
  public static double dbl(ToDoubleFunction<Integer> f) { return f.applyAsDouble(3); }
  public static Integer boxed(Integer i) { return i == null ? -1 : i + 1; }
  public static int[] prims(int[] xs) { return xs; }
  public static List<int[]> listOfArrays() { return new ArrayList<>(); }
  public static Object[] objs(Object... os) { return os; }
  public interface Visitor<R> {
    R visitInt(int i);
    R visitStr(String s);
    default R visitAny(Object o) { return o instanceof Integer ? visitInt((Integer) o) : visitStr(String.valueOf(o)); }
  }
  public interface Callable<V> { V call() throws Exception; }
  public static class Node<N extends Node<N>> { public List<N> children = new ArrayList<>(); }
  public class InnerOfGeneric { public T value; }
  public enum Op implements IntBinaryOperator {
    ADD { public int applyAsInt(int a, int b) { return a + b; } },
    MUL { public int applyAsInt(int a, int b) { return a * b; } };
  }
  public static class Checked extends Exception { public Checked(String m) { super(m); } }
  public static void mayThrow(boolean b) throws Checked { if (b) throw new Checked("x"); }
}
