package com.seamless.oas.oas_to_commands

import com.seamless.oas.ResolverTestFixture
import com.seamless.oas.QueryImplicits._
import RequestsToCommandsImplicits._
import com.seamless.contexts.data_types.Commands.AssignType
import com.seamless.contexts.requests.Commands.SetResponseBodyShape
import com.seamless.contexts.requests.Commands._
import com.seamless.contexts.rfc.Events.RfcEvent
import com.seamless.contexts.rfc.RfcService
import com.seamless.ddd.InMemoryEventStore

import scala.util.Try
class RequestsToCommandsImplicitsSpec extends ResolverTestFixture("2") {

  val mattermostResolver = resolverFor(mattermost)
  val oxfordResolver = resolverFor(oxfordDictionary)

  it("can create path component commands from valid paths") {
      val pathContext = mattermostResolver.paths.toCommandStream
      assert(pathContext.commands.init.size == 243)
      assert(pathContext.pathToId(mattermostResolver.paths.~#("/users")).endsWith("path_175"))
  }

  it("can build path graph in RFC service") {
    val pathContext = mattermostResolver.paths.toCommandStream
    val service = new RfcService(new InMemoryEventStore[RfcEvent])

    pathContext.commands.init.foreach(i => {
      val result = Try(service.handleCommand("test", i))
      if (result.isFailure) {
        result.failed.get.printStackTrace()
        throw new Error("STOP EVERYTHING")
      }
    })

  }

  it("can create path component commands even when users rely on conflicting paths") {
      val pathContext = oxfordResolver.paths.toCommandStream
      assert(pathContext.uriToId("/wordlist/{source_lang}/{filters_advanced}").endsWith("_path_44") )
  }

  it("can form commands from operations with multiple responses and a request body") {

    implicit val pathContext = mattermostResolver.paths.toCommandStream

    val commands = mattermostResolver.paths.~#("/users").operations.~#("post").toCommandStream

    //adds the responses correctly
    val statuses = commands.init.collect {
      case r: AddResponse => r.httpStatusCode
    }
    assert(statuses.size == 3)
    assert(statuses.toSet == Set(201, 400, 403))

    //response commands add their inline shapes to the events
    assert(commands.init.count(i => i.isInstanceOf[AssignType] && i.asInstanceOf[AssignType].to.isRef) == 4)
    assert(commands.describe.count(_.isInstanceOf[SetResponseBodyShape]) == 3)
    assert(commands.describe.count(_.isInstanceOf[SetRequestBodyShape]) == 1)
  }



}
