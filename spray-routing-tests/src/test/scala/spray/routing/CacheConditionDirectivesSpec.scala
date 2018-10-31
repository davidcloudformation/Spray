/*
 * Copyright © 2011-2014 the spray project <http://spray.io>
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

package spray.routing

import spray.http._
import spray.http.StatusCodes._
import spray.http.HttpHeaders._

class CacheConditionDirectivesSpec extends RoutingSpec {

  "the `conditional` directive" should {
    val timestamp = DateTime.now - 2000
    val ifUnmodifiedSince = `If-Unmodified-Since`(timestamp)
    val ifModifiedSince = `If-Modified-Since`(timestamp)
    val tag = EntityTag("fresh")
    val responseHeaders = List(ETag(tag), `Last-Modified`(timestamp))

    def taggedAndTimestamped = conditional(tag, timestamp) { completeOk }
    def weak = conditional(tag.copy(weak = true), timestamp) { completeOk }

    "return OK for new resources" in {
      Get() ~> taggedAndTimestamped ~> check {
        status === OK
        headers must containAllOf(responseHeaders)
      }
    }

    "return OK for non-matching resources" in {
      Get() ~> `If-None-Match`(EntityTag("old")) ~> taggedAndTimestamped ~> check {
        status === OK
        headers must containAllOf(responseHeaders)
      }
      Get() ~> `If-Modified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status === OK
        headers must containAllOf(responseHeaders)
      }
      Get() ~> `If-None-Match`(EntityTag("old")) ~> `If-Modified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status === OK
        headers must containAllOf(responseHeaders)
      }
    }

    "ignore If-Modified-Since if If-None-Match is defined" in {
      Get() ~> `If-None-Match`(tag) ~> `If-Modified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status === NotModified
      }
      Get() ~> `If-None-Match`(EntityTag("old")) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status === OK
      }
    }

    "return PreconditionFailed for matched but unsafe resources" in {
      Put() ~> `If-None-Match`(tag) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status === PreconditionFailed
        headers === Nil
      }
    }

    "return NotModified for matching resources" in {
      Get() ~> `If-None-Match`.`*` ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status === NotModified
        headers must containAllOf(responseHeaders)
      }
      Get() ~> `If-None-Match`(tag) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status === NotModified
        headers must containAllOf(responseHeaders)
      }
      Get() ~> `If-None-Match`(tag) ~> `If-Modified-Since`(timestamp + 1000) ~> taggedAndTimestamped ~> check {
        status === NotModified
        headers must containAllOf(responseHeaders)
      }
      Get() ~> `If-None-Match`(tag.copy(weak = true)) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status === NotModified
        headers must containAllOf(responseHeaders)
      }
      Get() ~> `If-None-Match`(tag, EntityTag("some"), EntityTag("other")) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status === NotModified
        headers must containAllOf(responseHeaders)
      }
    }

    "return NotModified when only one matching header is set" in {
      Get() ~> `If-None-Match`.`*` ~> taggedAndTimestamped ~> check {
        status === NotModified
        headers must containAllOf(responseHeaders)
      }
      Get() ~> `If-None-Match`(tag) ~> taggedAndTimestamped ~> check {
        status === NotModified
        headers must containAllOf(responseHeaders)
      }
      Get() ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status === NotModified
        headers must containAllOf(responseHeaders)
      }
    }

    "return NotModified for matching weak resources" in {
      val weakTag = tag.copy(weak = true)
      Get() ~> `If-None-Match`(tag) ~> weak ~> check {
        status === NotModified
        headers must containAllOf(List(ETag(weakTag), `Last-Modified`(timestamp)))
      }
      Get() ~> `If-None-Match`(weakTag) ~> weak ~> check {
        status === NotModified
        headers must containAllOf(List(ETag(weakTag), `Last-Modified`(timestamp)))
      }
    }

    "return normally for matching If-Match/If-Unmodified" in {
      Put() ~> `If-Match`.`*` ~> taggedAndTimestamped ~> check {
        status === OK
        headers must containAllOf(responseHeaders)
      }
      Put() ~> `If-Match`(tag) ~> taggedAndTimestamped ~> check {
        status === OK
        headers must containAllOf(responseHeaders)
      }
      Put() ~> ifUnmodifiedSince ~> taggedAndTimestamped ~> check {
        status === OK
        headers must containAllOf(responseHeaders)
      }
    }

    "return PreconditionFailed for non-matching If-Match/If-Unmodified" in {
      Put() ~> `If-Match`(EntityTag("old")) ~> taggedAndTimestamped ~> check {
        status === PreconditionFailed
        headers === Nil
      }
      Put() ~> `If-Unmodified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status === PreconditionFailed
        headers === Nil
      }
    }

    "ignore If-Unmodified-Since if If-Match is defined" in {
      Put() ~> `If-Match`(tag) ~> `If-Unmodified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status === OK
      }
      Put() ~> `If-Match`(EntityTag("old")) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status === PreconditionFailed
      }
    }

    "not filter out a `Range` header if `If-Range` does match the timestamp" in {
      Get() ~> `If-Range`(timestamp) ~> Range(ByteRange(0, 10)) ~> {
        (conditional(tag, timestamp) & optionalHeaderValueByType[Range]()) { echoComplete }
      } ~> check {
        status === OK
        responseAs[String] must startWith("Some")
      }
    }

    "filter out a `Range` header if `If-Range` doesn't match the timestamp" in {
      Get() ~> `If-Range`(timestamp - 1000) ~> Range(ByteRange(0, 10)) ~> {
        (conditional(tag, timestamp) & optionalHeaderValueByType[Range]()) { echoComplete }
      } ~> check {
        status === OK
        responseAs[String] === "None"
      }
    }

    "not filter out a `Range` header if `If-Range` does match the ETag" in {
      Get() ~> `If-Range`(tag) ~> Range(ByteRange(0, 10)) ~> {
        (conditional(tag, timestamp) & optionalHeaderValueByType[Range]()) { echoComplete }
      } ~> check {
        status === OK
        responseAs[String] must startWith("Some")
      }
    }

    "filter out a `Range` header if `If-Range` doesn't match the ETag" in {
      Get() ~> `If-Range`(EntityTag("other")) ~> Range(ByteRange(0, 10)) ~> {
        (conditional(tag, timestamp) & optionalHeaderValueByType[Range]()) { echoComplete }
      } ~> check {
        status === OK
        responseAs[String] === "None"
      }
    }
  }

}
