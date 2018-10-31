/*
 * Copyright © 2011-2015 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.site

import akka.event.Logging._
import shapeless._
import spray.routing.directives.{ DirectoryListing, LogEntry }
import spray.httpx.marshalling.Marshaller
import spray.httpx.PlayTwirlSupport._
import spray.http._
import spray.routing._
import html._
import StatusCodes._

class SiteServiceActor(settings: SiteSettings) extends HttpServiceActor with SearchSuggestions {

  // format: OFF
  def receive = runRoute {
    dynamicIf(settings.devMode) { // for proper support of twirl + sbt-revolver during development
      (get & compressResponse()) {
        host("repo.spray.io") {
          logRequestResponse(showRepoResponses("repo") _) {
            getFromBrowseableDirectories(settings.repoDirs: _*) ~
            complete(NotFound)
          }
        } ~
        (host("repo.spray.cc") & unmatchedPath) { ump =>
          redirect("http://repo.spray.io" + ump, Found)
        } ~
        host("nightlies.spray.io") {
          logRequestResponse(showRepoResponses("nightlies") _) {
            getFromBrowseableDirectories(settings.nightliesDir) ~
            complete(NotFound)
          }
        } ~
        (host("nightlies.spray.cc") & unmatchedPath) { ump =>
          redirect("http://nightlies.spray.io" + ump, Found)
        } ~
        host("spray.io", "localhost", "127.0.0.1") {
          path("favicon.ico") {
            complete(NotFound) // fail early in order to prevent error response logging
          } ~
          logRequestResponse(showErrorResponses _) {
            talkCharts("berlin") ~
            talkCharts("duse") ~
            talkCharts("jax14") ~
            talkCharts("jfokus") ~
            talkCharts("msug") ~
            talkCharts("nescala2015") ~
            talkCharts("nescala2015/rs") ~
            talkCharts("scala.io/2013") ~
            talkCharts("scala.io/2014") ~
            talkCharts("scaladays/2014") ~
            talkCharts("scaladays/2015") ~
            talkCharts("scalax/2014") ~
            talkCharts("vienna") ~
            talkCharts("webinar") ~
            talkCharts("webinar2014") ~
            talkCharts("wjax") ~
            talkCharts("zse") ~
            searchRoute("spray.io") ~
            path("webinar" / "video" /) { redirect("http://www.youtube.com/watch?v=7MqD7_YvZ8Q", Found) } ~
            getFromResourceDirectory("theme") ~
            pathPrefix("_images") {
              getFromResourceDirectory("sphinx/json/_images")
            } ~
            pathPrefix("documentation" / Segment / "api") { version =>
              val dir = s"api/$version/"
              pathEnd {
                redirect(s"/documentation/$version/api/", MovedPermanently)
              } ~
                pathSingleSlash {
                  getFromResource(dir + "index.html")
                } ~
                getFromResourceDirectory(dir)
            } ~
            logRequest(showRequest _) {
              pathSingleSlash {
                complete(page(home()))
              } ~
              pathPrefix("files") {
                getFromDirectory("/opt/spray.io/files")
              } ~
              pathSuffixTest(Slash) {
                path("home" /) {
                  redirect("/", MovedPermanently)
                } ~
                path("index" /) {
                  complete(page(index()))
                } ~
                pathPrefixTest("blog") {
                  path("blog" /) {
                    complete(page(blogIndex(Main.blog.root.children), Main.blog.root))
                  } ~
                  path("blog" / "feed" /) {
                    complete(xml.blogAtomFeed())
                  } ~
                  path("blog" / "category" / Segment /) { tag =>
                    Main.blog.posts(tag) match {
                      case Nil => complete(NotFound, page(error404()))
                      case posts => complete(page(blogIndex(posts, tag), Main.blog.root))
                    }
                  } ~
                  sphinxNode { node =>
                    complete(page(blogPost(node), node))
                  }
                } ~
                pathPrefixTest("documentation" / !IntNumber ~ !PathEnd ~ Rest) { subUri =>
                  val latestVersion = Main.settings.otherVersions.last.takeWhile(_ != ' ')
                  redirect("/documentation/" + latestVersion + '/' + subUri, MovedPermanently)
                } ~
                requestUri { uri =>
                  val path = uri.path.toString
                  "(?:-RC[1234])|(?:.[01])/".r.findFirstIn(path) match {
                    case Some(found) => redirect(uri.withPath(Uri.Path(path.replace(found, ".2/"))), MovedPermanently)
                    case None => reject
                  }
                } ~
                sphinxNode { node =>
                  complete(page(document(node), node))
                }
              } ~
              unmatchedPath { ump =>
                redirect(ump.toString + "/", MovedPermanently)
              }
            }
          }
        } ~
        unmatchedPath { ump =>
          redirect("http://spray.io" + ump, Found)
        }
      }
    }
  }
  // format: ON

  val sphinxNode = path(Rest).map(Main.root.find).flatMap[ContentNode :: HNil] {
    case None       ⇒ complete(NotFound, page(error404()))
    case Some(node) ⇒ provide(node)
  }

  def showRequest(request: HttpRequest) = LogEntry(request.uri, InfoLevel)

  def showErrorResponses(request: HttpRequest): Any ⇒ Option[LogEntry] = {
    case HttpResponse(OK | NotModified | PartialContent, _, _, _) ⇒ None
    case HttpResponse(NotFound, _, _, _)                          ⇒ Some(LogEntry("404: " + request.uri, WarningLevel))
    case r @ HttpResponse(Found | MovedPermanently, _, _, _) ⇒
      Some(LogEntry(s"${r.status.intValue}: ${request.uri} -> ${r.header[HttpHeaders.Location].map(_.uri.toString).getOrElse("")}", WarningLevel))
    case response ⇒ Some(
      LogEntry("Non-200 response for\n  Request : " + request + "\n  Response: " + response, WarningLevel))
  }

  def showRepoResponses(repo: String)(request: HttpRequest): HttpResponsePart ⇒ Option[LogEntry] = {
    case HttpResponse(s @ (OK | NotModified), _, _, _) ⇒
      Some(LogEntry(repoResponseLogLine(repo, s.intValue.toString, request), InfoLevel))
    case ChunkedResponseStart(HttpResponse(OK, _, _, _)) ⇒
      Some(LogEntry(repoResponseLogLine(repo, "200 (chunked)", request), InfoLevel))
    case HttpResponse(NotFound, _, _, _) ⇒
      Some(LogEntry(repoResponseLogLine(repo, "404", request)))
    case _ ⇒ None
  }

  def repoResponseLogLine(repo: String, status: String, request: HttpRequest): String = {
    import HttpHeaders._
    val ip = request.header[`Remote-Address`].map(_.address.toString).getOrElse("n/a")
    val userAgent = request.header[`User-Agent`].map(_.products.mkString(" ")).getOrElse("n/a")
    s"$repo|:|$status|:|$ip|:|$userAgent|:|${request.uri}"
  }

  implicit val ListingMarshaller: Marshaller[DirectoryListing] =
    Marshaller.delegate(MediaTypes.`text/html`) { (listing: DirectoryListing) ⇒
      listing.copy(
        files = listing.files.filterNot(file ⇒
          file.getName.startsWith(".") || file.getName.startsWith("archetype-catalog")))
    }(DirectoryListing.DefaultMarshaller)

  def talkCharts(talk: String) =
    pathPrefix(PathMatchers.separateOnSlashes(talk)) {
      pathEnd {
        redirect(s"/$talk/", MovedPermanently)
      } ~
        pathSingleSlash {
          getFromResource(s"talks/$talk/index.html")
        } ~
        getFromResourceDirectory("talks/" + talk)
    }
}
