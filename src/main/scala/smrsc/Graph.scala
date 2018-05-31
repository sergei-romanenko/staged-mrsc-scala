package smrsc

import smrsc.Util._

//
// Graphs of configurations
//

// A `Graph[C]` is supposed to represent a residual program.
// Technically, a `Graph[C]` is a tree, with `back` nodes being
// references to parent nodes.
//
// A graph's nodes contain configurations. Here we abstract away
// from the concrete structure of configurations.
// In this model the arrows in the graph carry no information,
// because this information can be kept in nodes.
// (Hence, this information is supposed to be encoded inside
// "configurations".)
//
// To simplify the machinery, back-nodes in this model of
// supercompilation do not contain explicit references
// to parent nodes. Hence, `Back(c)` means that `c` is foldable
// to a parent configuration (perhaps, to several ones).
//
// * Back-nodes are produced by folding a configuration to another
//  configuration in the history.
// * Forth-nodes are produced by
//    + decomposing a configuration into a number of other configurations
//      (e.g. by driving or taking apart a let-expression), or
//    + by rewriting a configuration by another one (e.g. by generalization,
//      introducing a let-expression or applying a lemma during
//      two-level supercompilation).

// Graph

sealed trait Graph[C]

case class Back[C](c: C) extends Graph[C]

case class Forth[C](c: C, gs: List[Graph[C]]) extends Graph[C]

//
// Lazy graphs of configuration
//

// A `LazyGraph a` represents a finite set of graphs
// of configurations (whose type is `Graph a`).
//
// "Lazy" graphs of configurations will be produced
// by the "lazy" (staged) version of multi-result
// supercompilation.

// LazyGraph

sealed trait LazyGraph[+C]

case object Empty extends LazyGraph[Nothing]

case class Stop[C](c: C) extends LazyGraph[C]

case class Build[C](c: C, lss: List[List[LazyGraph[C]]]) extends LazyGraph[C]

object Graph {

  // The semantics of a `LazyGraph a` is formally defined by
  // the interpreter `unroll` that generates a list of `Graph a` from
  // the `LazyGraph a` by executing commands recorded in the `LazyGraph a`.

  def unroll[C](l: LazyGraph[C]): List[Graph[C]] = l match {
    case Empty => Nil
    case Stop(c) => List(Back(c))
    case Build(c, lss) =>
      //lss.flatMap(ls => cartesian(ls.map(unroll))).map(Forth(c, _))
      lss.flatMap(ls => cartesian(ls.map(unroll)))
        .map(Forth(c, _))
  }

  // Usually, we are not interested in the whole bag `unroll(l)`.
  // The goal is to find "the best" or "most interesting" graphs.
  // Hence, there should be developed some techniques of extracting
  // useful information from a `LazyGraph[C]` without evaluating
  // `unroll(l)` directly.

  // This can be formulated in the following form.
  // Suppose that a function `select` filters bags of graphs,
  // removing "bad" graphs, so that
  //     select (unroll l)
  // generates the bag of "good" graphs.
  // Let us find a function `extract` such that
  //     extract(l) = select(unroll(l))
  // In many cases, `extract` may be more efficient (by several orders
  // of magnitude) than the composition `select . unroll`.
  // Sometimes, `extract` can be formulated in terms of "cleaners" of
  // lazy graphs. Let `clean` be a function that transforms lazy graphs,
  // such that
  //     unroll(clean(l)) ⊆ unroll(l)
  // Then `extract` can be constructed in the following way:
  //     extract(l) = unroll(clean(l))
  // Theoretically speaking, `clean` is the result of "promoting" `select`:
  //     (select compose unroll)(l) = (unroll compose clean)(l)
  // The nice property of cleaners is that they are composable:
  // given `clean1` and `clean2`, `clean2 compose clean1` is also a cleaner.

  //
  // Some filters
  //

  // Removing graphs that contain "bad" configurations.
  // Configurations represent states of a computation process.
  // Some of these states may be "bad" with respect to the problem
  // that is to be solved by means of supercompilation.

  def bad_graph[C](bad: C => Boolean): Graph[C] => Boolean = {
    case Back(c) => bad(c)
    case Forth(c, gs) =>
      bad(c) || gs.exists(bad_graph(bad))
  }

  // This filter removes the graphs containing "bad" configurations.

  def fl_bad_conf[C](bad: C => Boolean)(gs: List[Graph[C]]): List[Graph[C]] =
    gs.filter(bad_graph(bad))

  //
  // Some cleaners
  //

  // `cl_empty` removes subtrees that represent empty sets of graphs.

  def cl_empty[C]: LazyGraph[C] => LazyGraph[C] = {
    case Empty => Empty
    case Stop(c) => Stop(c)
    case Build(c, lss) => cl_empty_build(c, cl_empty2(lss))
  }

  def cl_empty_build[C](c: C, lss: List[List[LazyGraph[C]]]): LazyGraph[C] =
    if (lss.isEmpty) Empty else Build(c, lss)

  def cl_empty2[C](lss: List[List[LazyGraph[C]]]): List[List[LazyGraph[C]]] =
    lss.map(cl_empty1).collect { case Some(ls1) => ls1 }

  def cl_empty1[C](ls: List[LazyGraph[C]]): Option[List[LazyGraph[C]]] = {
    val ls1 = ls.map(cl_empty)
    if (ls1.contains(Empty)) None else Some(ls1)
  }

  // Removing graphs that contain "bad" configurations.
  // The cleaner `cl_bad_conf` corresponds to the filter `fl_bad_conf`.
  // `cl_bad_conf` exploits the fact that "badness" is monotonic,
  // in the sense that a single "bad" configuration spoils the whole
  // graph.

  def cl_bad_conf[C](bad: C => Boolean): LazyGraph[C] => LazyGraph[C] = {
    case Empty => Empty
    case Stop(c) =>
      if (bad(c)) Empty else Stop(c)
    case Build(c, lss) =>
      if (bad(c)) Empty else Build(c, lss.map(_.map(cl_bad_conf(bad))))
  }

  //
  // The graph returned by `cl_bad_conf` may be cleaned by `cl_empty`.
  //

  def cl_empty_and_bad[C](bad: C => Boolean): LazyGraph[C] => LazyGraph[C] =
    cl_bad_conf(bad) andThen cl_empty

  //
  // Extracting a graph of minimal size (if any).
  //

  def graph_size[C]: Graph[C] => Int = {
    case Back(_) => 1
    case Forth(_, gs) => 1 + gs.map(graph_size).sum
  }

  // Now we define a cleaner `cl_min_size` that produces a lazy graph
  // representing the smallest graph (or the empty set of graphs).

  // We use a trick: ∞ is represented by -1 in (-1 , Empty).

  def select_min2[C](kx1: (Int, C), kx2: (Int, C)): (Int, C) =
    (kx1, kx2) match {
      case ((-1, _), (k2, x2)) => (k2, x2)
      case ((k1, x1), (-1, _)) => (k1, x1)
      case ((k1, x1), (k2, x2)) =>
        if (k1 <= k2) (k1, x1) else (k2, x2)
    }

  def select_min[A](m: A): List[(Int, A)] => (Int, A) = {
    case Nil => (-1, m)
    case kx :: kxs =>
      kxs.foldLeft(kx)(select_min2)
  }

  def cl_min_size[C]: LazyGraph[C] => (Int, LazyGraph[C]) = {
    case Empty =>
      (-1, Empty)
    case Stop(c) =>
      (1, Stop(c))
    case Build(c, lss) =>
      cl_min_size2(lss) match {
        case (-1, _) => (-1, Empty)
        case (k, ls) => (1 + k, Build(c, List(ls)))
      }
  }

  def cl_min_size2[C]: List[List[LazyGraph[C]]] => (Int, List[LazyGraph[C]]) = {
    case Nil =>
      (-1, Nil)
    case ls :: lss =>
      select_min2(cl_min_size_and(ls), cl_min_size2(lss))
  }

  def cl_min_size_and[C]: List[LazyGraph[C]] => (Int, List[LazyGraph[C]]) = {
    case Nil =>
      (1, Nil)
    case l :: ls =>
      (cl_min_size(l), cl_min_size_and(ls)) match {
        case ((-1, l1), (_, ls1)) => (-1, l1 :: ls1)
        case ((_, l1), (-1, ls1)) => (-1, l1 :: ls1)
        case ((i, l1), (j, ls1)) => (i + j, l1 :: ls1)
      }
  }

  //
  // `cl_min_size` is sound:
  //
  //  Let cl_min_size(l) = (k , l'). Then
  //     unroll(l') ⊆ unroll(l)
  //     k = graph_size (hd (unroll(l')))

}
