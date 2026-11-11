//> using options -experimental
import scala.language.experimental.dedentedStringLiterals
def helper(n: Int) =
  val x = '''
    i am cow
      hear me moo
    '''
  val y = '''

      a
      b

    '''
  val z = s'''
    n = $n
    ${n + 1}
    '''
  x + y + z
