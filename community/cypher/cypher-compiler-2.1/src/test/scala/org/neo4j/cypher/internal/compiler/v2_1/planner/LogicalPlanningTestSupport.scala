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

import org.neo4j.cypher.internal.commons.{CypherTestSuite, CypherTestSupport}


import org.neo4j.graphdb.Direction
import org.neo4j.cypher.internal.compiler.v2_1._
import org.neo4j.cypher.internal.compiler.v2_1.spi.{GraphStatistics, PlanContext}
import org.neo4j.cypher.internal.compiler.v2_1.parser.{ParserMonitor, CypherParser}
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical._
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.plans._
import org.neo4j.cypher.internal.compiler.v2_1.ast.{PatternExpression, AstConstructionTestSupport, RelTypeName, Query}
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.Metrics._
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatest.mock.MockitoSugar

trait LogicalPlanningTestSupport
  extends CypherTestSupport
  with AstConstructionTestSupport {

  self: CypherTestSuite with MockitoSugar =>

  val kernelMonitors = new org.neo4j.kernel.monitoring.Monitors
  val monitors = new Monitors(kernelMonitors)
  val monitorTag = "compiler2.1"
  val parser = new CypherParser(monitors.newMonitor[ParserMonitor](monitorTag))
  val semanticChecker = new SemanticChecker(monitors.newMonitor[SemanticCheckMonitor](monitorTag))
  val astRewriter = new ASTRewriter(monitors.newMonitor[AstRewritingMonitor](monitorTag), shouldExtractParameters = false)
  val mockRel = newPatternRelationship("a", "b", "r")
  val tokenResolver = new SimpleTokenResolver()

  def newPatternRelationship(start: IdName, end: IdName, rel: IdName, dir: Direction = Direction.OUTGOING, types: Seq[RelTypeName] = Seq.empty, length: PatternLength = SimplePatternLength) = {
    PatternRelationship(rel, (start, end), dir, types, length)
  }

  class SpyableMetricsFactory extends MetricsFactory {
    def newSelectivityEstimator(statistics: GraphStatistics, semanticTable: SemanticTable) =
      SimpleMetricsFactory.newSelectivityEstimator(statistics, semanticTable)
    def newCardinalityEstimator(statistics: GraphStatistics, selectivity: SelectivityModel, semanticTable: SemanticTable) =
      SimpleMetricsFactory.newCardinalityEstimator(statistics, selectivity, semanticTable)
    def newCostModel(cardinality: CardinalityModel) =
      SimpleMetricsFactory.newCostModel(cardinality)
  }

  def newMetricsFactory = SimpleMetricsFactory

  def newSimpleMetrics(stats: GraphStatistics = newMockedGraphStatistics, semanticTable: SemanticTable) =
    newMetricsFactory.newMetrics(stats, semanticTable)

  def newMockedGraphStatistics = mock[GraphStatistics]

  def newMockedSemanticTable = mock[SemanticTable]

  def newMockedMetricsFactory = spy(new SpyableMetricsFactory)

  def newMockedStrategy(plan: QueryPlan) = {
    val strategy = mock[PlanningStrategy]
    doReturn(plan).when(strategy).plan(any(), any())
    strategy
  }

  def newMockedLogicalPlanContext(planContext: PlanContext,
                                  metrics: Metrics = self.mock[Metrics],
                                  semanticTable: SemanticTable = self.mock[SemanticTable],
                                  queryGraph: QueryGraph = self.mock[QueryGraph],
                                  subQueryLookupTable: Map[PatternExpression, QueryGraph] = Map.empty,
                                  strategy: PlanningStrategy = new GreedyPlanningStrategy()): LogicalPlanContext =

    LogicalPlanContext(planContext, metrics, semanticTable, queryGraph, subQueryLookupTable, strategy)

  implicit class RichLogicalPlan(plan: QueryPlan) {
    def asTableEntry = plan.coveredIds -> plan
  }

  def newMockedStatistics = mock[GraphStatistics]

  def newMockedPlanContext(implicit statistics: GraphStatistics = newMockedStatistics) = {
    val context = mock[PlanContext]
    doReturn(statistics).when(context).statistics
    context
  }

  def newMockedQueryPlanWithProjections(ids: String*)(implicit context: LogicalPlanContext) = {
    val projections = Projections(projections = ids.map((id) => id -> ident(id)).toMap)
    QueryPlan(
      newMockedLogicalPlan(ids: _*),
      QueryGraph
        .empty
        .addPatternNodes(ids.map(IdName).toSeq: _*)
        .withProjection(projections)
    )
  }

  def newMockedQueryPlan(idNames: Set[IdName]): QueryPlan = {
    val plan = newMockedLogicalPlan(idNames)
    val qg = QueryGraph.empty.addPatternNodes(idNames.toSeq: _*)
    QueryPlan( plan, qg )
  }

  def newMockedQueryPlan(ids: String*): QueryPlan = {
    newMockedQueryPlan( ids.map(IdName).toSet )
  }

  def newMockedLogicalPlan(ids: String*): LogicalPlan = FakePlan(ids.map(IdName).toSet)

  def newMockedLogicalPlan(ids: Set[IdName]): LogicalPlan = FakePlan(ids)

  def newMockedQueryPlanWithPatterns(ids: Set[IdName], patterns: Seq[PatternRelationship] = Seq.empty)(implicit context: LogicalPlanContext): QueryPlan = {
    val plan = newMockedLogicalPlan(ids)
    val qg = QueryGraph.empty.addPatternNodes(ids.toSeq: _*).addPatternRels(patterns)
    QueryPlan( plan, qg )
  }

  def newMockedLogicalPlanWithPatterns(ids: Set[IdName], patterns: Seq[PatternRelationship] = Seq.empty)(implicit context: LogicalPlanContext): LogicalPlan = {
    val plan = mock[LogicalPlan]
    doReturn(s"MockedLogicalPlan(ids = $ids})").when(plan).toString
    plan
  }

  def newPlanner(metricsFactory: MetricsFactory): Planner =
    new Planner(monitors, metricsFactory, monitors.newMonitor[PlanningMonitor]())

  def produceLogicalPlan(queryText: String)
                        (implicit planner: Planner, planContext: PlanContext) = {
    val parsedStatement = parser.parse(queryText)
    semanticChecker.check(queryText, parsedStatement)
    val (rewrittenStatement, _) = astRewriter.rewrite(queryText, parsedStatement)
    planner.rewriteStatement(rewrittenStatement) match {
      case ast: Query =>
        val semanticTable = semanticChecker.check(queryText, rewrittenStatement)
        tokenResolver.resolve(ast)(semanticTable, planContext)
        planner.produceLogicalPlan(ast, semanticTable)(planContext)
      case _ =>
        throw new IllegalArgumentException("produceLogicalPlan only supports ast.Query input")
    }
  }

  implicit def idName(name: String): IdName = IdName(name)
}

case class FakePlan(availableSymbols: Set[IdName]) extends LogicalPlan{
  def rhs = None
  def lhs = None
}
