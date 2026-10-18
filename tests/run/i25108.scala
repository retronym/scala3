// A class's implementation of the trait setter of a trait val that the class overrides
// must not write the overriding val's field.

trait Trait:
  val optionValue: Option[Unit] = None

object Obj extends Trait:
  override val optionValue: Some[Unit] = Some(())

trait Logger:
  val prefix: String = "[log]"
  val seenInInit: String = prefix

class Service extends Logger:
  override val prefix: String = "[svc]"

trait Override extends Logger:
  override val prefix: String = "[override]"

class Linearized extends Logger with Override

class Param(override val prefix: String) extends Logger

class Plain extends Logger

@main def Test =
  println(Obj.optionValue)
  for l <- List(Service(), Linearized(), Param("[param]"), Plain()) do
    println((l.prefix, l.seenInInit))
