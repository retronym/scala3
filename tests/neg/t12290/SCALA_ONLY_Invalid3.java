class SCALA_ONLY_Invalid3 {

    // Text block runs to the end of the file without a closing delimiter
    public static final String unclosed = """
      foo
      bar;
} // anypos-error
