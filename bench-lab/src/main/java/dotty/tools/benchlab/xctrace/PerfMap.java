package dotty.tools.benchlab.xctrace;

import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JIT's code map: start, size and name of each compiled method. From the {@code Compiler.codelist} diagnostic
 * command (every platform; {@code Compiler.perfmap} is Linux only), invoked in-process, so no jcmd or extra JVM
 * flags. Methods compiled and then flushed before the dump are missing, and stubs are not listed.
 */
final class PerfMap {
    // "<id> <tier> <state> <method> [<blob start>, <code begin> - <code end>]"
    private static final Pattern LINE = Pattern.compile("^\\d+ \\d+ \\d+ (\\S+) \\[(0x\\p{XDigit}+), 0x\\p{XDigit}+ - (0x\\p{XDigit}+)\\]");

    private final TreeMap<Long, long[]> starts = new TreeMap<>();   // start -> [size, index]
    private final List<String> names = new ArrayList<>();

    private PerfMap(String codelist) {
        for (String line : codelist.split("\n")) {
            Matcher m = LINE.matcher(line);
            if (!m.find()) continue;
            long start = Long.parseUnsignedLong(m.group(2).substring(2), 16);
            long end = Long.parseUnsignedLong(m.group(3).substring(2), 16);
            starts.put(start, new long[]{end - start, names.size()});
            names.add(m.group(1).replaceFirst("\\(.*", ""));
        }
    }

    static PerfMap dump() {
        try {
            ObjectName dcmd = new ObjectName("com.sun.management:type=DiagnosticCommand");
            String out = (String) ManagementFactory.getPlatformMBeanServer().invoke(dcmd, "compilerCodelist",
                    new Object[]{new String[0]}, new String[]{String[].class.getName()});
            PerfMap map = new PerfMap(out);
            System.err.println("[xctrace] code map: " + map.names.size() + " compiled methods");
            return map;
        } catch (Exception e) {
            System.err.println("[xctrace] no code map, attribution off: " + e);
            return null;
        }
    }

    /** The compiled method containing addr; else the symbol xctrace gave the frame (native code); else the address. */
    String name(String addr, String frameName) {
        if (addr != null) {
            long a = Long.parseUnsignedLong(addr.replaceFirst("^0x", ""), 16);
            Map.Entry<Long, long[]> e = starts.floorEntry(a);
            if (e != null && a < e.getKey() + e.getValue()[0]) return names.get((int) e.getValue()[1]);
        }
        if (frameName != null && !frameName.startsWith("0x")) return frameName;
        return addr == null ? "<no backtrace>" : "<unknown " + addr + ">";
    }
}
