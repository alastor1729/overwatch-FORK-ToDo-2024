package com.databricks.labs.overwatch.pipeline

import com.databricks.labs.overwatch.env.{Database, Workspace}
import com.databricks.labs.overwatch.utils.{Config, FailedModuleException, Helpers, Module, ModuleStatusReport, NoNewDataException, SchemaTools, SparkSessionWrapper, UnhandledException}
import TransformFunctions._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{AnalysisException, Column, DataFrame, Row}

import java.io.{PrintWriter, StringWriter}

class Pipeline(_workspace: Workspace, _database: Database,
               _config: Config) extends PipelineTargets(_config) with SparkSessionWrapper {

  // TODO -- Validate Targets (unique table names, ModuleIDs and names, etc)
  private val logger: Logger = Logger.getLogger(this.getClass)
  protected final val workspace: Workspace = _workspace
  protected final val database: Database = _database
  protected final val config: Config = _config
  lazy protected final val postProcessor = new PostProcessor()
  private val sw = new StringWriter
  private var sourceDFparts: Int = 200

  envInit()

  // TODO -- Add Rules engine
  //  additional field under transforms rules: Option[Seq[Rule => Boolean]]
  case class EtlDefinition(
                            sourceDF: DataFrame,
                            transforms: Option[Seq[DataFrame => DataFrame]],
                            write: (DataFrame, Module) => Unit,
                            module: Module
                          ) {

    def process(): Unit = {
      println(s"Beginning: ${module.moduleName}")
      println("Validating Input Schemas")

      try {

        if (!sourceDF.isEmpty) { // if source DF is nonEmpty, continue with transforms
          @transient
          lazy val verifiedSourceDF = sourceDF
            .verifyMinimumSchema(Schema.get(module), enforceNonNullCols = true, config.debugFlag)

          try {
            sourceDFparts = verifiedSourceDF.rdd.partitions.length
          } catch {
            case _: AnalysisException =>
              println(s"Delaying source shuffle Partition Set since input is stream")
          }

          if (transforms.nonEmpty) {
            val transformedDF = transforms.get.foldLeft(verifiedSourceDF) {
              case (df, transform) =>
                df.transform(transform)
            }
            write(transformedDF, module)
          } else {
            write(verifiedSourceDF, module)
          }
        } else { // if Source DF is empty don't attempt transforms and send EMPTY to writer
//          write(spark.emptyDataFrame, module)
          val msg = s"ALERT: No New Data Retrieved for Module ${module.moduleID}-${module.moduleName}! Skipping"
          println(msg)
          throw new NoNewDataException(msg)
        }
      } catch {
        case e: FailedModuleException =>
          val errMessage = s"FAILED: ${module.moduleID}-${module.moduleName} Module"
          logger.log(Level.ERROR, errMessage, e)
        case e: NoNewDataException =>
          val errMessage = s"EMPTY: ${module.moduleID}-${module.moduleName} Module: SKIPPING"
          logger.log(Level.ERROR, errMessage, e)
          noNewDataHandler(module)
      }
    }
  }

  import spark.implicits._

  /**
    * Azure retrieves audit logs from EH which is to the millisecond whereas aws audit logs are delivered daily.
    * Accepting data with higher precision than delivery causes bad data
    */
  protected val auditLogsIncrementalCols: Seq[String] = if (config.cloudProvider == "azure") Seq("timestamp", "date") else Seq("date")

  protected def finalizeModule(report: ModuleStatusReport): Unit = {
    val pipelineReportTarget = PipelineTable(
      name = "pipeline_report",
      keys = Array("organization_id", "Overwatch_RunID"),
      config = config,
      incrementalColumns = Array("Pipeline_SnapTS")
    )
    database.write(Seq(report).toDF, pipelineReportTarget)
  }

  protected def initiatePostProcessing(): Unit = {
    //    postProcessor.analyze()
    postProcessor.optimize()
    Helpers.fastrm(Array(
      "/tmp/overwatch/bronze/clusterEventsBatches",
      "/tmp/overwatch/bronze/sparkEventLogPaths"
    ))

  }

  protected def restoreSparkConf(): Unit = {
    restoreSparkConf(config.initialSparkConf)
  }

  protected def restoreSparkConf(value: Map[String, String]): Unit = {
    PipelineFunctions.setSparkOverrides(spark, value, config.debugFlag)
  }

  private def getLastOptimized(moduleID: Int): Long = {
    val lastRunOptimizeTS = config.lastRunDetail.filter(_.moduleID == moduleID)
    if (!config.isFirstRun && lastRunOptimizeTS.nonEmpty) lastRunOptimizeTS.head.lastOptimizedTS
    else 0L
  }

  // TODO - make this timeframe configurable by module
  private def needsOptimize(moduleID: Int): Boolean = {
    // TODO -- Don't use 7 days -- use optimizeFrequency in PipelineTable
    val WEEK = 1000L * 60L * 60L * 24L * 7L // week of milliseconds
    val tsLessSevenD = System.currentTimeMillis() - WEEK.toLong
    if ((getLastOptimized(moduleID) < tsLessSevenD || config.isFirstRun) && !config.isLocalTesting) true
    else false
  }


  /**
    * Some modules should never progress through time while being empty
    * For example, cluster events may get ahead of audit logs and be empty but that doesn't mean there are
    * no cluster events for that time period
    *
    * @param moduleID
    * @return
    */
  private def getVerifiedUntilTS(moduleID: Int): Long = {
    val nonEmptyModules = Array(1005)
    if (nonEmptyModules.contains(moduleID)) {
      config.fromTime(moduleID).asUnixTimeMilli
    } else {
      config.untilTime(moduleID).asUnixTimeMilli
    }
  }

  private def failModule(module: Module, target: PipelineTable, msg: String): Unit = {

    val rollbackMsg = s"ROLLBACK: Attempting Roll back ${module.moduleName}."
    println(rollbackMsg)
    logger.log(Level.WARN, rollbackMsg)

    val rollbackStatus = try {
      database.rollbackTarget(target)
      "ROLLBACK SUCCESSFUL"
    } catch {
      case e: Throwable => {
        val rollbackFailedMsg = s"ROLLBACK FAILED: ${module.moduleName} -->\nMessage: ${e.getMessage}\nCause:" +
          s"${e.getCause}"
        println(rollbackFailedMsg, e)
        logger.log(Level.ERROR, rollbackFailedMsg, e)
        "ROLLBACK FAILED"
      }
    }

    val failedStatusReport = ModuleStatusReport(
      organization_id = config.organizationId,
      moduleID = module.moduleID,
      moduleName = module.moduleName,
      primordialDateString = config.primordialDateString,
      runStartTS = 0L,
      runEndTS = 0L,
      fromTS = config.fromTime(module.moduleID).asUnixTimeMilli,
      untilTS = getVerifiedUntilTS(module.moduleID),
      dataFrequency = target.dataFrequency.toString,
      status = s"FAILED --> $rollbackStatus: ERROR:\n$msg",
      recordsAppended = 0L,
      lastOptimizedTS = getLastOptimized(module.moduleID),
      vacuumRetentionHours = 0,
      inputConfig = config.inputConfig,
      parsedConfig = config.parsedConfig
    )
    finalizeModule(failedStatusReport)
    throw new FailedModuleException(msg)

  }

  private def noNewDataHandler(module: Module): Unit = {
    val startTime = System.currentTimeMillis()
    val emptyStatusReport = ModuleStatusReport(
      organization_id = config.organizationId,
      moduleID = module.moduleID,
      moduleName = module.moduleName,
      primordialDateString = config.primordialDateString,
      runStartTS = startTime,
      runEndTS = startTime,
      fromTS = config.fromTime(module.moduleID).asUnixTimeMilli,
      untilTS = getVerifiedUntilTS(module.moduleID),
      dataFrequency = "",
      status = "EMPTY",
      recordsAppended = 0L,
      lastOptimizedTS = getLastOptimized(module.moduleID),
      vacuumRetentionHours = 24 * 7,
      inputConfig = config.inputConfig,
      parsedConfig = config.parsedConfig
    )
    finalizeModule(emptyStatusReport)
  }

  private[overwatch] def append(target: PipelineTable)(df: DataFrame, module: Module): Unit = {
    val startTime = System.currentTimeMillis()

    try {

      val finalDF = PipelineFunctions.optimizeWritePartitions(df, sourceDFparts, target, spark, config, module)


      val startLogMsg = s"Beginning append to ${target.tableFullName}"
      logger.log(Level.INFO, startLogMsg)


      // Append the output
      if (!_database.write(finalDF, target)) throw new Exception("PIPELINE FAILURE")

      // Source files for spark event logs are extremely inefficient. Get count from bronze table instead
      // of attempting to re-read the very inefficient json.gz files.
      val dfCount = if (target.name == "spark_events_bronze") {
        target.asIncrementalDF(module, 2, "fileCreateDate", "fileCreateEpochMS").count()
      } else finalDF.count()

      val msg = s"SUCCESS! ${module.moduleName}: $dfCount records appended."
      println(msg)
      logger.log(Level.INFO, msg)

      var lastOptimizedTS: Long = getLastOptimized(module.moduleID)
      if (needsOptimize(module.moduleID)) {
        postProcessor.markOptimize(target)
        lastOptimizedTS = config.untilTime(module.moduleID).asUnixTimeMilli
      }

      restoreSparkConf()

      val endTime = System.currentTimeMillis()

      // Generate Success Report
      val moduleStatusReport = ModuleStatusReport(
        organization_id = config.organizationId,
        moduleID = module.moduleID,
        moduleName = module.moduleName,
        primordialDateString = config.primordialDateString,
        runStartTS = startTime,
        runEndTS = endTime,
        fromTS = config.fromTime(module.moduleID).asUnixTimeMilli,
        untilTS = config.untilTime(module.moduleID).asUnixTimeMilli,
        dataFrequency = target.dataFrequency.toString,
        status = "SUCCESS",
        recordsAppended = dfCount,
        lastOptimizedTS = lastOptimizedTS,
        vacuumRetentionHours = 24 * 7,
        inputConfig = config.inputConfig,
        parsedConfig = config.parsedConfig
      )
      finalizeModule(moduleStatusReport)
    } catch {
      case e: Throwable =>
        val msg = s"${module.moduleName} FAILED -->\nMessage: ${e.getMessage}\nCause:${e.getCause}"
        logger.log(Level.ERROR, msg, e)
        failModule(module, target, msg)
    }

  }

}

