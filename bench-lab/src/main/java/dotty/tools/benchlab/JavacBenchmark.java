package dotty.tools.benchlab;

import io.github.retronym.perflab.jmh.Announce;

import org.openjdk.jmh.annotations.*;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * javac on the same terms as {@link CompileBenchmark}, as a yardstick: one op compiles a Java corpus (re2j, the
 * Java original of the re2s corpus), sources read into memory once and class files kept in memory, so the
 * measured region does no file I/O. The corpus directory is {@code -Dbenchlab.javaCorpora=<dir>}, holding one
 * directory per corpus (e.g. compiler-benchmark's {@code corpus}, where {@code re2j/latest} is the sources).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms4G", "-Xmx4G", "-Xss2M"})
@Warmup(iterations = 20, time = 3)
@Measurement(iterations = 10, time = 3)
public class JavacBenchmark {
    @Param({"re2j"})
    public String javaCorpus;

    private JavaCompiler javac;
    // created once and reused, as dotc's jar classpath is: it caches the platform classes (ct.sym) it has opened
    private StandardJavaFileManager std;
    private List<JavaFileObject> sources;
    private int outputs;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        Announce.jvm("JavacBenchmark(" + javaCorpus + ")");
        Path root = Path.of(Objects.requireNonNull(System.getProperty("benchlab.javaCorpora"),
                "-Dbenchlab.javaCorpora=<dir> not set"));
        Path dir = (Files.isDirectory(root.resolve(javaCorpus).resolve("latest"))
                ? root.resolve(javaCorpus).resolve("latest") : root.resolve(javaCorpus)).toRealPath(); // latest is a symlink
        sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                String name = dir.relativize(p).toString();
                sources.add(new SimpleJavaFileObject(URI.create("mem:///" + name), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return text; }
                });
            }
        }
        if (sources.isEmpty()) throw new IllegalStateException("no .java sources under " + dir);
        javac = ToolProvider.getSystemJavaCompiler();
        std = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        compile();
        System.out.println("\n[bench-lab] " + javaCorpus + ": " + sources.size() + " sources -> " + outputs + " class files");
    }

    @Benchmark
    public int compile() {
        Map<String, ByteArrayOutputStream> out = new HashMap<>();
        JavaFileManager fm = new ForwardingJavaFileManager<>(std) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override public OutputStream openOutputStream() {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        out.put(className, bytes);
                        return bytes;
                    }
                };
            }
        };
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        // re2j here lives in java.util.regex: compile it as pre-module Java, as compiler-benchmark did
        List<String> options = List.of("--release", "8", "-nowarn", "-Xlint:-options");
        boolean ok = javac.getTask(null, fm, diags, options, null, sources).call();
        if (!ok) throw new IllegalStateException("javac failed: " + diags.getDiagnostics().stream().limit(5).toList());
        outputs = out.size();
        return outputs;
    }
}
