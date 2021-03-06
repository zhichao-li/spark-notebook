package notebook
package server

import java.io.File

import scala.concurrent._
import scala.concurrent.duration._

import akka.actor._
import akka.actor.Terminated

import play.api._
import play.api.libs.json._

import notebook.client._

/**
 * Provides a web-socket interface to the Calculator
 */
class CalcWebSocketService(
  system: ActorSystem,
  customLocalRepo:Option[String],
  customRepos:Option[List[String]],
  customDeps:Option[List[String]],
  customImports:Option[List[String]],
  customSparkConf:Option[Map[String, String]],
  initScripts: List[(String, String)],
  compilerArgs: List[String],
  remoteDeployFuture: Future[Deploy],
  tachyonInfo:notebook.server.TachyonInfo) {

  implicit val executor = system.dispatcher

  val calcActor = system.actorOf(Props( new CalcActor ))

  def register(ws:WebSockWrapper) = {
    calcActor ! Register(ws)
  }

  def unregister(ws:WebSockWrapper) = {
    calcActor ! Unregister(ws)
  }


  class CalcActor extends Actor with ActorLogging {
    private var currentSessionOperation: Option[ActorRef] = None
    var calculator: ActorRef = null
    var wss: List[WebSockWrapper] = Nil

    val ws = new {
      def send(header: JsValue, session: JsValue /*ignored*/, msgType: String, channel:String, content: JsValue) = {
        Logger.debug(s"Sending info to websockets $wss in $this")
        wss.foreach { ws =>
          ws.send(header, ws.session, msgType, channel, content)
        }
      }
    }

    private def spawnCalculator() {
      // N.B.: without these local copies of the instance variables, we'll capture all sorts of things in our closure
      // that we don't want, then akka's attempts at serialization will fail and kittens everywhere will cry.
      val kCompilerArgs = compilerArgs
      val kCustomLocalRepo = customLocalRepo
      val kCustomRepos = customRepos
      val kCustomDeps = customDeps
      val kCustomImports = customImports

      val tachyon = Map(
        "spark.tachyonStore.url"     → tachyonInfo.url.getOrElse("tachyon://"+notebook.share.Tachyon.host+":"+notebook.share.Tachyon.port),
        "spark.tachyonStore.baseDir" → tachyonInfo.baseDir
      )
      val kCustomSparkConf = customSparkConf.map(_ ++ tachyon).orElse(Some(tachyon))

      val kInitScripts = initScripts
      val remoteDeploy = Await.result(remoteDeployFuture, 2 minutes)

      calculator = context.actorOf {
        Props(new ReplCalculator( kCustomLocalRepo,
                                  kCustomRepos,
                                  kCustomDeps,
                                  kCustomImports,
                                  kCustomSparkConf,
                                  kInitScripts,
                                  kCompilerArgs)
        ).withDeploy(remoteDeploy)
      }
    }

    def receive = {
      case Register(ws:WebSockWrapper) if wss.isEmpty && calculator == null =>
        Logger.info(s"Registering first web-socket ($ws) in service ${this}")
        wss = List(ws)
        Logger.info(s"Spawning calculator in service ${this}")
        spawnCalculator()

      case Register(ws:WebSockWrapper) =>
        Logger.info(s"Registering web-socket ($ws) in service ${this} (current cout is ${wss.size})")
        wss = ws :: wss

      case Unregister(ws:WebSockWrapper) =>
        Logger.info(s"UN-registering web-socket ($ws) in service ${this} (current cout is ${wss.size})")
        wss = wss.filterNot(_ == ws)

      case InterruptCalculator =>
        for (op <- currentSessionOperation) {
          calculator.tell(InterruptRequest, op)
        }

      case req@SessionRequest(header, session, request) =>
        val operations = new SessionOperationActors(header, session)
        val operationActor = (request: @unchecked) match {
          case ExecuteRequest(counter, code) =>
            ws.send(header, session, "status", "iopub", Json.obj("execution_state" → "busy"))
            ws.send(header, session, "pyin",  "iopub", Json.obj("execution_count" → counter, "code" → code))
            operations.singleExecution(counter)

          case _: CompletionRequest =>
            operations.completion

          case _: ObjectInfoRequest =>
            operations.objectInfo
        }
        val operation = context.actorOf(operationActor)
        context.watch(operation)
        currentSessionOperation = Some(operation)
        calculator.tell(request, operation)


      case Terminated(actor) =>
        log.warning("Termination")
        if (actor == calculator) {
          spawnCalculator()
        } else {
          currentSessionOperation = None
        }
    }

    class SessionOperationActors(header: JsValue, session: JsValue) {
      def singleExecution(counter: Int) = Props(new Actor {
        def receive = {
          case StreamResponse(data, name) =>
            ws.send(header, session, "stream", "iopub", Json.obj("text" → data, "name" → name))

          case ExecuteResponse(html) =>
            ws.send(header, session, "execute_result", "iopub", Json.obj("execution_count" → counter, "data" → Json.obj("text/html" → html)))
            ws.send(header, session, "status", "iopub", Json.obj("execution_state" → "idle"))
            ws.send(header, session, "execute_reply", "shell", Json.obj("execution_count" → counter))
            context.stop(self)

          case ErrorResponse(msg, incomplete) =>
            if (incomplete) {
              ws.send(header, session, "error", "iopub", Json.obj("execution_count" → counter, "status" → "error", "ename" → "Error", "traceback" → Seq(msg)))
            } else {
              //already printed by the repl!
              //ws.send(header, session, "error", "iopub", Json.obj("execution_count" → counter, "status" → "error", "ename" → "Error", "traceback" → Seq(msg)))
            }
            ws.send(header, session, "status", "iopub", Json.obj("execution_state" → "idle"))
            ws.send(header, session, "execute_reply", "shell", Json.obj("execution_count" → counter))
            context.stop(self)
        }
      })

      def completion = Props(new Actor {
        def receive = {
          case CompletionResponse(cursorPosition, candidates, matchedText) =>
            ws.send(header, session, "complete_reply", "shell", Json.obj("matched_text" → matchedText, "matches" → candidates.map(_.toJson).toList, "cursor_start" → (cursorPosition-matchedText.size), "cursor_end" → cursorPosition))
            context.stop(self)
        }
      })

      def objectInfo = Props(new Actor {
        def receive = {
          case ObjectInfoResponse(found, name, callDef, callDocString) =>
            ws.send(
              header,
              session,
              "object_info_reply",
              "shell",
              Json.obj(
                "found" → found,
                "name" → name,
                "call_def" → callDef,
                "call_docstring" → "Description TBD"
              )
            )
            context.stop(self)
        }
      })
    }
  }
}