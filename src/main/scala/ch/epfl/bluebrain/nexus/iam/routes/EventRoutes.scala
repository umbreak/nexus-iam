package ch.epfl.bluebrain.nexus.iam.routes

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.server.Rejections._
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, PathMatcher0, Route}
import akka.persistence.query._
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.iam.acls.AclEvent.JsonLd._
import ch.epfl.bluebrain.nexus.iam.acls.{AclEvent, Acls}
import ch.epfl.bluebrain.nexus.iam.config.AppConfig
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.tracing._
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.{HttpConfig, PersistenceConfig}
import ch.epfl.bluebrain.nexus.iam.directives.AuthDirectives._
import ch.epfl.bluebrain.nexus.iam.io.TaggingAdapter._
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsEvent
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsEvent.JsonLd._
import ch.epfl.bluebrain.nexus.iam.realms.RealmEvent.JsonLd._
import ch.epfl.bluebrain.nexus.iam.realms.{RealmEvent, Realms}
import ch.epfl.bluebrain.nexus.iam.routes.EventRoutes._
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission}
import ch.epfl.bluebrain.nexus.iam.{acls => aclsp, permissions => permissionsp, realms => realmsp}
import io.circe.Encoder
import io.circe.syntax._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
  * The event stream routes.
  *
  * @param acls   the acls api
  * @param realms the realms api
  */
class EventRoutes(acls: Acls[Task], realms: Realms[Task])(implicit as: ActorSystem,
                                                          hc: HttpConfig,
                                                          pc: PersistenceConfig) {

  private val pq: EventsByTagQuery = PersistenceQuery(as).readJournalFor[EventsByTagQuery](pc.queryJournalPlugin)

  private implicit val implAcls: Acls[Task] = acls

  def routes: Route = {
    (handleRejections(RejectionHandling()) & handleExceptions(ExceptionHandling()) & pathPrefix(hc.prefix)) {
      concat(
        // TODO: replace `acls/write` with `acls/read` when self=false checks for `acls/read`
        routesFor("acls" / "events", aclEventTag, aclsp.write, typedEventToSse[AclEvent]),
        routesFor("permissions" / "events", permissionsEventTag, permissionsp.read, typedEventToSse[PermissionsEvent]),
        routesFor("realms" / "events", realmEventTag, realmsp.read, typedEventToSse[RealmEvent]),
        routesFor("events", eventTag, eventsRead, eventToSse),
      )
    }
  }

  private def routesFor(
      pm: PathMatcher0,
      tag: String,
      permission: Permission,
      toSse: EventEnvelope => Option[ServerSentEvent]
  ): Route =
    path(pm) {
      authenticateOAuth2Async("*", authenticator(realms)).withAnonymousUser(Caller.anonymous) { implicit caller =>
        authorizeFor(permission).apply {
          lastEventId { offset =>
            trace(s"${tag}Events") {
              complete(source(tag, offset, toSse))
            }
          }
        }
      }
    }

  protected def source(
      tag: String,
      offset: Offset,
      toSse: EventEnvelope => Option[ServerSentEvent]
  ): Source[ServerSentEvent, NotUsed] = {
    pq.eventsByTag(tag, offset)
      .flatMapConcat(ee => Source(toSse(ee).toList))
      .keepAlive(10 seconds, () => ServerSentEvent.heartbeat)
  }

  private def lastEventId: Directive1[Offset] =
    optionalHeaderValueByName(`Last-Event-ID`.name)
      .map(_.map(id => `Last-Event-ID`(id)))
      .flatMap {
        case Some(header) =>
          Try[Offset](TimeBasedUUID(UUID.fromString(header.id))) orElse Try(Sequence(header.id.toLong)) match {
            case Success(value) => provide(value)
            case Failure(_)     => reject(validationRejection("The value of the `Last-Event-ID` header is not valid."))
          }
        case None => provide(NoOffset)
      }

  private def aToSse[A: Encoder](a: A, offset: Offset): ServerSentEvent = {
    import ch.epfl.bluebrain.nexus.commons.http.syntax.circe._
    val json = a.asJson.sortKeys(AppConfig.orderedKeys)
    ServerSentEvent(
      data = json.noSpaces,
      eventType = json.hcursor.get[String]("@type").toOption,
      id = offset match {
        case NoOffset            => None
        case Sequence(value)     => Some(value.toString)
        case TimeBasedUUID(uuid) => Some(uuid.toString)
      }
    )
  }

  private def eventToSse(envelope: EventEnvelope): Option[ServerSentEvent] =
    envelope.event match {
      case value: AclEvent         => Some(aToSse(value, envelope.offset))
      case value: RealmEvent       => Some(aToSse(value, envelope.offset))
      case value: PermissionsEvent => Some(aToSse(value, envelope.offset))
      case _                       => None
    }

  private def typedEventToSse[A: Encoder](envelope: EventEnvelope)(implicit A: ClassTag[A]): Option[ServerSentEvent] =
    envelope.event match {
      case A(a) => Some(aToSse(a, envelope.offset))
      case _    => None
    }
}

object EventRoutes {
  // read permissions for the global event log
  final val eventsRead = Permission.unsafe("events/read")
}
