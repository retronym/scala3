//> using scala 3.7.4
//> using jvm 21

// A toy of dotc's signature completion, to try ways of completing signatures on several threads.
//
// What it keeps from dotc:
//  - classes complete in stages: their decls are entered first (a temporary info, `TempClassInfo`), then their
//    parents are typed, which can evaluate a constructor argument `extends P(O.m)`, and only then is the info final;
//  - a member's signature is declared, or inferred from another member's (`def m = O.n`);
//  - re-entry on one thread: a class in progress on this thread reads as its temporary info, a member in progress
//    on this thread is a cyclic reference (an error, and an error type);
//  - a claim per completion root, a coarse root for some classes (a TASTy file's), waits between threads, and a
//    wait-for cycle found when a wait would close one.
//
// The threads are real, but a scheduler runs one at a time, the one with the earliest virtual clock (ties broken by
// a seeded random), so a run is reproducible from its seed and its makespan is the largest clock.

import java.util.concurrent.{Semaphore, CountDownLatch}
import scala.collection.mutable
import scala.util.Random

object Toy:

  // ---- programs

  final case class Ref(owner: String, mem: String):
    override def toString = s"$owner.$mem"
  enum Rhs:
    case Declared(tpe: String)
    case Inferred(ref: Ref)
  final case class Mem(name: String, rhs: Rhs)
  final case class Parent(cls: String, arg: Option[Ref])
  final case class Cls(name: String, parents: List[Parent], members: Vector[Mem], root: String)
  final case class Prog(units: Vector[Vector[Cls]]):
    def classes = units.flatten

  // ---- results: what the serial compiler would record

  final case class Result(bases: Map[String, List[String]], infos: Map[String, String], errors: Set[String])

  // ---- the strategies

  enum Strategy:
    case Abort      // a wait that would close a cycle aborts the waiter (the PoC's answer; batch must fall back)
    case Lend       // the waiter that closes the cycle borrows the claim it waits for, as a serial nesting would
  // staged: a class's decls are a fact of their own (a claim of their own), so a read of the decls completes only
  // them, not the class's parents, as K2 resolves a declaration to a phase
  // lookupNeedsParents: a member lookup `O.m` needs O's parents too (inherited members), as dotc's findMember does;
  // on a class whose parents are in progress it reads its own decls only, a partial lookup: an unstable read
  final case class Config(strategy: Strategy, workers: Int, headersFirst: Boolean, staged: Boolean = false,
                          lookupNeedsParents: Boolean = false)

  enum Outcome:
    case Equal, Different, Aborted, Deadlock

  final class Aborted extends RuntimeException(null, null, false, false)
  final class Killed extends RuntimeException(null, null, false, false)

  // ---- the scheduler

  final class Worker(val id: Int):
    var clock = 0L
    var waitingOn: Claim | Null = null
    var finished = false
    val sem = Semaphore(0)
    // the workers whose claims this one holds as a guest (Lend), innermost first
    var lenders: List[Worker] = Nil
    def lentBy(o: Worker): Boolean = lenders.exists(l => (l eq o) || l.lentBy(o))
    override def toString = s"w$id"

  final class Claim(val key: String, val owner: Worker):
    var holds = 1
    val waiters = mutable.ArrayBuffer.empty[Worker]

  final class Sim(seed: Long):
    val rnd = Random(seed)
    var workers: Vector[Worker] = Vector.empty
    var killed = false
    var deadlock = false
    var aborted = false
    var done: CountDownLatch = CountDownLatch(1)
    val claims = mutable.HashMap.empty[String, Claim]

    private def pick(): Worker | Null =
      val ready = workers.filter(w => !w.finished && w.waitingOn == null)
      if ready.isEmpty then null
      else
        val min = ready.map(_.clock).min
        val tied = ready.filter(_.clock == min)
        tied(rnd.nextInt(tied.size))

    /** Hand the baton to the next worker; `w` resumes when it is picked again. */
    def switchFrom(w: Worker): Unit =
      pick() match
        case null =>
          if workers.forall(_.finished) then done.countDown()
          else
            deadlock = true; killed = true
            workers.foreach(_.sem.release())
            done.countDown()
          if !w.finished then throw Killed()
        case next: Worker =>
          if next ne w then
            next.sem.release()
            if !w.finished then
              w.sem.acquire()
              if killed then throw Killed()

    def work(w: Worker, n: Int): Unit =
      w.clock += n
      switchFrom(w)

    /** Run `tasks`, taken in order by `n` workers; the clocks start at `start`. Returns the largest clock. */
    def run(n: Int, start: Long, tasks: Vector[Worker => Unit]): Long =
      val queue = mutable.Queue.from(tasks)
      done = CountDownLatch(1)
      workers = Vector.tabulate(n)(Worker(_))
      workers.foreach(_.clock = start)
      val threads = workers.map: w =>
        Thread.ofPlatform().daemon().start: () =>
          w.sem.acquire()
          try
            if !killed then
              var t = queue.removeHeadOption()
              while t.isDefined do
                t.get(w)
                t = queue.removeHeadOption()
          catch
            case _: Killed => ()
            case _: Aborted =>
              aborted = true; killed = true
              workers.foreach(_.sem.release())
              done.countDown()
          finally
            if !killed then
              w.finished = true
              switchFrom(w)
      pick() match
        case w: Worker => w.sem.release()
        case null => done.countDown()
      done.await()
      threads.foreach(_.join())
      workers.map(_.clock).max

  // ---- the symbol table

  enum State:
    case Lazy
    case InProgress(owner: Worker)
    case Done

  final class ClassSym(val cls: Cls):
    var state: State = State.Lazy
    var declsState: State = State.Lazy
    var bases: List[String] = Nil
  final class MemSym(val owner: String, val mem: Mem):
    var state: State = State.Lazy
    var info: String = ""

  // ---- completion

  final class Compiler(prog: Prog, sim: Sim, strategy: Strategy, staged: Boolean, lookupNeedsParents: Boolean):
    val classes = prog.classes.map(c => c.name -> ClassSym(c)).toMap
    val members = (for c <- prog.classes; m <- c.members yield s"${c.name}.${m.name}" -> MemSym(c.name, m)).toMap
    val errors = mutable.Set.empty[String]
    var guestSawProgress = 0
    // reads whose result depends on the order: bases of a class in progress, a member in progress (a cycle)
    var unstable = 0

    private def visible(w: Worker, s: State): Boolean = s match
      case State.InProgress(o) => (o eq w) || w.lentBy(o)
      case _ => false

    private def guestView(w: Worker, s: State): Unit = s match
      case State.InProgress(o) if o ne w => guestSawProgress += 1
      case _ =>

    /** Run `body` holding the claim on `key`: wait while another worker holds it; on a wait that would close a
     *  cycle, abort or borrow the claim. */
    private def withClaim[T](w: Worker, key: String)(body: => T): T =
      var lent: Worker | Null = null
      var own = false
      while lent == null && !own do
        sim.claims.get(key) match
          case None =>
            sim.claims(key) = Claim(key, w); own = true
          case Some(c) if c.owner eq w =>
            c.holds += 1; own = true
          case Some(c) if w.lentBy(c.owner) =>
            lent = c.owner
            w.lenders = c.owner :: w.lenders
          case Some(c) =>
            if closesCycle(w, c) then strategy match
              case Strategy.Abort => throw Aborted()
              case Strategy.Lend =>
                lent = c.owner
                w.lenders = c.owner :: w.lenders
            else
              w.waitingOn = c
              c.waiters += w
              sim.switchFrom(w)
      try body
      finally
        if lent != null then w.lenders = w.lenders.tail
        else
          val c = sim.claims(key)
          c.holds -= 1
          if c.holds == 0 then
            sim.claims.remove(key)
            for x <- c.waiters do
              x.waitingOn = null
              x.clock = math.max(x.clock, w.clock)

    private def closesCycle(w: Worker, c: Claim): Boolean =
      var o: Worker = c.owner
      var steps = 0
      while steps < 100 do
        if o eq w then return true
        o.waitingOn match
          case null => return false
          case next: Claim => o = next.owner
        steps += 1
      false

    /** Make the class's decls available: its completion started, on this worker (or a lender), or done. Reading
     *  decls from the temporary info is stable: they are entered before it is set, so every order reads the same. */
    def classDecls(w: Worker, name: String): Unit =
      if staged then stagedDecls(w, name) else fullDecls(w, name)

    private def stagedDecls(w: Worker, name: String): Unit =
      val c = classes(name)
      c.declsState match
        case State.Done => ()
        case s if visible(w, s) => guestView(w, s)
        case _ =>
          withClaim(w, c.cls.root + "#decls"):
            c.declsState match
              case State.Done => ()
              case s if visible(w, s) => guestView(w, s)
              case _ =>
                c.declsState = State.InProgress(w)
                sim.work(w, 2)
                c.declsState = State.Done

    /** The class's header, all of it: its decls, then its parents. */
    def classHeader(w: Worker, name: String): Unit =
      if staged then
        stagedDecls(w, name)
        val c = classes(name)
        c.state match
          case State.Done => ()
          case s if visible(w, s) => guestView(w, s)
          case _ =>
            withClaim(w, c.cls.root):
              c.state match
                case State.Done => ()
                case s if visible(w, s) => guestView(w, s)
                case _ => completeClass(w, c)
      else fullDecls(w, name)

    private def fullDecls(w: Worker, name: String): Unit =
      val c = classes(name)
      c.state match
        case State.Done => ()
        case s if visible(w, s) => guestView(w, s)
        case _ =>
          withClaim(w, c.cls.root):
            c.state match
              case State.Done => ()
              case s if visible(w, s) => guestView(w, s)
              case _ => completeClass(w, c)

    /** The class's bases. Reading them from a class in progress is unstable: its parents are not known yet, and what
     *  is read depends on where the cycle was entered. */
    def classBases(w: Worker, name: String): List[String] =
      classHeader(w, name)
      val c = classes(name)
      c.state match
        case State.Done => c.bases
        case s =>
          unstable += 1
          List(name)

    private def completeClass(w: Worker, c: ClassSym): Unit =
      c.state = State.InProgress(w)
      if !staged then sim.work(w, 2) // decls entered: the temporary info
      val bs = for p <- c.cls.parents yield
        val pb = classBases(w, p.cls)
        p.arg.foreach(memberInfo(w, _))
        sim.work(w, 1)
        pb
      c.bases = (c.cls.name :: bs.flatten).distinct
      c.state = State.Done
      sim.work(w, 1)

    def memberInfo(w: Worker, r: Ref): String =
      if lookupNeedsParents then
        classHeader(w, r.owner)
        if classes(r.owner).state != State.Done then unstable += 1
      else classDecls(w, r.owner)
      val m = members(r.toString)
      def cyclic() =
        errors += r.toString
        "<cyclic>"
      m.state match
        case State.Done => m.info
        case s if visible(w, s) => guestView(w, s); unstable += 1; cyclic()
        case _ =>
          withClaim(w, r.toString):
            m.state match
              case State.Done => m.info
              case s if visible(w, s) => guestView(w, s); unstable += 1; cyclic()
              case _ =>
                m.state = State.InProgress(w)
                sim.work(w, 3)
                m.info = m.mem.rhs match
                  case Rhs.Declared(t) => t
                  case Rhs.Inferred(ref) => s"(${memberInfo(w, ref)})"
                m.state = State.Done
                m.info

    def unitHeaders(cls: Vector[Cls])(w: Worker): Unit = cls.foreach(c => classHeader(w, c.name))
    def unitMembers(cls: Vector[Cls])(w: Worker): Unit =
      for c <- cls; m <- c.members do memberInfo(w, Ref(c.name, m.name))
    def unitAll(cls: Vector[Cls])(w: Worker): Unit =
      for c <- cls do
        classHeader(w, c.name)
        for m <- c.members do memberInfo(w, Ref(c.name, m.name))

    def result = Result(classes.view.mapValues(_.bases).toMap, members.view.mapValues(_.info).toMap, errors.toSet)

  final case class Stats(makespan: Long, guestReads: Int, unstable: Int)

  /** Compile `prog`: its result, the makespan, and how often a guest read another worker's state in progress. */
  def compile(prog: Prog, cfg: Config, seed: Long, order: Vector[Vector[Cls]] => Vector[Vector[Cls]] = identity): (Either[Outcome, Result], Stats) =
    val sim = Sim(seed)
    val comp = Compiler(prog, sim, cfg.strategy, cfg.staged, cfg.lookupNeedsParents)
    locally:
      val end =
        if cfg.headersFirst then
          val t = sim.run(cfg.workers, 0, order(prog.units).map(comp.unitHeaders))
          if sim.deadlock || sim.aborted then -1L
          else sim.run(cfg.workers, t, order(prog.units).map(comp.unitMembers))
        else sim.run(cfg.workers, 0, order(prog.units).map(comp.unitAll))
      val st = Stats(end, comp.guestSawProgress, comp.unstable)
      if sim.aborted then (Left(Outcome.Aborted), st)
      else if sim.deadlock then (Left(Outcome.Deadlock), st)
      else (Right(comp.result), st)
