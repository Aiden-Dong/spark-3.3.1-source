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

package org.apache.spark.sql.hive.execution

import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.{Date, Locale, Random}

import scala.util.control.NonFatal

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.hive.common.FileUtils
import org.apache.hadoop.hive.ql.exec.TaskRunner

import org.apache.spark.internal.io.FileCommitProtocol
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.catalog.BucketSpec
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.errors.QueryExecutionErrors
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.command.DataWritingCommand
import org.apache.spark.sql.execution.datasources.{BucketingUtils, FileFormatWriter}
import org.apache.spark.sql.hive.HiveExternalCatalog
import org.apache.spark.sql.hive.HiveShim.{ShimFileSinkDesc => FileSinkDesc}
import org.apache.spark.sql.hive.client.HiveVersion

// Base trait from which all hive insert statement physical execution extends.
private[hive] trait SaveAsHiveFile extends DataWritingCommand {

  var createdTempDir: Option[Path] = None

  protected def saveAsHiveFile(
      sparkSession: SparkSession,
      plan: SparkPlan,
      hadoopConf: Configuration,
      fileSinkConf: FileSinkDesc,
      outputLocation: String,
      customPartitionLocations: Map[TablePartitionSpec, String] = Map.empty,
      partitionAttributes: Seq[Attribute] = Nil,
      bucketSpec: Option[BucketSpec] = None): Set[String] = {

    // 设置压缩
    val isCompressed =
      fileSinkConf.getTableInfo.getOutputFileFormatClassName.toLowerCase(Locale.ROOT) match {
        case formatName if formatName.endsWith("orcoutputformat") =>
          // For ORC,"mapreduce.output.fileoutputformat.compress",
          // "mapreduce.output.fileoutputformat.compress.codec", and
          // "mapreduce.output.fileoutputformat.compress.type"
          // have no impact because it uses table properties to store compression information.
          false
        case _ => hadoopConf.get("hive.exec.compress.output", "false").toBoolean
    }

    if (isCompressed) {
      hadoopConf.set("mapreduce.output.fileoutputformat.compress", "true")
      fileSinkConf.setCompressed(true)
      fileSinkConf.setCompressCodec(hadoopConf
        .get("mapreduce.output.fileoutputformat.compress.codec"))
      fileSinkConf.setCompressType(hadoopConf
        .get("mapreduce.output.fileoutputformat.compress.type"))
    } else {
      // Set compression by priority
      HiveOptions.getHiveWriteCompression(fileSinkConf.getTableInfo, sparkSession.sessionState.conf)
        .foreach { case (compression, codec) => hadoopConf.set(compression, codec) }
    }

    val committer = FileCommitProtocol.instantiate(
      sparkSession.sessionState.conf.fileCommitProtocolClass,
      jobId = java.util.UUID.randomUUID().toString,
      outputPath = outputLocation)

    val options = bucketSpec
      .map(_ => Map(BucketingUtils.optionForHiveCompatibleBucketWrite -> "true"))
      .getOrElse(Map.empty)

    FileFormatWriter.write(
      sparkSession = sparkSession,
      plan = plan,
      fileFormat = new HiveFileFormat(fileSinkConf),
      committer = committer,
      outputSpec =  FileFormatWriter.OutputSpec(outputLocation, customPartitionLocations, outputColumns),
      hadoopConf = hadoopConf,
      partitionColumns = partitionAttributes,
      bucketSpec = bucketSpec,
      statsTrackers = Seq(basicWriteJobStatsTracker(hadoopConf)),
      options = options)
  }

  protected def getExternalTmpPath(
      sparkSession: SparkSession,
      hadoopConf: Configuration,
      path: Path): Path = {
    import org.apache.spark.sql.hive.client.hive._

    // 在 Hive 1.1 之前，插入表时，Hive 将在一个通用的临时目录下创建临时目录。写入完成后，Hive 将简单地清空表目录并将临时目录移动到其中。
    // 在 Hive 1.1 之后，Hive 将在表目录下创建临时目录，当将临时目录移动到表目录时，Hive 仍然会清空表目录，但会排除那里的临时目录。
    // 我们必须遵循这里的 Hive 行为，以避免麻烦。例如，如果我们在 Hive 1.1 之前为表目录下的 Hive 创建临时目录，则当 Hive 尝试清空表目录时，临时目录将被移除。
    val hiveVersionsUsingOldExternalTempPath: Set[HiveVersion] = Set(v12, v13, v14, v1_0)
    val hiveVersionsUsingNewExternalTempPath: Set[HiveVersion] =
      Set(v1_1, v1_2, v2_0, v2_1, v2_2, v2_3, v3_0, v3_1)

    // Ensure all the supported versions are considered here.
    assert(hiveVersionsUsingNewExternalTempPath ++ hiveVersionsUsingOldExternalTempPath ==
      allSupportedHiveVersions)

    val externalCatalog = sparkSession.sharedState.externalCatalog
    val hiveVersion = externalCatalog.unwrapped.asInstanceOf[HiveExternalCatalog].client.version
    val stagingDir = hadoopConf.get("hive.exec.stagingdir", ".hive-staging")
    val scratchDir = hadoopConf.get("hive.exec.scratchdir", "/tmp/hive")

    if (hiveVersionsUsingOldExternalTempPath.contains(hiveVersion)) {
      oldVersionExternalTempPath(path, hadoopConf, scratchDir)
    } else if (hiveVersionsUsingNewExternalTempPath.contains(hiveVersion)) {
      newVersionExternalTempPath(path, hadoopConf, stagingDir)
    } else {
      throw new IllegalStateException("Unsupported hive version: " + hiveVersion.fullVersion)
    }
  }

  protected def deleteExternalTmpPath(hadoopConf: Configuration) : Unit = {
    // Attempt to delete the staging directory and the inclusive files. If failed, the files are
    // expected to be dropped at the normal termination of VM since deleteOnExit is used.
    try {
      createdTempDir.foreach { path =>
        val fs = path.getFileSystem(hadoopConf)
        if (fs.delete(path, true)) {
          // If we successfully delete the staging directory, remove it from FileSystem's cache.
          fs.cancelDeleteOnExit(path)
        }
      }
    } catch {
      case NonFatal(e) =>
        val stagingDir = hadoopConf.get("hive.exec.stagingdir", ".hive-staging")
        logWarning(s"Unable to delete staging directory: $stagingDir.\n" + e)
    }
  }

  // Mostly copied from Context.java#getExternalTmpPath of Hive 0.13
  private def oldVersionExternalTempPath(
      path: Path,
      hadoopConf: Configuration,
      scratchDir: String): Path = {
    val extURI: URI = path.toUri
    val scratchPath = new Path(scratchDir, executionId)
    var dirPath = new Path(
      extURI.getScheme,
      extURI.getAuthority,
      scratchPath.toUri.getPath + "-" + TaskRunner.getTaskRunnerID())

    try {
      val fs: FileSystem = dirPath.getFileSystem(hadoopConf)
      dirPath = new Path(fs.makeQualified(dirPath).toString())

      if (!FileUtils.mkdir(fs, dirPath, true, hadoopConf)) {
        throw new IllegalStateException("Cannot create staging directory: " + dirPath.toString)
      }
      createdTempDir = Some(dirPath)
      fs.deleteOnExit(dirPath)
    } catch {
      case e: IOException =>
        throw QueryExecutionErrors.cannotCreateStagingDirError(dirPath.toString, e)
    }
    dirPath
  }

  // Mostly copied from Context.java#getExternalTmpPath of Hive 1.2
  private def newVersionExternalTempPath(
      path: Path,
      hadoopConf: Configuration,
      stagingDir: String): Path = {
    val extURI: URI = path.toUri
    if (extURI.getScheme == "viewfs") {
      getExtTmpPathRelTo(path, hadoopConf, stagingDir)
    } else {
      new Path(getExternalScratchDir(extURI, hadoopConf, stagingDir), "-ext-10000")
    }
  }

  private def getExtTmpPathRelTo(
      path: Path,
      hadoopConf: Configuration,
      stagingDir: String): Path = {
    new Path(getStagingDir(path, hadoopConf, stagingDir), "-ext-10000") // Hive uses 10000
  }

  private def getExternalScratchDir(
      extURI: URI,
      hadoopConf: Configuration,
      stagingDir: String): Path = {
    getStagingDir(
      new Path(extURI.getScheme, extURI.getAuthority, extURI.getPath),
      hadoopConf,
      stagingDir)
  }

  private[hive] def getStagingDir(
      inputPath: Path,
      hadoopConf: Configuration,
      stagingDir: String): Path = {
    val inputPathName: String = inputPath.toString
    val fs: FileSystem = inputPath.getFileSystem(hadoopConf)
    var stagingPathName: String =
      if (inputPathName.indexOf(stagingDir) == -1) {
        new Path(inputPathName, stagingDir).toString
      } else {
        inputPathName.substring(0, inputPathName.indexOf(stagingDir) + stagingDir.length)
      }

    // SPARK-20594: This is a walk-around fix to resolve a Hive bug. Hive requires that the
    // staging directory needs to avoid being deleted when users set hive.exec.stagingdir
    // under the table directory.
    if (isSubDir(new Path(stagingPathName), inputPath, fs) &&
      !stagingPathName.stripPrefix(inputPathName).stripPrefix("/").startsWith(".")) {
      logDebug(s"The staging dir '$stagingPathName' should be a child directory starts " +
        "with '.' to avoid being deleted if we set hive.exec.stagingdir under the table " +
        "directory.")
      stagingPathName = new Path(inputPathName, ".hive-staging").toString
    }

    val dir: Path =
      fs.makeQualified(
        new Path(stagingPathName + "_" + executionId + "-" + TaskRunner.getTaskRunnerID))
    logDebug("Created staging dir = " + dir + " for path = " + inputPath)
    try {
      if (!FileUtils.mkdir(fs, dir, true, hadoopConf)) {
        throw new IllegalStateException("Cannot create staging directory  '" + dir.toString + "'")
      }
      createdTempDir = Some(dir)
      fs.deleteOnExit(dir)
    } catch {
      case e: IOException =>
        throw QueryExecutionErrors.cannotCreateStagingDirError(
          s"'${dir.toString}': ${e.getMessage}", e)
    }
    dir
  }

  // HIVE-14259 removed FileUtils.isSubDir(). Adapted it from Hive 1.2's FileUtils.isSubDir().
  private def isSubDir(p1: Path, p2: Path, fs: FileSystem): Boolean = {
    val path1 = fs.makeQualified(p1).toString + Path.SEPARATOR
    val path2 = fs.makeQualified(p2).toString + Path.SEPARATOR
    path1.startsWith(path2)
  }

  private def executionId: String = {
    val rand: Random = new Random
    val format = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US)
    "hive_" + format.format(new Date) + "_" + Math.abs(rand.nextLong)
  }
}

