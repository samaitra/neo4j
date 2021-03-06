/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_1.planner

import org.neo4j.cypher.InternalException
import org.neo4j.cypher.internal.compiler.v2_1.ast._
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.plans._
import org.neo4j.cypher.internal.compiler.v2_1.helpers.UnNamedNameGenerator.isNamed

trait QueryGraph {
  def patternRelationships: Set[PatternRelationship]
  def patternNodes: Set[IdName]
  def argumentIds: Set[IdName]
  def selections: Selections
  def optionalMatches: Seq[QueryGraph]
  def tail: Option[QueryGraph]

  def projection: Projections

  def addPatternNodes(nodes: IdName*): QueryGraph
  def addPatternRel(rel: PatternRelationship): QueryGraph
  def addPatternRels(rels: Seq[PatternRelationship]): QueryGraph
  def addArgumentId(newIds: Seq[IdName]): QueryGraph
  def addSelections(selections: Selections): QueryGraph
  def addPredicates(predicates: Expression*): QueryGraph
  def addCoveredIdsAsProjections(): QueryGraph

  def withoutArguments(): QueryGraph

  def withAddedOptionalMatch(optionalMatch: QueryGraph): QueryGraph
  def withTail(newTail: QueryGraph): QueryGraph
  def withSelections(selections: Selections): QueryGraph
  def withProjection(projection: Projections): QueryGraph

  def knownLabelsOnNode(node: IdName): Seq[LabelName] =
    selections
      .labelPredicates.getOrElse(node, Seq.empty)
      .flatMap(_.labels).toSeq

  def findRelationshipsEndingOn(id: IdName): Set[PatternRelationship] =
    patternRelationships.filter { r => r.left == id || r.right == id }


  def coveredIds: Set[IdName] = {
    val patternIds = QueryGraph.coveredIdsForPatterns(patternNodes, patternRelationships)
    val optionalMatchIds = optionalMatches.flatMap(_.coveredIds)
    patternIds ++ argumentIds ++ optionalMatchIds
  }

  def ++(other: QueryGraph): QueryGraph =
    QueryGraph(
      projection = projection ++ other.projection,
      selections = selections ++ other.selections,
      patternNodes = patternNodes ++ other.patternNodes,
      patternRelationships = patternRelationships ++ other.patternRelationships,
      optionalMatches = optionalMatches ++ other.optionalMatches,
      argumentIds = argumentIds ++ other.argumentIds,
      tail = either(tail, other.tail)
    )

  private def either[T](a: Option[T], b: Option[T]): Option[T] = (a, b) match {
    case (Some(_), Some(_)) => throw new InternalException("Can't join two query graphs with different SKIP")
    case (s@Some(_), None) => s
    case (None, s) => s
  }

}

object QueryGraph {
  def apply(patternRelationships: Set[PatternRelationship] = Set.empty,
            patternNodes: Set[IdName] = Set.empty,
            argumentIds: Set[IdName] = Set.empty,
            selections: Selections = Selections(),
            projection: Projections = Projections(),
            optionalMatches: Seq[QueryGraph] = Seq.empty,
            tail: Option[QueryGraph] = None): QueryGraph =
    QueryGraphImpl(patternRelationships, patternNodes, argumentIds, selections, projection, optionalMatches, tail)

  val empty = QueryGraph()

  def coveredIdsForPatterns(patternNodeIds: Set[IdName], patternRels: Set[PatternRelationship]) = {
    val patternRelIds = patternRels.flatMap(_.coveredIds)
    patternNodeIds ++ patternRelIds
  }
}


// An abstract representation of the query graph being solved at the current step
case class QueryGraphImpl(patternRelationships: Set[PatternRelationship] = Set.empty,
                          patternNodes: Set[IdName] = Set.empty,
                          argumentIds: Set[IdName] = Set.empty,
                          selections: Selections = Selections(),
                          projection: Projections,
                          optionalMatches: Seq[QueryGraph] = Seq.empty,
                          tail: Option[QueryGraph] = None) extends QueryGraph with Visitable[QueryGraph] {


  def accept[R](visitor: Visitor[QueryGraph, R]): R = visitor.visit(this)

  def withAddedOptionalMatch(optionalMatch: QueryGraph): QueryGraph = {
    val argumentIds = coveredIds intersect optionalMatch.coveredIds
    copy(optionalMatches = optionalMatches :+ optionalMatch.addArgumentId(argumentIds.toSeq)).
      addCoveredIdsAsProjections()
  }

  def addPatternNodes(nodes: IdName*): QueryGraph = copy(patternNodes = patternNodes ++ nodes)

  def addPatternRel(rel: PatternRelationship): QueryGraph =
    copy(
      patternNodes = patternNodes + rel.nodes._1 + rel.nodes._2,
      patternRelationships = patternRelationships + rel
    )

  def addPatternRels(rels: Seq[PatternRelationship]) =
    rels.foldLeft[QueryGraph](this)((qg, rel) => qg.addPatternRel(rel))

  def addArgumentId(newIds: Seq[IdName]): QueryGraph = copy(argumentIds = argumentIds ++ newIds)

  def withoutArguments(): QueryGraph = copy(argumentIds = Set.empty)

  def withTail(newTail: QueryGraph) = tail match {
    case None    => copy(tail = Some(newTail))
    case Some(_) => throw new InternalException("Attempt to set a second tail on a query graph")
  }

  def withSelections(selections: Selections): QueryGraph = copy(selections = selections)

  def withProjection(projection: Projections): QueryGraph = copy(projection = projection)

  def addSelections(selections: Selections): QueryGraph =
    copy(selections = Selections(selections.predicates ++ this.selections.predicates))

  def addPredicates(predicates: Expression*): QueryGraph = {
    val newSelections = Selections(predicates.flatMap(SelectionPredicates.extractPredicates).toSet)
    copy(selections = selections ++ newSelections)
  }

  def addCoveredIdsAsProjections(): QueryGraph = {
    val coveredIdProjections = coveredIds.map(x => x.name -> Identifier(x.name)(null)).toMap
    copy(projection = projection.addProjections(coveredIdProjections))
  }
}

object SelectionPredicates {
  def fromWhere(where: Where): Set[Predicate] = extractPredicates(where.expression).map(filterUnnamed)

  def idNames(predicate: Expression): Set[IdName] = predicate.treeFold(Set.empty[IdName]) {
    case Identifier(name) => (acc: Set[IdName], _) => acc + IdName(name)
  }

  def extractPredicates(predicate: Expression): Set[Predicate] = predicate.treeFold(Set.empty[Predicate]) {
    // n:Label
    case predicate@HasLabels(identifier@Identifier(name), labels) =>
      (acc, _) => acc ++ labels.map {
        label: LabelName =>
          Predicate(Set(IdName(name)), predicate.copy(labels = Seq(label))(predicate.position))
      }
    // and
    case _: Ands =>
      (acc, children) => children(acc)
    // iterable expression should not depend on the identifier they introduce
    case predicate: IterablePredicateExpression =>
      val innerDeps = predicate.innerPredicate.map(idNames).getOrElse(Set.empty) - IdName(predicate.identifier.name)
      (acc, _) => acc + Predicate(idNames(predicate.expression) ++ innerDeps, predicate)
    // generic expression
    case predicate: Expression =>
      (acc, _) => acc + Predicate(idNames(predicate), predicate)
  }.map(filterUnnamed).toSet

  private def filterUnnamed(predicate: Predicate): Predicate = predicate match {
    case Predicate(deps, e: PatternExpression) =>
      Predicate(deps.filter(x => isNamed(x.name)), e)
    case Predicate(deps, e@Not(_: PatternExpression)) =>
      Predicate(deps.filter(x => isNamed(x.name)), e)
    case Predicate(deps, ors@Ors(exprs)) =>
      val newDeps = exprs.foldLeft(Set.empty[IdName]) { (acc, exp) =>
        exp match {
          case exp: PatternExpression        => acc ++ SelectionPredicates.idNames(exp).filter(x => isNamed(x.name))
          case exp@Not(_: PatternExpression) => acc ++ SelectionPredicates.idNames(exp).filter(x => isNamed(x.name))
          case exp                           => acc ++ SelectionPredicates.idNames(exp)
        }
      }
      Predicate(newDeps, ors)
    case p => p
  }
}
