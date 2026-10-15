package dotty.tools.benchlab;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code org.openjdk.jmh.Main} with bench-lab's default profilers: {@code gc} (allocation per op, GC count and time)
 * {@link JitProfiler} (is the JIT still busy?) and {@link PairedProfiler} (time per variant, when variants alternate
 * within the fork; otherwise silent). A default is skipped if given on the command line;
 * {@code -noDefaultProf} drops them all.
 */
public class Main {
    static final List<String> DEFAULTS = List.of("gc", JitProfiler.class.getName(), PairedProfiler.class.getName());

    public static void main(String[] argv) throws Exception {
        List<String> args = new ArrayList<>(List.of(argv));
        boolean defaults = !args.remove("-noDefaultProf");
        // Defaults go last: JMH runs beforeIteration in order and afterIteration in reverse, so JitProfiler's window
        // then excludes other profilers' own setup and analysis (xctrace's start, parsing its export).
        List<String> extra = new ArrayList<>(args);
        if (defaults)
            for (String p : DEFAULTS)
                if (!given(args, p)) { extra.add("-prof"); extra.add(p); }
        org.openjdk.jmh.Main.main(extra.toArray(new String[0]));
    }

    private static boolean given(List<String> args, String prof) {
        for (int i = 0; i + 1 < args.size(); i++)
            if (args.get(i).equals("-prof") && args.get(i + 1).split(":", 2)[0].equals(prof)) return true;
        return false;
    }
}
