package org.enso.languageserver.boot

import akka.event.EventStream
import org.enso.jsonrpc.ProtocolFactory
import org.enso.languageserver.boot.resource.{
  AsyncResourcesInitialization,
  DirectoriesInitialization,
  InitializationComponent,
  JsonRpcInitialization,
  RepoInitialization,
  SequentialResourcesInitialization,
  TruffleContextInitialization,
  ZioRuntimeInitialization
}
import org.enso.languageserver.data.ProjectDirectoriesConfig
import org.enso.languageserver.effect
import org.enso.searcher.memory.InMemorySuggestionsRepo
import org.graalvm.polyglot.Context

import scala.concurrent.ExecutionContextExecutor

/** Helper object for the initialization of the Language Server resources.
  * Creates the directories, initializes the databases, and the Truffle context.
  */
object ResourcesInitialization {

  /** Create the initialization component of the Language Server.
    *
    * @param eventStream system event stream
    * @param directoriesConfig configuration of directories that should be created
    * @param protocolFactory the JSON-RPC protocol factory
    * @param suggestionsRepo the suggestions repo
    * @param truffleContext the runtime context
    * @param runtime the runtime to run effects
    * @return the initialization component
    */
  def apply(
    eventStream: EventStream,
    directoriesConfig: ProjectDirectoriesConfig,
    protocolFactory: ProtocolFactory,
    suggestionsRepo: InMemorySuggestionsRepo,
    truffleContext: Context,
    runtime: effect.Runtime
  )(implicit ec: ExecutionContextExecutor): InitializationComponent = {
    new SequentialResourcesInitialization(
      ec,
      new DirectoriesInitialization(ec, directoriesConfig),
      new AsyncResourcesInitialization(
        new JsonRpcInitialization(ec, protocolFactory),
        new ZioRuntimeInitialization(ec, runtime, eventStream),
        new RepoInitialization(
          ec,
          directoriesConfig,
          eventStream,
          suggestionsRepo
        ),
        new TruffleContextInitialization(ec, truffleContext, eventStream)
      )
    )
  }
}
