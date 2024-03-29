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

package org.apache.spark.sql.execution.datasources

import org.apache.hadoop.fs._

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.types.StructType

/**
 * A collection of data files from a partitioned relation, along with the partition values in the
 * form of an [[InternalRow]].
 */
case class PartitionDirectory(values: InternalRow, files: Seq[FileStatus])

/**
 * An interface for objects capable of enumerating the root paths of a relation as well as the
 * partitions of a relation subject to some pruning expressions.
 */
trait FileIndex {

  /**
   * 返回目录将获取文件的根输入路径列表。
   * 可以有一个根路径，从中发现分区，也可以由每个路径指定单独的分区。
   */
  def rootPaths: Seq[Path]

  /**
   * Returns all valid files grouped into partitions when the data is partitioned. If the data is
   * unpartitioned, this will return a single partition with no partition values.
   *
   * @param partitionFilters The filters used to prune which partitions are returned. These filters
   *                         must only refer to partition columns and this method will only return
   *                         files where these predicates are guaranteed to evaluate to `true`.
   *                         Thus, these filters will not need to be evaluated again on the
   *                         returned data.
   * @param dataFilters Filters that can be applied on non-partitioned columns. The implementation
   *                    does not need to guarantee these filters are applied, i.e. the execution
   *                    engine will ensure these filters are still applied on the returned files.
   */
  def listFiles(
      partitionFilters: Seq[Expression], dataFilters: Seq[Expression]): Seq[PartitionDirectory]

  /**
   * Returns the list of files that will be read when scanning this relation. This call may be
   * very expensive for large tables.
   */
  def inputFiles: Array[String]

  /** Refresh any cached file listings */
  def refresh(): Unit

  /** Sum of table file sizes, in bytes */
  def sizeInBytes: Long

  /** Schema of the partitioning columns, or the empty schema if the table is not partitioned. */
  def partitionSchema: StructType

  /**
   * Returns an optional metadata operation time, in nanoseconds, for listing files.
   *
   * We do file listing in query optimization (in order to get the proper statistics) and we want
   * to account for file listing time in physical execution (as metrics). To do that, we save the
   * file listing time in some implementations and physical execution calls it in this method
   * to update the metrics.
   */
  def metadataOpsTimeNs: Option[Long] = None
}
