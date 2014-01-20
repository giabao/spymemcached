package net.spy.memcached

import java.net.InetSocketAddress
import scala.collection.JavaConverters._
import scala.concurrent.{Promise, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import net.spy.memcached.ops.{OperationState, OperationStatus, GetOperation}
import net.spy.memcached.transcoders.Transcoder
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

//dont use TranscodeService & ExecutorService
class MemcachedClientScala(cf: ConnectionFactory, addrs: Seq[InetSocketAddress])
  extends MemcachedClient(cf, addrs.asJava, null, null){

  def this(ia: InetSocketAddress*) = this(new DefaultConnectionFactory, ia)

  def scalaGet(key: String): Future[AnyRef] = scalaGet(key, transcoder)

  def scalaGet[T](key: String, tc: Transcoder[T]): Future[T] = {
    val p = Promise[T]()
    val p2 = Promise[T]()
    val sref = new AtomicReference[OperationStatus](null)
    val op = opFact.get(key, new GetOperation.Callback(){
      override def receivedStatus(status: OperationStatus){
        sref.set(status)
      }
      override def gotData(k: String, flags: Int, data: Array[Byte]){
        assert(key == k, "Wrong key returned")
        val cachedData = new CachedData(flags, data, tc.getMaxSize)
        if(tc.asyncDecode(cachedData))
          p completeWith Future[T](tc.decode(cachedData))
        else
          p complete Try(tc.decode(cachedData))
      }
      override def complete(){
        if(p2.isCompleted) return
        val status = sref.get
        if(status != null && status.isSuccess){
          p2 completeWith p.future
        }else{
          val msg = if(status == null) "Cant get status" else status.getMessage
          p2 failure new Exception(msg)
        }
      }
    })

    mconn.enqueueOperation(key, op)

    //@see [[net.spy.memcached.internal.OperationFuture.get(long, java.util.concurrent.TimeUnit)]]
    //hơi ngược tí :D
    p2.future.andThen{case _ =>
      if(op.getState != OperationState.COMPLETE) MemcachedConnection.opTimedOut(op)
      else MemcachedConnection.opSucceeded(op)
    }.transform(identity[T], e => if(op.hasErrored) op.getException else e)
  }
}
