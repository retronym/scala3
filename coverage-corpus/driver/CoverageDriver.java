import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Runs many compilation jobs in one warm JVM.
 *
 * Reads a job file (see harness.py `write_jobs`) of the form
 *
 *   @job <id> <execOut|->
 *   @scalac
 *   <arg>
 *   ...
 *   @javac
 *   <arg>
 *   ...
 *   @end
 *
 * Each job is a list of steps, run in order; a job fails at its first failing step.
 * After each job, if the JaCoCo agent is attached and execOut is not "-", the agent's
 * execution data is dumped (and reset) to execOut, giving per-job coverage.
 *
 * The compiler is called reflectively (`dotty.tools.dotc.Main.process(String[])`) so the
 * driver works against any Scala 3 compiler on the classpath.
 *
 * Progress is reported on the original stdout as `@@RESULT <id> <OK|FAIL|CRASH> <millis> <detail>`.
 */
public class CoverageDriver {
  static final PrintStream realOut = System.out;
  static final PrintStream realErr = System.err;

  // `@tasty-in:<dir>` expands to the .tasty files under <dir>, which only exist once earlier steps have run.
  static String[] expand(List<String> args) throws IOException {
    List<String> result = new ArrayList<>();
    for (String a : args) {
      if (a.startsWith("@tasty-in:")) {
        try (var files = Files.walk(Paths.get(a.substring("@tasty-in:".length())))) {
          files.map(Path::toString).filter(f -> f.endsWith(".tasty")).sorted().forEach(result::add);
        }
      } else result.add(a);
    }
    return result.toArray(new String[0]);
  }

  static Method process;
  static Object agent;
  static Method getExecutionData;

  public static void main(String[] args) throws Exception {
    process = Class.forName("dotty.tools.dotc.Main").getMethod("process", String[].class);
    try {
      Class<?> rt = Class.forName("org.jacoco.agent.rt.RT");
      agent = rt.getMethod("getAgent").invoke(null);
      getExecutionData = agent.getClass().getMethod("getExecutionData", boolean.class);
      getExecutionData.setAccessible(true);
    } catch (ClassNotFoundException e) {
      // no coverage
    }
    if (args[0].equals("--serve")) {
      serve(Paths.get(args[1]));
      return;
    }
    List<String> lines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
    boolean quiet = args.length > 1 && args[1].equals("--quiet");
    PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
    runJobs(lines, quiet ? sink : null, realOut, sink);
  }

  /**
   * Keeps a warm compiler JVM: picks up `<name>.job` files dropped into `dir`, writes all output of the jobs to
   * `<name>.out`, then renames the job file to `<name>.done`.
   */
  static void serve(Path dir) throws Exception {
    // scala.Console captures System.out on first use, so install one stream up front and retarget it per request
    var target = new Object() { volatile OutputStream out = realOut; };
    PrintStream switching = new PrintStream(new OutputStream() {
      public void write(int b) throws IOException { target.out.write(b); }
      public void write(byte[] b, int off, int len) throws IOException { target.out.write(b, off, len); }
      public void flush() throws IOException { target.out.flush(); }
    }, true);
    System.setOut(switching);
    System.setErr(switching);
    while (true) {
      List<Path> jobs;
      try (var ls = Files.list(dir)) {
        jobs = ls.filter(p -> p.toString().endsWith(".job")).sorted().toList();
      }
      for (Path job : jobs) {
        String base = job.toString().substring(0, job.toString().length() - 4);
        try (PrintStream out = new PrintStream(new FileOutputStream(base + ".out"), true)) {
          List<String> lines = Files.readAllLines(job, StandardCharsets.UTF_8);
          target.out = out;
          try {
            runJobs(lines, null, out, out);
          } catch (Throwable t) {
            t.printStackTrace(out);
          } finally {
            switching.flush();
            target.out = realOut;
          }
        }
        Files.move(job, Paths.get(base + ".done"), StandardCopyOption.REPLACE_EXISTING);
      }
      if (jobs.isEmpty()) Thread.sleep(50);
    }
  }

  /** @param capture where compiler output goes (null: leave System.out/err alone) */
  static void runJobs(List<String> lines, PrintStream capture, PrintStream results, PrintStream javacOut) throws Exception {

    int i = 0;
    while (i < lines.size()) {
      String header = lines.get(i++);
      if (header.isEmpty()) continue;
      if (!header.startsWith("@job ")) throw new IllegalStateException("bad job file at: " + header);
      String[] h = header.split(" ", 3);
      String id = h[1];
      String execOut = h[2];
      List<List<String>> steps = new ArrayList<>();
      List<String> kinds = new ArrayList<>();
      List<String> cur = null;
      while (true) {
        String l = lines.get(i++);
        if (l.equals("@end")) break;
        if (l.equals("@scalac") || l.equals("@javac") || l.startsWith("@main:") || l.startsWith("@driver:")) {
          cur = new ArrayList<>();
          steps.add(cur);
          kinds.add(l.substring(1));
        } else {
          cur.add(l);
        }
      }

      long t0 = System.nanoTime();
      String status = "OK";
      String detail = "";
      if (capture != null) { System.setOut(capture); System.setErr(capture); }
      try {
        for (int s = 0; s < steps.size(); s++) {
          String[] stepArgs = expand(steps.get(s));
          if (kinds.get(s).equals("scalac")) {
            Object reporter = process.invoke(null, (Object) stepArgs);
            boolean hasErrors = (Boolean) reporter.getClass().getMethod("hasErrors").invoke(reporter);
            if (hasErrors) { status = "FAIL"; detail = "scalac step " + s; break; }
          } else if (kinds.get(s).startsWith("driver:")) {
            // a dotc.Driver subclass, e.g. the decompiler: process(args) returns a Reporter
            Object reporter = Class.forName(kinds.get(s).substring(7)).getMethod("process", String[].class).invoke(null, (Object) stepArgs);
            boolean hasErrors = (Boolean) reporter.getClass().getMethod("hasErrors").invoke(reporter);
            if (hasErrors) { status = "FAIL"; detail = "driver step " + s; break; }
          } else if (kinds.get(s).startsWith("main:")) {
            // another compiler entry point, e.g. dotty.tools.dotc.core.tasty.TastyPrinter
            Class.forName(kinds.get(s).substring(5)).getMethod("main", String[].class).invoke(null, (Object) stepArgs);
          } else {
            javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
            int rc = javac.run(null, javacOut, javacOut, stepArgs);
            if (rc != 0) { status = "FAIL"; detail = "javac step " + s; break; }
          }
        }
      } catch (Throwable t) {
        Throwable c = t instanceof java.lang.reflect.InvocationTargetException ? t.getCause() : t;
        status = "CRASH";
        detail = String.valueOf(c).replace('\n', ' ');
        if (capture == null) c.printStackTrace();  // `serve`: stderr goes to the job's .out
      } finally {
        if (capture != null) { System.setOut(realOut); System.setErr(realErr); }
      }
      long ms = (System.nanoTime() - t0) / 1_000_000;
      if (agent != null && !execOut.equals("-")) {
        byte[] data = (byte[]) getExecutionData.invoke(agent, true);
        Files.write(Paths.get(execOut), data);
      }
      results.println("@@RESULT " + id + " " + status + " " + ms + " " + detail);
      results.flush();
    }
  }
}
