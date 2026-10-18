// scalajs: --skip

// Like i8033, but for named local classes, whose unused outer pointer
// should also be dropped.

trait Okay extends Serializable {
  def okay: Okay
}

class Foo {
  def okay1: Okay = {
    class Local extends Okay {
      val okay: Okay = this
      override def toString = "okay1"
    }
    new Local
  }
  def okay2: Okay = {
    class Local extends Okay {
      val okay: Okay = okay1
      override def toString = "okay2"
    }
    new Local
  }
}

object Test {
  def main(args: Array[String]): Unit = {
    val foo = new Foo
    assert(roundTrip(foo.okay1).toString == "okay1")
    assert(roundTrip(foo.okay2).toString == "okay2")
  }

  def roundTrip[A](a: A): A = {
    import java.io.*

    val aos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(aos)
    oos.writeObject(a)
    oos.close()
    val ais = new ByteArrayInputStream(aos.toByteArray())
    val ois: ObjectInputStream = new ObjectInputStream(ais)
    val newA = ois.readObject()
    ois.close()
    newA.asInstanceOf[A]
  }
}
