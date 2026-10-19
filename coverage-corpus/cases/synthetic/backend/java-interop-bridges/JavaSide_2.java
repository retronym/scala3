// Stage 2 Java, jointly compiled with Impl_2.scala: Java implementing a Scala trait and extending a Scala class.
package jb2;

class JavaSide extends ScalaAbs<String> implements ScalaTrait {
  private int v = 0;
  JavaSide() { super("from java"); }
  public int abstractM(int i) { return i * 10; }
  public int concrete(int x) { return ScalaTrait.super.concrete(x) + 100; }
  public String traitVal() { return "jtv"; }
  public int traitVar() { return v; }
  public void traitVar_$eq(int x) { v = x; }
  public void jb2$ScalaTrait$_setter_$traitVal_$eq(String s) {}
  static int fromScala(Impl i) { return i.useProtected(); }
}
