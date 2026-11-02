//> using options -Wshadow:type-parameter-shadow

// The `scala` package has synthesized members: FunctionN for N > 22, and ContextFunctionN
// and ImpureFunctionN for all N. They are created and entered into the package's scope
// when their name is first looked up (Definitions.ScalaPackageClass, Scope.lookupEntry).
// CheckShadowing enumerates the type members of `scala`, so it knows these names only if
// earlier code mentioned them. Both type parameters shadow a type of `scala` and should
// be reported whether or not `synthetic-scala-members-deterministic-mentioned.scala`'s
// prior mention is present. Here nothing mentions them first, and no warning is reported.
class C:
  def f[
    ContextFunction3, // warn
    Function23 // warn
  ](x: ContextFunction3, y: Function23) = x
