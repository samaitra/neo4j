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
package org.neo4j.cypher.internal.compiler.v2_1.planner.logical.steps

import org.neo4j.graphdb.Direction
import org.neo4j.cypher.internal.commons.CypherFunSuite
import org.neo4j.cypher.internal.compiler.v2_1.planner._
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.plans._
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.PlanTable
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.steps.QueryPlanProducer._

class OptionalTest extends CypherFunSuite with LogicalPlanningTestSupport {

  test("should introduce apply for unsolved exclusive optional match") {
    // OPTIONAL MATCH (a)-[r]->(b)

    val patternRel = PatternRelationship("r", ("a", "b"), Direction.OUTGOING, Seq.empty, SimplePatternLength)
    val optionalMatch = QueryGraph(
      patternNodes = Set("a", "b"),
      patternRelationships = Set(patternRel)
    ).addCoveredIdsAsProjections()
    val qg = QueryGraph().withAddedOptionalMatch(optionalMatch)

    val factory = newMockedMetricsFactory
    when(factory.newCardinalityEstimator(any(), any(), any())).thenReturn((plan: LogicalPlan) => plan match {
      case _: SingleRow => 1.0
      case _            => 1000.0
    })

    val fakePlan = newMockedQueryPlan(Set(IdName("a"), IdName("b")))

    implicit val context = newMockedLogicalPlanContext(
      planContext = newMockedPlanContext,
      queryGraph = qg,
      strategy = newMockedStrategy(fakePlan),
      metrics = factory.newMetrics(newMockedStatistics, newMockedSemanticTable)
    )

    val planTable = PlanTable(Map())

    optional(planTable).plans should equal(Seq(planOptional(fakePlan)))
  }
}
