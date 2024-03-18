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

import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.spark.internal.io.FileCommitProtocol
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.catalog.{BucketSpec, CatalogTable, CatalogTablePartition}
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.catalog.ExternalCatalogUtils._
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.util.CaseInsensitiveMap
import org.apache.spark.sql.errors.{QueryCompilationErrors, QueryExecutionErrors}
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.command._
import org.apache.spark.sql.internal.SQLConf.PartitionOverwriteMode
import org.apache.spark.sql.util.SchemaUtils

/**
 * 写入数据到一个 [[HadoopFsRelation]] 的命令。支持覆盖和追加两种模式。 同时也支持写入到动态分区。
 *
 * @param staticPartitions 写入的部分分区规范。
 *                         这定义了分区覆盖的范围：当规范为空时，所有分区都被覆盖。
 *                         当它涵盖分区键的前缀时，只有匹配前缀的分区才会被覆盖
 * @param ifPartitionNotExists 如果为真，则仅在分区不存在时进行写入。仅适用于静态分区。
 */
case class InsertIntoHadoopFsRelationCommand(
    outputPath: Path,                    // 输出目录
    staticPartitions: TablePartitionSpec, // 表的静态分区列
    ifPartitionNotExists: Boolean,
    partitionColumns: Seq[Attribute],   // 表的分区列
    bucketSpec: Option[BucketSpec],
    fileFormat: FileFormat,
    options: Map[String, String],
    query: LogicalPlan,
    mode: SaveMode,  // 写的模式
    catalogTable: Option[CatalogTable],  //  写的目标表
    fileIndex: Option[FileIndex],        // 目标表文件信息
    outputColumnNames: Seq[String])      // 写出列名
  extends DataWritingCommand {

  private lazy val parameters = CaseInsensitiveMap(options)

  // 判断是否是动态分区覆盖写操作
  private[sql] lazy val dynamicPartitionOverwrite: Boolean = {
    val partitionOverwriteMode = parameters.get(DataSourceUtils.PARTITION_OVERWRITE_MODE)
      // scalastyle:off caselocale
      .map(mode => PartitionOverwriteMode.withName(mode.toUpperCase))
      // scalastyle:on caselocale
      .getOrElse(conf.partitionOverwriteMode)
    val enableDynamicOverwrite = partitionOverwriteMode == PartitionOverwriteMode.DYNAMIC
    // 这个配置只在我们用动态分区列覆盖一个分区数据集时才有意义。
    enableDynamicOverwrite && mode == SaveMode.Overwrite &&
      staticPartitions.size < partitionColumns.length
  }

  override def run(sparkSession: SparkSession, child: SparkPlan): Seq[Row] = {
    // Most formats don't do well with duplicate columns, so lets not allow that
    SchemaUtils.checkColumnNameDuplication(
      outputColumnNames,
      s"when inserting into $outputPath",
      sparkSession.sessionState.conf.caseSensitiveAnalysis)

    val hadoopConf = sparkSession.sessionState.newHadoopConfWithOptions(options)
    val fs = outputPath.getFileSystem(hadoopConf)
    val qualifiedOutputPath = outputPath.makeQualified(fs.getUri, fs.getWorkingDirectory)

    val partitionsTrackedByCatalog = sparkSession.sessionState.conf.manageFilesourcePartitions &&
      catalogTable.isDefined &&
      catalogTable.get.partitionColumnNames.nonEmpty &&
      catalogTable.get.tracksPartitionsInCatalog

    var initialMatchingPartitions: Seq[TablePartitionSpec] = Nil
    var customPartitionLocations: Map[TablePartitionSpec, String] = Map.empty
    var matchingPartitions: Seq[CatalogTablePartition] = Seq.empty

    // 当分区由目录跟踪时，计算所有可能与插入作业相关的自定义分区位置。
    if (partitionsTrackedByCatalog) {
      // 找到静态分区匹配位置
      matchingPartitions = sparkSession.sessionState.catalog.listPartitions(
        catalogTable.get.identifier, Some(staticPartitions))

        initialMatchingPartitions = matchingPartitions.map(_.spec)
        customPartitionLocations = getCustomPartitionLocations(
          fs, catalogTable.get, qualifiedOutputPath, matchingPartitions)
    }

    val jobId = java.util.UUID.randomUUID().toString

    // 初始化 commiter
    // spark.sql.sources.commitProtocolClass
    // org.apache.spark.sql.execution.datasources.SQLHadoopMapReduceCommitProtocol
    val committer = FileCommitProtocol.instantiate(
      sparkSession.sessionState.conf.fileCommitProtocolClass,
      jobId = jobId,
      outputPath = outputPath.toString,
      dynamicPartitionOverwrite = dynamicPartitionOverwrite)

    val doInsertion = if (mode == SaveMode.Append) {
      true
    } else {
      val pathExists = fs.exists(qualifiedOutputPath)
      (mode, pathExists) match {
        case (SaveMode.ErrorIfExists, true) =>
          throw QueryCompilationErrors.outputPathAlreadyExistsError(qualifiedOutputPath)
        case (SaveMode.Overwrite, true) =>
          if (ifPartitionNotExists && matchingPartitions.nonEmpty) {
            false
          } else if (dynamicPartitionOverwrite) {
            // For dynamic partition overwrite, do not delete partition directories ahead.
            true
          } else {
            deleteMatchingPartitions(fs, qualifiedOutputPath, customPartitionLocations, committer)
            true
          }
        case (SaveMode.Overwrite, _) | (SaveMode.ErrorIfExists, false) =>
          true
        case (SaveMode.Ignore, exists) =>
          !exists
        case (s, exists) =>
          throw QueryExecutionErrors.saveModeUnsupportedError(s, exists)
      }
    }

    if (doInsertion) {

      def refreshUpdatedPartitions(updatedPartitionPaths: Set[String]): Unit = {
        val updatedPartitions = updatedPartitionPaths.map(PartitioningUtils.parsePathFragment)
        if (partitionsTrackedByCatalog) {
          val newPartitions = updatedPartitions -- initialMatchingPartitions
          if (newPartitions.nonEmpty) {
            AlterTableAddPartitionCommand(
              catalogTable.get.identifier, newPartitions.toSeq.map(p => (p, None)),
              ifNotExists = true).run(sparkSession)
          }
          // For dynamic partition overwrite, we never remove partitions but only update existing
          // ones.
          if (mode == SaveMode.Overwrite && !dynamicPartitionOverwrite) {
            val deletedPartitions = initialMatchingPartitions.toSet -- updatedPartitions
            if (deletedPartitions.nonEmpty) {
              AlterTableDropPartitionCommand(
                catalogTable.get.identifier, deletedPartitions.toSeq,
                ifExists = true, purge = false,
                retainData = true /* already deleted */).run(sparkSession)
            }
          }
        }
      }

      // For dynamic partition overwrite, FileOutputCommitter's output path is staging path, files
      // will be renamed from staging path to final output path during commit job
      val committerOutputPath = if (dynamicPartitionOverwrite) {
        FileCommitProtocol.getStagingDir(outputPath.toString, jobId)
          .makeQualified(fs.getUri, fs.getWorkingDirectory)
      } else {
        qualifiedOutputPath
      }

      val updatedPartitionPaths =
        FileFormatWriter.write(
          sparkSession = sparkSession,
          plan = child,
          fileFormat = fileFormat,
          committer = committer,
          outputSpec = FileFormatWriter.OutputSpec(
            committerOutputPath.toString, customPartitionLocations, outputColumns),
          hadoopConf = hadoopConf,
          partitionColumns = partitionColumns,
          bucketSpec = bucketSpec,
          statsTrackers = Seq(basicWriteJobStatsTracker(hadoopConf)),
          options = options)


      // update metastore partition metadata
      if (updatedPartitionPaths.isEmpty && staticPartitions.nonEmpty
        && partitionColumns.length == staticPartitions.size) {
        // Avoid empty static partition can't loaded to datasource table.
        val staticPathFragment =
          PartitioningUtils.getPathFragment(staticPartitions, partitionColumns)
        refreshUpdatedPartitions(Set(staticPathFragment))
      } else {
        refreshUpdatedPartitions(updatedPartitionPaths)
      }

      // refresh cached files in FileIndex
      fileIndex.foreach(_.refresh())
      // refresh data cache if table is cached
      sparkSession.sharedState.cacheManager.recacheByPath(sparkSession, outputPath, fs)

      if (catalogTable.nonEmpty) {
        CommandUtils.updateTableStats(sparkSession, catalogTable.get)
      }

    } else {
      logInfo("Skipping insertion into a relation that already exists.")
    }

    Seq.empty[Row]
  }

  /**
   * 删除所有与指定静态前缀匹配的分区文件。
   * 基于给定给该类的自定义位置映射，也会清除具有自定义位置的分区。
   */
  private def deleteMatchingPartitions(
      fs: FileSystem,
      qualifiedOutputPath: Path,
      customPartitionLocations: Map[TablePartitionSpec, String],
      committer: FileCommitProtocol): Unit = {
    val staticPartitionPrefix = if (staticPartitions.nonEmpty) {
      "/" + partitionColumns.flatMap { p =>
        staticPartitions.get(p.name).map(getPartitionPathString(p.name, _))
      }.mkString("/")
    } else {
      ""
    }
    // first clear the path determined by the static partition keys (e.g. /table/foo=1)
    val staticPrefixPath = qualifiedOutputPath.suffix(staticPartitionPrefix)
    if (fs.exists(staticPrefixPath) && !committer.deleteWithJob(fs, staticPrefixPath, true)) {
      throw QueryExecutionErrors.cannotClearOutputDirectoryError(staticPrefixPath)
    }
    // now clear all custom partition locations (e.g. /custom/dir/where/foo=2/bar=4)
    for ((spec, customLoc) <- customPartitionLocations) {
      assert((staticPartitions.toSet -- spec).isEmpty,
        "Custom partition location did not match static partitioning keys")
      val path = new Path(customLoc)
      if (fs.exists(path) && !committer.deleteWithJob(fs, path, true)) {
        throw QueryExecutionErrors.cannotClearPartitionDirectoryError(path)
      }
    }
  }

  /**
   * Given a set of input partitions, returns those that have locations that differ from the
   * Hive default (e.g. /k1=v1/k2=v2). These partitions were manually assigned locations by
   * the user.
   *
   * @return a mapping from partition specs to their custom locations
   */
  private def getCustomPartitionLocations(
      fs: FileSystem,
      table: CatalogTable,
      qualifiedOutputPath: Path,
      partitions: Seq[CatalogTablePartition]): Map[TablePartitionSpec, String] = {
    partitions.flatMap { p =>
      val defaultLocation = qualifiedOutputPath.suffix(
        "/" + PartitioningUtils.getPathFragment(p.spec, table.partitionSchema)).toString
      val catalogLocation = new Path(p.location).makeQualified(
        fs.getUri, fs.getWorkingDirectory).toString
      if (catalogLocation != defaultLocation) {
        Some(p.spec -> catalogLocation)
      } else {
        None
      }
    }.toMap
  }

  override protected def withNewChildInternal(
    newChild: LogicalPlan): InsertIntoHadoopFsRelationCommand = copy(query = newChild)
}