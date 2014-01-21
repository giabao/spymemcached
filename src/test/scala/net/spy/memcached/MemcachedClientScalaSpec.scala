package net.spy.memcached

import org.scalatest.FlatSpec
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.Matchers._
import java.net.InetSocketAddress
import scala.concurrent.duration._
import scala.concurrent._
import scala.util.Left
import scala.concurrent.ExecutionContext.Implicits.global

class MemcachedClientScalaSpec extends FlatSpec {
  val client = new MemcachedClientScala(new InetSocketAddress("127.0.0.1", 11211))

  "MemcachedClientScala" should "scalaGet" in {
    client.set("t1", 60, "v1").get
    whenReady(client.scalaGet("t1")){s =>
      s shouldEqual "v1"
    }
    val f = client.scalaGet("t_not_exist")
    Await.ready(f, 100.millis)
    f.eitherValue match {
      case Some(Left(e)) if e.getMessage == "NOT_FOUND" =>
      case _ => fail()
    }
  }
  
  it should "scalaGetBulk" in {
    val keys = 1 to 100 map ("k" + _)
    val fset = Future.traverse(keys){k =>
      Future(client.set(k, 60, "v" + k).get)
    }
    Await.ready(fset, 30.seconds)

    val fget = client.scalaGetBulk(keys)
    Await.ready(fget, 60.seconds)

    fget.value should not be empty
    fget.value.get.isSuccess shouldBe true
    fget.value.get.get should have size keys.length
  }

  it should "shutdown" in {
    client.shutdown()
  }
}
