package org.enso.interpreter.instrument.execution

import org.enso.interpreter.instrument.InterpreterContext
import org.enso.interpreter.instrument.job.{BackgroundJob, Job, UniqueJob}
import org.enso.text.Sha3_224VersionCalculator

import java.util
import java.util.{Collections, UUID}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ExecutorService
import java.util.logging.Level

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

/** This component schedules the execution of jobs. It keeps a queue of
  * pending jobs and activates job execution in FIFO order.
  *
  * @param interpreterContext suppliers of services that provide interpreter
  * specific functionality
  * @param executionState a state of the runtime
  * @param locking locking capability for runtime
  */
final class JobExecutionEngine(
  interpreterContext: InterpreterContext,
  executionState: ExecutionState,
  locking: Locking
) extends JobProcessor
    with JobControlPlane {

  private val runningJobsRef =
    new AtomicReference[Vector[RunningJob]](Vector.empty)

  private val backgroundJobsRef =
    new AtomicReference[Vector[RunningJob]](Vector.empty)

  private val context = interpreterContext.executionService.getContext

  private val jobParallelism = context.getJobParallelism

  private var isBackgroundJobsStarted = false

  private val delayedBackgroundJobsQueue =
    new util.ArrayList[BackgroundJob[_]](4096)

  val jobExecutor: ExecutorService =
    context.newFixedThreadPool(jobParallelism, "job-pool", false)

  val highPriorityJobExecutor: ExecutorService =
    context.newCachedThreadPool(
      "prioritized-job-pool",
      2,
      4,
      50,
      false
    )

  private val backgroundJobExecutor: ExecutorService =
    context.newCachedThreadPool("background-job-pool", 1, 4, 50, false)

  private val runtimeContext =
    RuntimeContext(
      executionService  = interpreterContext.executionService,
      contextManager    = interpreterContext.contextManager,
      endpoint          = interpreterContext.endpoint,
      truffleContext    = interpreterContext.truffleContext,
      jobProcessor      = this,
      jobControlPlane   = this,
      locking           = locking,
      state             = executionState,
      versionCalculator = Sha3_224VersionCalculator
    )

  /** @inheritdoc */
  override def runBackground[A](job: BackgroundJob[A]): Unit =
    synchronized {
      if (isBackgroundJobsStarted) {
        cancelDuplicateJobs(job, backgroundJobsRef)
        runInternal(job, backgroundJobExecutor, backgroundJobsRef)
      } else {
        job match {
          case job: UniqueJob[_] =>
            delayedBackgroundJobsQueue.removeIf {
              case that: UniqueJob[_] => that.equalsTo(job)
              case _                  => false
            }
          case _ =>
        }
        delayedBackgroundJobsQueue.add(job)
      }
    }

  /** @inheritdoc */
  override def run[A](job: Job[A]): Future[A] = {
    cancelDuplicateJobs(job, runningJobsRef)
    val executor =
      if (job.highPriority) highPriorityJobExecutor else jobExecutor
    runInternal(job, executor, runningJobsRef)
  }

  private def cancelDuplicateJobs[A](
    job: Job[A],
    runningJobsRef: AtomicReference[Vector[RunningJob]]
  ): Unit = {
    job match {
      case job: UniqueJob[_] =>
        val allJobs =
          runningJobsRef.updateAndGet(_.filterNot(_.future.isCancelled))
        allJobs.foreach { runningJob =>
          runningJob.job match {
            case jobRef: UniqueJob[_] if jobRef.equalsTo(job) =>
              runtimeContext.executionService.getLogger
                .log(Level.FINEST, s"Cancelling duplicate job [$jobRef].")
              runningJob.future.cancel(jobRef.mayInterruptIfRunning)
            case _ =>
          }
        }
      case _ =>
    }
  }

  private def runInternal[A](
    job: Job[A],
    executorService: ExecutorService,
    runningJobsRef: AtomicReference[Vector[RunningJob]]
  ): Future[A] = {
    val jobId   = UUID.randomUUID()
    val promise = Promise[A]()
    val logger  = runtimeContext.executionService.getLogger
    logger.log(Level.FINE, s"Submitting job: {0}...", job)
    val future = executorService.submit(() => {
      logger.log(Level.FINE, s"Executing job: {0}...", job)
      val before = System.currentTimeMillis()
      try {
        val result = job.run(runtimeContext)
        val took   = System.currentTimeMillis() - before
        logger.log(Level.FINE, s"Job {0} finished in {1} ms.", Array(job, took))
        promise.success(result)
      } catch {
        case NonFatal(ex) =>
          logger.log(Level.SEVERE, s"Error executing $job", ex)
          promise.failure(ex)
        case err: InterruptedException =>
          logger.log(Level.WARNING, s"$job got interrupted", err)
        case err: Throwable =>
          logger.log(Level.SEVERE, s"Error executing $job", err)
          throw err
      } finally {
        runningJobsRef.updateAndGet(_.filterNot(_.id == jobId))
      }
    })
    val runningJob = RunningJob(jobId, job, future)

    val queue = runningJobsRef.updateAndGet(_ :+ runningJob)
    logger.log(Level.FINE, "Number of pending jobs: {}", queue.size)

    promise.future
  }

  /** @inheritdoc */
  override def abortAllJobs(): Unit =
    abortAllExcept()

  /** @inheritdoc */
  override def abortAllExcept(ignoredJobs: Class[_ <: Job[_]]*): Unit = {
    val allJobs = runningJobsRef.updateAndGet(_.filterNot(_.future.isCancelled))
    val cancellableJobs = allJobs
      .filter { runningJob =>
        runningJob.job.isCancellable &&
        !ignoredJobs.contains(runningJob.job.getClass)
      }
    cancellableJobs.foreach { runningJob =>
      runningJob.future.cancel(runningJob.job.mayInterruptIfRunning)
    }
    runtimeContext.executionService.getContext.getThreadManager
      .interruptThreads()
  }

  /** @inheritdoc */
  override def abortJobs(
    contextId: UUID,
    toAbort: Class[_ <: Job[_]]*
  ): Unit = {
    val allJobs     = runningJobsRef.get()
    val contextJobs = allJobs.filter(_.job.contextIds.contains(contextId))
    contextJobs.foreach { runningJob =>
      if (
        runningJob.job.isCancellable && (toAbort.isEmpty || toAbort
          .contains(runningJob.getClass))
      ) {
        runningJob.future.cancel(runningJob.job.mayInterruptIfRunning)
      }
    }
    runtimeContext.executionService.getContext.getThreadManager
      .interruptThreads()
  }

  /** @inheritdoc */
  override def abortJobs(
    contextId: UUID,
    accept: java.util.function.Function[Job[_], java.lang.Boolean]
  ): Unit = {
    val allJobs     = runningJobsRef.get()
    val contextJobs = allJobs.filter(_.job.contextIds.contains(contextId))
    contextJobs.foreach { runningJob =>
      if (runningJob.job.isCancellable && accept.apply(runningJob.job)) {
        runningJob.future.cancel(runningJob.job.mayInterruptIfRunning)
      }
    }
    runtimeContext.executionService.getContext.getThreadManager
      .interruptThreads()
  }

  override def abortBackgroundJobs(toAbort: Class[_ <: Job[_]]*): Unit = {
    val allJobs =
      backgroundJobsRef.updateAndGet(_.filterNot(_.future.isCancelled))
    val cancellableJobs = allJobs
      .filter { runningJob =>
        runningJob.job.isCancellable &&
        toAbort.contains(runningJob.job.getClass)
      }
    cancellableJobs.foreach { runningJob =>
      runningJob.future.cancel(runningJob.job.mayInterruptIfRunning)
    }
  }

  /** @inheritdoc */
  override def startBackgroundJobs(): Boolean =
    synchronized {
      val result = !isBackgroundJobsStarted
      isBackgroundJobsStarted = true
      submitBackgroundJobsOrdered()
      result
    }

  /** @inheritdoc */
  override def stopBackgroundJobs(): Boolean =
    synchronized {
      val result = isBackgroundJobsStarted
      isBackgroundJobsStarted = false
      result
    }

  /** @inheritdoc */
  override def stop(): Unit = {
    val allJobs = runningJobsRef.get()
    allJobs.foreach(_.future.cancel(true))
    runtimeContext.executionService.getContext.getThreadManager
      .interruptThreads()
    jobExecutor.shutdownNow()
    backgroundJobExecutor.shutdownNow()
  }

  /** Submit background jobs preserving the stable order. */
  private def submitBackgroundJobsOrdered(): Unit = {
    Collections.sort(
      delayedBackgroundJobsQueue,
      BackgroundJob.BACKGROUND_JOBS_QUEUE_ORDER
    )
    runtimeContext.executionService.getLogger.log(
      Level.FINE,
      "Submitting {0} background jobs [{1}]",
      Array[AnyRef](
        delayedBackgroundJobsQueue.size(): Integer,
        delayedBackgroundJobsQueue
      )
    )
    delayedBackgroundJobsQueue.forEach(job => runBackground(job))
    delayedBackgroundJobsQueue.clear()
  }

  private val runningJobPartialFunction: PartialFunction[RunningJob, Job[_]] = {
    case RunningJob(_, job, _) => job
  }

  override def jobInProgress[T](
    filter: PartialFunction[Job[_], Option[T]]
  ): Option[T] = {
    val allJobs    = runningJobsRef.get()
    val fullFilter = runningJobPartialFunction.andThen(filter)
    allJobs.collectFirst(fullFilter).flatten
  }
}
