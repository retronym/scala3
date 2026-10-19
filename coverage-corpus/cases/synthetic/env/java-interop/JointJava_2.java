// Stage 2, jointly compiled with Use_2.scala: parsed by dotc's Java parser, then compiled by javac.
package juse;

import java.util.*;
import java.util.function.*;
import jlib.JLib_1;

class JointJava extends AbstractList<String> implements JLib_1.IntOp {
  public static final int K = 1 + 2 * 3;
  public static final String S = "a" + "b";
  private final Map<String, List<? extends Number>> m = new HashMap<>();
  @Override public String get(int i) { return S; }
  @Override public int size() { return K; }
  public int apply(int a, int b) { return a - b; }
  public int useScala(ScalaSide s) { return s.n() + 1; }
  public <T extends Comparable<T>> T id(T t) { return t; }
  public static <T> List<T> listOf(T... ts) { return Arrays.asList(ts); }
  protected void varargs(int x, String... ys) {}
  @Deprecated(since = "1", forRemoval = false) public void dep() throws Exception {}
  public static class Nested<A> {
    private final A a;
    public Nested(A a) { this.a = a; }
    public A get() { return a; }
    public <B> Nested<B> map(Function<? super A, ? extends B> f) { return new Nested<>(f.apply(a)); }
  }
  public enum Mode { ON, OFF; public Mode flip() { return this == ON ? OFF : ON; } }
  public interface Visitor<R> { R visit(Object o); default R twice(Object o) { visit(o); return visit(o); } }
  public record Rec(String name, int[] data) {}
  static { System.getProperty("x"); }
  { int unused = 0; }
  public int[] arr = {1, 2, 3};
  public char c = 'c';
  public double d = 1e10;
  public long l = 0xFFFFL;
  public float f = 1.5f;
  public byte by = (byte) 1;
  public short sh = (short) 2;
  public boolean bo = !false;
}
