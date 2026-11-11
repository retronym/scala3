object Test {
  def main(args: Array[String]): Unit = {
    try {
      Int.box(???)
      sys.error("no exception")
    } catch {
      case _: NotImplementedError =>
      case e: Throwable =>
        sys.error("wrong exception: " + e)
    }
  }
}
