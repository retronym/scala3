// LLM-assisted (Claude), see LLM_POLICY.md.
// usage: java -cp byte-buddy.jar:. AllocCheck <dump dir>   (dir from -Dnet.bytebuddy.dump)
// For each instrumented class, per method whose bytecode changed: allocation sites
// (NEW, NEWARRAY, ANEWARRAY, MULTIANEWARRAY, INVOKEDYNAMIC, boxing valueOf) before and after.
import java.nio.file.*;
import java.util.*;
import net.bytebuddy.jar.asm.*;

public class AllocCheck {
  record M(int allocs, int size, String allocList) {}

  static Map<String, M> scan(byte[] b) {
    Map<String, M> res = new TreeMap<>();
    new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
      public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] e) {
        String key = n + d;
        return new MethodVisitor(Opcodes.ASM9) {
          int allocs, size; StringBuilder sb = new StringBuilder();
          void a(String w) { allocs++; sb.append(w).append(' '); }
          public void visitInsn(int op) { size++; }
          public void visitVarInsn(int op, int v) { size++; }
          public void visitFieldInsn(int op, String o, String nn, String dd) { size++; }
          public void visitJumpInsn(int op, Label l) { size++; }
          public void visitTypeInsn(int op, String t) { size++; if (op == Opcodes.NEW || op == Opcodes.ANEWARRAY) a((op == Opcodes.NEW ? "new " : "anewarray ") + t); }
          public void visitIntInsn(int op, int v) { size++; if (op == Opcodes.NEWARRAY) a("newarray"); }
          public void visitMultiANewArrayInsn(String d2, int dims) { size++; a("multianewarray"); }
          public void visitInvokeDynamicInsn(String nn, String dd, Handle h, Object... args) { size++; a("indy " + nn); }
          public void visitMethodInsn(int op, String o, String nn, String dd, boolean itf) {
            size++;
            if (nn.equals("valueOf") && o.startsWith("java/lang/") && dd.startsWith("(") && dd.charAt(1) != 'L') a("box " + o);
          }
          public void visitEnd() { res.put(key, new M(allocs, size, sb.toString())); }
        };
      }
    }, 0);
    return res;
  }

  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[0]);
    try (var s = Files.list(dir)) {
      for (Path inst : s.filter(p -> !p.toString().contains("-original.")).sorted().toList()) {
        String f = inst.getFileName().toString();
        int dot = f.lastIndexOf('.', f.length() - 7);
        Path orig = dir.resolve(f.substring(0, dot) + "-original" + f.substring(dot));
        if (!Files.exists(orig)) continue;
        Map<String, M> a = scan(Files.readAllBytes(orig)), b = scan(Files.readAllBytes(inst));
        for (var e : b.entrySet()) {
          M o = a.get(e.getKey()), n = e.getValue();
          if (o != null && o.size == n.size) continue;
          System.out.printf("%-50s %-28s insns %5s -> %5d  allocs %2s -> %2d %s%n", f.substring(0, dot), e.getKey().replaceAll("\\(.*", "()"),
            o == null ? "-" : o.size, n.size, o == null ? "-" : o.allocs, n.allocs,
            (o != null && o.allocs != n.allocs) ? "  ADDED: " + n.allocList : "");
        }
      }
    }
  }
}
