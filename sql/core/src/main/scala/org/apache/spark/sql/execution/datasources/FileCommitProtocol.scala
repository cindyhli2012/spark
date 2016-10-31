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

import java.util.{Date, UUID}

import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl

import org.apache.spark.SparkHadoopWriter
import org.apache.spark.internal.Logging
import org.apache.spark.mapred.SparkHadoopMapRedUtil
import org.apache.spark.sql.internal.SQLConf


object FileCommitProtocol {
  class TaskCommitMessage(obj: Any) extends Serializable

  object EmptyTaskCommitMessage extends TaskCommitMessage(Unit)
}


/**
 * An interface to define how a Spark job commits its outputs. Implementations must be serializable.
 *
 * The proper call sequence is:
 *
 * 1. Driver calls setupJob.
 * 2. As part of each task's execution, executor calls setupTask and then commitTask
 *    (or abortTask if task failed).
 * 3. When all necessary tasks completed successfully, the driver calls commitJob. If the job
 *    failed to execute (e.g. too many failed tasks), the job should call abortJob.
 */
abstract class FileCommitProtocol {
  import FileCommitProtocol._

  def setupJob(jobContext: JobContext): Unit

  def commitJob(jobContext: JobContext, taskCommits: Seq[TaskCommitMessage]): Unit

  def abortJob(jobContext: JobContext): Unit

  def setupTask(taskContext: TaskAttemptContext): Unit

  def addTaskTempFile(taskContext: TaskAttemptContext, dir: Option[String], ext: String): String

  def commitTask(taskContext: TaskAttemptContext): TaskCommitMessage

  def abortTask(taskContext: TaskAttemptContext): Unit
}


/**
 * An [[FileCommitProtocol]] implementation backed by an underlying Hadoop OutputCommitter
 * (from the newer mapreduce API, not the old mapred API).
 *
 * Unlike Hadoop's OutputCommitter, this implementation is serializable.
 */
class MapReduceFileCommitterProtocol(path: String, isAppend: Boolean)
  extends FileCommitProtocol with Logging {

  import FileCommitProtocol._

  /** OutputCommitter from Hadoop is not always serializable. */
  @transient private var committer: OutputCommitter = _

  /** UUID used to identify the job in file name. */
  private val uuid: String = UUID.randomUUID().toString

  private def setupCommitter(context: TaskAttemptContext): Unit = {
    committer = context.getOutputFormatClass.newInstance().getOutputCommitter(context)

    if (!isAppend) {
      // If we are appending data to an existing dir, we will only use the output committer
      // associated with the file output format since it is not safe to use a custom
      // committer for appending. For example, in S3, direct parquet output committer may
      // leave partial data in the destination dir when the appending job fails.
      // See SPARK-8578 for more details.
      val configuration = context.getConfiguration
      val clazz =
        configuration.getClass(SQLConf.OUTPUT_COMMITTER_CLASS.key, null, classOf[OutputCommitter])

      if (clazz != null) {
        logInfo(s"Using user defined output committer class ${clazz.getCanonicalName}")

        // Every output format based on org.apache.hadoop.mapreduce.lib.output.OutputFormat
        // has an associated output committer. To override this output committer,
        // we will first try to use the output committer set in SQLConf.OUTPUT_COMMITTER_CLASS.
        // If a data source needs to override the output committer, it needs to set the
        // output committer in prepareForWrite method.
        if (classOf[FileOutputCommitter].isAssignableFrom(clazz)) {
          // The specified output committer is a FileOutputCommitter.
          // So, we will use the FileOutputCommitter-specified constructor.
          val ctor = clazz.getDeclaredConstructor(classOf[Path], classOf[TaskAttemptContext])
          committer = ctor.newInstance(new Path(path), context)
        } else {
          // The specified output committer is just an OutputCommitter.
          // So, we will use the no-argument constructor.
          val ctor = clazz.getDeclaredConstructor()
          committer = ctor.newInstance()
        }
      }
    }
    logInfo(s"Using output committer class ${committer.getClass.getCanonicalName}")
  }

  override def addTaskTempFile(
      taskContext: TaskAttemptContext, dir: Option[String], ext: String): String = {
    // The file name looks like part-r-00000-2dd664f9-d2c4-4ffe-878f-c6c70c1fb0cb_00003.gz.parquet
    // Note that %05d does not truncate the split number, so if we have more than 100000 tasks,
    // the file name is fine and won't overflow.
    val split = taskContext.getTaskAttemptID.getTaskID.getId
    val filename = f"part-$split%05d-$uuid$ext"

    val stagingDir: String = committer match {
      // For FileOutputCommitter it has its own staging path called "work path".
      case f: FileOutputCommitter => Option(f.getWorkPath.toString).getOrElse(path)
      case _ => path
    }

    dir.map { d =>
      new Path(new Path(stagingDir, d), filename).toString
    }.getOrElse {
      new Path(stagingDir, filename).toString
    }
  }

  override def setupJob(jobContext: JobContext): Unit = {
    // Setup IDs
    val jobId = SparkHadoopWriter.createJobID(new Date, 0)
    val taskId = new TaskID(jobId, TaskType.MAP, 0)
    val taskAttemptId = new TaskAttemptID(taskId, 0)

    // Set up the configuration object
    jobContext.getConfiguration.set("mapred.job.id", jobId.toString)
    jobContext.getConfiguration.set("mapred.tip.id", taskAttemptId.getTaskID.toString)
    jobContext.getConfiguration.set("mapred.task.id", taskAttemptId.toString)
    jobContext.getConfiguration.setBoolean("mapred.task.is.map", true)
    jobContext.getConfiguration.setInt("mapred.task.partition", 0)

    val taskAttemptContext = new TaskAttemptContextImpl(jobContext.getConfiguration, taskAttemptId)
    setupCommitter(taskAttemptContext)

    committer.setupJob(jobContext)
  }

  override def commitJob(jobContext: JobContext, taskCommits: Seq[TaskCommitMessage]): Unit = {
    committer.commitJob(jobContext)
  }

  override def abortJob(jobContext: JobContext): Unit = {
    committer.abortJob(jobContext, JobStatus.State.FAILED)
  }

  override def setupTask(taskContext: TaskAttemptContext): Unit = {
    setupCommitter(taskContext)
    committer.setupTask(taskContext)
  }

  override def commitTask(taskContext: TaskAttemptContext): TaskCommitMessage = {
    val attemptId = taskContext.getTaskAttemptID
    SparkHadoopMapRedUtil.commitTask(
      committer, taskContext, attemptId.getJobID.getId, attemptId.getTaskID.getId)
    EmptyTaskCommitMessage
  }

  override def abortTask(taskContext: TaskAttemptContext): Unit = {
    committer.abortTask(taskContext)
  }
}
