package com.seamless.oas.versions

import com.seamless.oas
import com.seamless.oas.Schemas.{Definition, HeaderParameter, NamedDefinition, Operation, Path, PathParameter, QueryParameter, RequestBody, Response, SharedResponse}
import com.seamless.oas.{Context, JSONReference, OASResolver, Schemas}
import play.api.libs.json.{JsArray, JsBoolean, JsObject, JsString, JsValue}
import sun.jvm.hotspot.memory.HeapBlock.Header

import scala.util.Try

class OAS2Resolver(root: JsObject) extends OASResolver(root, "2") {

  //Get produces, walk graph from nearest to root
  def getProduces(operation: Operation)(implicit ctx: Context): Vector[String] = {
    getProduces(operation.cxt.root, operation.path.cxt.root, root)
  }

  def getProduces(priority: JsValue*)(implicit ctx: Context): Vector[String] = {
    def extractProduces(jsonRoot: JsValue): Try[Vector[String]] = Try {
      (jsonRoot \ "produces").as[JsArray].value.map(_.as[JsString].value).toVector
    }

    priority.collectFirst{ case jsonRoot if extractProduces(jsonRoot).isSuccess => extractProduces(jsonRoot).get }
      .getOrElse(Vector.empty[String])
  }

  override def responsesForOperation(operation: Operation)(implicit ctx: Context): Vector[Response] = {

    val responseObject = (operation.cxt.root.as[JsObject] \ "responses")
      .getOrElse(JsObject.empty).as[JsObject].value.toVector
      .filter(i => Try(i._1.toInt).isSuccess)


    responseObject.map { case (status, description) => {
      val statusAsInt = status.toInt
      val schema = description.as[JsObject] \ "schema"

      val produces = if (schema.isDefined) {
        Some(getProduces(operation).headOption.getOrElse("application/json"))
      } else {
        None
      }

      val isRef = description.isInstanceOf[JsObject] && description.as[JsObject].value.contains("$ref")

      if (isRef) {
        val ref = description.as[JsObject].value("$ref").as[JsString].value
        val resolved = resolveSharedResponse(ref)
        require(resolved.isDefined, s"Could not resolve shared response ${ref}")
        //give inline shape a custom id so there aren't conflicts
        val newSchema = resolved.get.schema.map(s => s.asInstanceOf[Definition].copy(id = IdGenerator.inlineDefinition))
        Response(statusAsInt, resolved.get.contentType, newSchema)(buildContext(resolved.get.cxt.root))
      } else {
        val inlineSchema = schema.toOption
          .map(i => Definition(i.as[JsObject], IdGenerator.inlineDefinition)(buildContext(i.as[JsObject])))
        Response(statusAsInt, produces, inlineSchema)(buildContext(description))
      }
    }}

  }


  //Get consumes, walk graph from nearest to root
  def getConsumes(operation: Operation)(implicit ctx: Context): Vector[String] = {
    getConsumes(operation.cxt.root, operation.path.cxt.root, root)
  }

  def getConsumes(priority: JsValue*)(implicit ctx: Context): Vector[String] = {
    def extractProduces(jsonRoot: JsValue): Try[Vector[String]] = Try {
      (jsonRoot \ "consumes").as[JsArray].value.map(_.as[JsString].value).toVector
    }
    priority.collectFirst{ case jsonRoot if extractProduces(jsonRoot).isSuccess => extractProduces(jsonRoot).get }
      .getOrElse(Vector.empty[String])
  }


  override def requestBodyForOperation(operation: Operation)(implicit ctx: Context): Option[RequestBody] = {
    if (operation.supportsBody) {
      val consumes = getConsumes(operation).headOption
      parametersForOperation(operation)
        .collectFirst{ case p if p.isBodyParameter => p }
        .map { bodyParam =>
          val definition = Definition(bodyParam.schema, IdGenerator.stableInlineRequestBodyDefinition(operation))
          RequestBody(consumes, Some(definition))
      }
    } else {
      None
    }
  }

  private def parametersForOperation(operation: Operation)(implicit ctx: Context): Vector[Helpers.OAS2Param] = {
    (operation.cxt.root.as[JsObject] \ "parameters")
      .getOrElse(JsArray.empty).as[JsArray]
      .value
      .map(i => Helpers.OAS2Param(i.as[JsObject]))
      .toVector
  }

  def queryParametersForOperation(operation: Operation)(implicit ctx: Context): Vector[QueryParameter] = {
    parametersForOperation(operation)
      .collect{ case param if param.isQueryParameter => {
        QueryParameter(param.name, param.required)(buildContext(param.jsObject))
      }}
  }

  def headerParametersForOperation(operation: Operation)(implicit ctx: Context): Vector[HeaderParameter] = {
    parametersForOperation(operation)
      .collect{ case param if param.isHeaderParameter => {
        HeaderParameter(param.name, param.required)(buildContext(param.jsObject))
      }}
  }


  override def parametersForPath(path: Schemas.Path)(implicit ctx: Context): Vector[Schemas.PathParameter] = {
    import Helpers.distinctBy
    val allParams = path.operations.flatMap {
      case op => {
        (op.cxt.root.as[JsObject] \ "parameters")
          .getOrElse(JsArray.empty).as[JsArray]
          .value
          .map(i => Helpers.OAS2Param(i.as[JsObject]))
      }
    }.toList

    val uniqueParams = distinctBy(allParams)(i => i.name)

    val sorted = uniqueParams
      .toVector
      .filter(_.isPathParameter)
      .sortBy(param => path.uri.indexOf(s"{${param.name}"))

    sorted.zipWithIndex.map { case (param, index) => PathParameter(param.name, index)(buildContext(param.jsObject))}
  }

  lazy val definitions: Vector[NamedDefinition] = {
    (root \ "definitions").getOrElse(JsObject.empty).as[JsObject].value.toVector.map {
      case (name, schema) => {
        NamedDefinition(name, schema.as[JsObject], IdGenerator.definition(name))(buildContext(schema))
      }
    }
  }

  lazy val sharedResponses: Vector[SharedResponse] = {
    (root \ "responses").getOrElse(JsObject.empty).as[JsObject].value.toVector.map {
      case (key, value) => {
        val produces = getProduces(value, root)(buildContext(value)).headOption
        val schema = (value.as[JsObject] \ "schema").toOption
            .map(i => Definition(i.as[JsObject], IdGenerator.inlineDefinition)(buildContext(i.as[JsObject])))
        SharedResponse(key, if (schema.isDefined) produces else None, schema)(buildContext(value))
      }
    }
  }

  private object Helpers {
    case class OAS2Param(jsObject: JsObject) {

      private val parameterDefinition = {
        if ((jsObject \ "$ref").isDefined) {
          val ref = (jsObject \ "$ref").get.as[JsString].value
          JSONReference.walk(ref, root).get
        } else {
          jsObject
        }
      }

      def in = (parameterDefinition \ "in").get.as[JsString].value
      def name = (parameterDefinition \ "name").get.as[JsString].value
      def required = (parameterDefinition \ "required").getOrElse(JsBoolean(true)).as[JsBoolean].value

      def isPathParameter = in == "path"
      def isBodyParameter = in == "body"
      def isHeaderParameter = in == "header"
      def isQueryParameter = in == "query"

      def schema = (parameterDefinition \ "schema").getOrElse(JsObject.empty).as[JsObject]
    }

    def distinctBy[A, B](xs: List[A])(f: A => B): List[A] =
      scala.reflect.internal.util.Collections.distinctBy(xs)(f)
  }

}
