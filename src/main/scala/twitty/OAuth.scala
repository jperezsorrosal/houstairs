package twitty

import java.net.URLEncoder
import java.nio.charset.Charset
import javax.crypto

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpRequest, MediaTypes}
import akka.parboiled2.util.Base64
import akka.util.ByteString

import scala.collection.immutable.TreeMap



object OAuth {
  case class Consumer(key: String, secret: String)
  case class Token(value: String, secret: String)

  def oAuthAuthorizer(consumer: Consumer, token: Token, params: Map[String, String] ) : HttpRequest => HttpRequest = {
    // construct the key and cryptographic entity
    val SHA1 = "HmacSHA1"
    val keyString = percentEncode(consumer.secret :: token.secret :: Nil)
    val key = new crypto.spec.SecretKeySpec(bytes(keyString), SHA1)
    val mac = crypto.Mac.getInstance(SHA1)

    def oAuthHelper(httpRequest: HttpRequest): HttpRequest = {
      val timestamp = (System.currentTimeMillis / 1000).toString
      // nonce is unique enough for our purposes here
      val nonce = System.nanoTime.toString

      // pick out x-www-form-urlencoded body
      val (requestParams, newEntity) = httpRequest.entity match {
        case HttpEntity.Strict(ContentType(MediaTypes.`application/x-www-form-urlencoded`, _), data: ByteString) =>
          val params = data.toString().split("&")
          val pairs = params.map { param =>
            val p = param.split("=")
            p(0) -> percentEncode(p(1))
          }
          (pairs.toMap, HttpEntity(ContentType(MediaTypes.`application/x-www-form-urlencoded`), "%s=%s" format (pairs(0)._1, pairs(0)._2)))
        case e => (Map(), e)
      }

      // prepare the OAuth parameters
      val oauthParams = Map(
        "oauth_consumer_key" -> consumer.key,
        "oauth_signature_method" -> "HMAC-SHA1",
        "oauth_timestamp" -> timestamp,
        "oauth_nonce" -> nonce,
        "oauth_token" -> token.value,
        "oauth_version" -> "1.0"
      )

      println(oauthParams)
      println(params)

      // construct parts of the signature base string
      val encodedOrderedParams = (TreeMap[String, String]() ++ params ++ oauthParams ++ requestParams) map { case (k, v) => k + "=" + v } mkString "&"
      val url = httpRequest.uri.toString()
      httpRequest.uri.withPath(httpRequest.uri.path + "?" + params.mkString("&"))

      // construct the signature base string
      val signatureBaseString = percentEncode(httpRequest.method.name :: url :: encodedOrderedParams :: Nil)
      println("Http method: " + httpRequest.method.name)
      println("Signature Base String: '" + signatureBaseString + "'")

      mac.init(key)
      val sig = Base64.rfc2045().encodeToString(mac.doFinal(bytes(signatureBaseString)), false)
      mac.reset()

      val oauth = TreeMap[String, String]() ++ (oauthParams + ("oauth_signature" -> percentEncode(sig))) map { case (k, v) => "%s=\"%s\"" format (k, v) } mkString ", "

      // return the signed request
      httpRequest.withHeaders(List(RawHeader("Authorization", "OAuth " + oauth))).withEntity(newEntity)
    }

    oAuthHelper
  }

  private def percentEncode(str: String): String = URLEncoder.encode(str, "UTF-8") replace ("+", "%20") replace ("%7E", "~")
  private def percentEncode(s: Seq[String]): String = s map percentEncode mkString "&"
  private def bytes(str: String) = str.getBytes(Charset.forName("UTF-8"))

}
