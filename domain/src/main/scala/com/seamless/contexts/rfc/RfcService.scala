package com.seamless.contexts.rfc

import com.seamless.contexts.data_types.Commands.{ConceptId}
import com.seamless.contexts.data_types.projections.ShapeProjection
import com.seamless.contexts.rfc.Commands.RfcCommand
import com.seamless.contexts.rfc.Events.RfcEvent
import com.seamless.ddd.{AggregateId, EventSourcedRepository, EventSourcedService, EventStore, InMemoryEventStore}
import com.seamless.serialization.CommandSerialization

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportAll}
import scala.util.Try

class RfcService(eventStore: EventStore[RfcEvent]) extends EventSourcedService[RfcCommand, RfcState] {
  private val repository = new EventSourcedRepository[RfcState, RfcEvent](RfcAggregate, eventStore)

  def handleCommand(id: AggregateId, command: RfcCommand): Unit = {
    val state = repository.findById(id)
    val context = RfcCommandContext()
    val effects = RfcAggregate.handleCommand(state)((context, command))
    repository.save(id, effects.eventsToPersist)
  }

  def currentState(id: AggregateId): RfcState = {
    repository.findById(id)
  }

  @JSExport
  def commandHandlerForAggregate(id: AggregateId): js.Function1[RfcCommand, Unit] = (command: RfcCommand) => {
    handleCommand(id, command)
  }
}

@JSExport
@JSExportAll
object RfcServiceJSFacade {

  def makeEventStore() = {
    new InMemoryEventStore[RfcEvent]
  }

  def fromCommands(eventStore: EventStore[RfcEvent], commands: Vector[RfcCommand], id: AggregateId): RfcService = {
    val service = new RfcService(eventStore)
    commands.foreach(command => {
      val result = Try(service.handleCommand(id, command))
      if (result.isFailure) {
        println(command)
        println(result)
        throw result.failed.get
      }
    })
    service
  }

  def fromJsonCommands(eventStore: EventStore[RfcEvent], jsonString: String, id: AggregateId): RfcService = {
    import io.circe.parser._

    val commandsVector =
      for {
        json <- Try(parse(jsonString).right.get)
        commandsVector <- CommandSerialization.fromJson(json)
      } yield commandsVector
    if (commandsVector.isFailure) {
      println(commandsVector.failed.get)
    }
    require(commandsVector.isSuccess, "failed to parse and handle commands")

    fromCommands(eventStore, commandsVector.get, id)
  }
}

