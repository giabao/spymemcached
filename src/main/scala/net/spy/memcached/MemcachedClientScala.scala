package net.spy.memcached

import java.net.InetSocketAddress
import scala.collection.JavaConverters._
import net.spy.memcached.compat.log.LoggerFactory

//TODO dont use TranscodeService & ExecutorService
//We need pass null as TranscodeService & ExecutorService params to MemcachedClient's Ctor
//when finished migrate the client from java Future to scala Future
//Then, we will override & add @deprecated annotation to some methods in MemcachedClient
class MemcachedClientScala(cf: ConnectionFactory, addrs: Seq[InetSocketAddress])
  extends MemcachedClient(cf, addrs.asJava) //, null, null)
  with GetImpl with GetBulkImpl {

  def this(ia: InetSocketAddress*) = this(new DefaultConnectionFactory, ia)

  //We can't use net.spy.memcached.compat.SpyObject.getLogger because it's protected
  // => prevent using from the XxImpl traits
  protected lazy val logger = LoggerFactory.getLogger(this.getClass)
}

