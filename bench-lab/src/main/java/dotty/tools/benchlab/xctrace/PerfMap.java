package dotty.tools.benchlab.xctrace;

import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * The JIT's code map (start, size, name per compiled method and stub), written by the {@code Compiler.perfmap}
 * diagnostic command to /tmp/perf-<pid>.map. Dumped from inside the JVM, so no jcmd or extra JVM flags.
 */
final class PerfMap {
    private final TreeMap<Long, long[]> starts = new TreeMap<>();   // start -> [size, index]
    private final String[] names;

    private PerfMap(Path file) throws IOException {
        var lines = Files.readAllLines(file);
        names = new String[lines.size()];
        int i = 0;
        for (String line : lines) {
            String[] p = line.split(" ", 3);
            if (p.length < 3) continue;
            long start = Long.parseUnsignedLong(p[0].replaceFirst("^0x", ""), 16);
            long size = Long.parseUnsignedLong(p[1].replaceFirst("^0x", ""), 16);
            names[i] = p[2];
            starts.put(start, new long[]{size, i++});
        }
    }

    static PerfMap dump() {
        try {
            ObjectName dcmd = new ObjectName("com.sun.management:type=DiagnosticCommand");
            ManagementFactory.getPlatformMBeanServer().invoke(dcmd, "compilerPerfmap",
                    new Object[]{new String[0]}, new String[]{String[].class.getName()});
            return new PerfMap(Path.of("/tmp/perf-" + ProcessHandle.current().pid() + ".map"));
        } catch (Exception e) {
            System.err.println("[xctrace] no perf map, attribution off: " + e);
            return null;
        }
    }

    /** The compiled method containing addr; else the symbol xctrace gave the frame (native code); else the address. */
    String name(String addr, String frameName) {
        if (addr != null) {
            long a = Long.parseUnsignedLong(addr.replaceFirst("^0x", ""), 16);
            Map.Entry<Long, long[]> e = starts.floorEntry(a);
            if (e != null && a < e.getKey() + e.getValue()[0]) return names[(int) e.getValue()[1]];
        }
        if (frameName != null && !frameName.startsWith("0x")) return frameName;
        return addr == null ? "<no backtrace>" : "<unknown " + addr + ">";
    }
}
