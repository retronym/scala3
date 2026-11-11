import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.jacoco.core.analysis.*;
import org.jacoco.core.data.*;

/**
 * Maps each JaCoCo probe of each class to the source lines it marks as covered.
 *
 * JaCoCo line coverage is monotone in the probes: a line is covered iff some instruction on it is, and an instruction
 * is covered iff some probe whose backwards propagation reaches it fired. So analysing each probe on its own gives
 * sets whose union over a job's fired probes is exactly that job's line coverage.
 *
 * Usage: ProbeLines <classes.tsv> <out.tsv> <class dir or jar>...
 *   classes.tsv: classId \t vmName \t nprobes   (from the harness registry)
 *   out.tsv:     vmName \t sourceFile \t probe \t line,line,...   (probes that cover no line are omitted)
 */
public class ProbeLines {
  public static void main(String[] args) throws Exception {
    Map<String, byte[]> bytes = new HashMap<>();
    for (int i = 2; i < args.length; i++) {
      Path root = Paths.get(args[i]);
      if (Files.isDirectory(root)) {
        try (var files = Files.walk(root)) {
          for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
            String name = root.relativize(p).toString();
            bytes.put(name.substring(0, name.length() - 6), Files.readAllBytes(p));
          }
        }
      }
    }
    List<String[]> classes = new ArrayList<>();
    for (String l : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) classes.add(l.split("\t"));

    ConcurrentLinkedQueue<String> out = new ConcurrentLinkedQueue<>();
    ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    List<Future<?>> fs = new ArrayList<>();
    int[] skipped = {0};
    for (String[] c : classes) {
      long id = Long.parseLong(c[0]);
      String name = c[1];
      int n = Integer.parseInt(c[2]);
      byte[] b = bytes.get(name);
      if (b == null || org.jacoco.core.internal.data.CRC64.classId(b) != id) { skipped[0]++; continue; }
      fs.add(pool.submit(() -> {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < n; p++) {
          boolean[] probes = new boolean[n];
          probes[p] = true;
          ExecutionDataStore store = new ExecutionDataStore();
          store.put(new ExecutionData(id, name, probes));
          CoverageBuilder cb = new CoverageBuilder();
          try {
            new Analyzer(store, cb).analyzeClass(b, name);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          for (IClassCoverage cc : cb.getClasses()) {
            StringBuilder lines = new StringBuilder();
            for (int l = cc.getFirstLine(); l > 0 && l <= cc.getLastLine(); l++) {
              int st = cc.getLine(l).getStatus();
              if (st == ICounter.FULLY_COVERED || st == ICounter.PARTLY_COVERED) {
                if (lines.length() > 0) lines.append(',');
                lines.append(l);
              }
            }
            if (lines.length() > 0)
              sb.append(name).append('\t').append(cc.getSourceFileName()).append('\t').append(p).append('\t').append(lines).append('\n');
          }
        }
        out.add(sb.toString());
      }));
    }
    for (Future<?> f : fs) f.get();
    pool.shutdown();
    try (Writer w = Files.newBufferedWriter(Paths.get(args[1]))) {
      for (String s : out) w.write(s);
    }
    System.err.println("classes: " + classes.size() + ", skipped (no matching classfile): " + skipped[0]);
  }
}
