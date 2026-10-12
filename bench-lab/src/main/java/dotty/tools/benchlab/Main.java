package dotty.tools.benchlab;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code org.openjdk.jmh.Main} with bench-lab's default profilers: {@code gc} (allocation per op, GC count and time)
 * and {@link JitProfiler} (is the JIT still busy?). A default is skipped if given on the command line;
 * {@code -noDefaultProf} drops them all.
 */
public class Main {
    static final List<String> DEFAULTS = List.of("gc", JitProfiler.class.getName());

    public static void main(String[] argv) throws Exception {
        List<String> args = new ArrayList<>(List.of(argv));
        boolean defaults = !args.remove("-noDefaultProf");
        List<String> extra = new ArrayList<>();
        if (defaults)
            for (String p : DEFAULTS)
                if (!given(args, p)) { extra.add("-prof"); extra.add(p); }
        extra.addAll(args);
        org.openjdk.jmh.Main.main(extra.toArray(new String[0]));
    }

    private static boolean given(List<String> args, String prof) {
        for (int i = 0; i + 1 < args.size(); i++)
            if (args.get(i).equals("-prof") && args.get(i + 1).split(":", 2)[0].equals(prof)) return true;
        return false;
    }
}
