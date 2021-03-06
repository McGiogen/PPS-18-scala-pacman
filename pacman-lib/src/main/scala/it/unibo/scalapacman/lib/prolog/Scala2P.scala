package it.unibo.scalapacman.lib.prolog

import alice.tuprolog.{Prolog, SolveInfo, Struct, Term, Theory}

object Scala2P {

  type PrologEngine = Term => Stream[Term]

  def extractTerm(t: Term, i: Integer): Term = t.asInstanceOf[Struct].getArg(i).getTerm

  implicit def convertibleToTerm(t: TermConvertible): Term = t.toTerm

  implicit def stringToTerm(s: String): Term = Term.createTerm(s)

  implicit def seqToTerm[T](s: Seq[T]): Term = s.mkString("[", ",", "]")

  def mkPrologEngine(clauses: String*): PrologEngine = {
    goal =>
      new Iterable[Term] {
        val engine = new Prolog
        engine.setTheory(new Theory(clauses mkString " "))

        override def iterator: Iterator[Term] = new Iterator[Term] {
          var solution: SolveInfo = engine.solve(goal)

          override def hasNext: Boolean = solution.isSuccess ||
            solution.hasOpenAlternatives

          override def next(): Term =
            try {
              solution.getSolution
            } finally {
              solution = engine.solveNext
            }
        }
      }.toStream
  }
}
