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

  public static void main(String[] args) throws Exception {
    List<String> lines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
    boolean quiet = args.length > 1 && args[1].equals("--quiet");
    Method process = Class.forName("dotty.tools.dotc.Main").getMethod("process", String[].class);
    Object agent = null;
    Method getExecutionData = null;
    try {
      Class<?> rt = Class.forName("org.jacoco.agent.rt.RT");
      agent = rt.getMethod("getAgent").invoke(null);
      getExecutionData = agent.getClass().getMethod("getExecutionData", boolean.class);
      getExecutionData.setAccessible(true);
    } catch (ClassNotFoundException e) {
      // no coverage
    }
    PrintStream sink = new PrintStream(OutputStream.nullOutputStream());

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
        if (l.equals("@scalac") || l.equals("@javac")) {
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
      if (quiet) { System.setOut(sink); System.setErr(sink); }
      try {
        for (int s = 0; s < steps.size(); s++) {
          String[] stepArgs = expand(steps.get(s));
          if (kinds.get(s).equals("scalac")) {
            Object reporter = process.invoke(null, (Object) stepArgs);
            boolean hasErrors = (Boolean) reporter.getClass().getMethod("hasErrors").invoke(reporter);
            if (hasErrors) { status = "FAIL"; detail = "scalac step " + s; break; }
          } else {
            javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
            int rc = javac.run(null, sink, sink, stepArgs);
            if (rc != 0) { status = "FAIL"; detail = "javac step " + s; break; }
          }
        }
      } catch (Throwable t) {
        Throwable c = t instanceof java.lang.reflect.InvocationTargetException ? t.getCause() : t;
        status = "CRASH";
        detail = String.valueOf(c).replace('\n', ' ');
      } finally {
        if (quiet) { System.setOut(realOut); System.setErr(realOut); }
      }
      long ms = (System.nanoTime() - t0) / 1_000_000;
      if (agent != null && !execOut.equals("-")) {
        byte[] data = (byte[]) getExecutionData.invoke(agent, true);
        Files.write(Paths.get(execOut), data);
      }
      realOut.println("@@RESULT " + id + " " + status + " " + ms + " " + detail);
      realOut.flush();
    }
  }
}
