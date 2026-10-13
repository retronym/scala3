package unroller.pe;

// Copied from github.com/retronym/jvm-loop-unroller (DO NOT MERGE): the partial evaluator recognises these markers by
// class name, and this copy compiles at the compiler's release.

/**
 * Markers read by {@link PartialEvaluator}. Both are identity functions, so a template method runs
 * unmodified (and debuggably) when it is not specialised.
 */
public final class Specialize {
    private Specialize() {}

    /**
     * Asserts {@code v} is known at specialisation time and immutable from then on: its identity, and
     * for an array its length and elements, are folded into the generated code.
     */
    public static <T> T constant(T v) {
        return v;
    }

    /**
     * "The Trick": {@code i} is dynamic but expected in {@code [0, n)} ({@code n} must be known). The
     * partial evaluator emits a {@code tableswitch} with one arm per value, continuing with {@code i}
     * constant in each arm; out-of-range values continue with {@code i} dynamic.
     */
    public static int split(int i, int n) {
        return i;
    }
}
