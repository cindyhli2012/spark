/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql

import org.apache.spark.sql.test.TestSQLContext.implicits._


class DataFrameNaFunctionsSuite extends QueryTest {

  def createDF(): DataFrame = {
    Seq[(String, java.lang.Integer, java.lang.Integer)](
      ("Bob", 16, 176),
      ("Alice", null, 164),
      ("David", 60, null),
      ("Amy", null, null),
      (null, null, null)).toDF("name", "age", "height")
  }

  test("drop") {
    val input = createDF()
    val rows = input.collect()

    checkAnswer(
      input.na.drop("name"),
      rows(0) :: rows(1) :: rows(2) :: rows(3) :: Nil)

    checkAnswer(
      input.na.drop("age"),
      rows(0) :: rows(2) :: Nil)

    checkAnswer(
      input.na.drop("age", "height"),
      rows(0) :: Nil)

    checkAnswer(
      input.na.drop(),
      rows(0))

    // dropna on an a dataframe with no column should return an empty data frame.
    val empty = input.sqlContext.emptyDataFrame.select()
    assert(empty.na.drop().count() === 0L)
  }

  test("drop with threshold") {
    val input = createDF()
    val rows = input.collect()

    checkAnswer(
      input.na.drop(2, Seq("age", "height")),
      rows(0) :: Nil)

    checkAnswer(
      input.na.drop(3, Seq("name", "age", "height")),
      rows(0))
  }
}
