trait Trait:
  val optionValue: Option[Unit] = None

object Obj extends Trait:
  override val optionValue: Some[Unit] = Some(())

@main def Test = println(Obj.optionValue)
