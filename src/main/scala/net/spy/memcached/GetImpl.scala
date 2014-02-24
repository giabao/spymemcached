package net.spy.memcached

import scala.concurrent.{Promise, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import net.spy.memcached.transcoders.Transcoder
import java.util.concurrent.atomic.AtomicReference
import net.spy.memcached.ops.{OperationState, GetOperation, OperationStatus}
import scala.util.Try

private[memcached] trait GetImpl {this: MemcachedClientScala =>
  def scalaGet(key: String): Future[AnyRef] = scalaGet(key, transcoder)

  //This method is migrated from MemcachedClient.asyncGet(String, Transcoder<T>)
  def scalaGet[T](key: String, tc: Transcoder[T]): Future[T] = {
    val retP = Promise[T]() //result promise
    val tcP = Promise[T]() //transcoder promise
    val sref = new AtomicReference[OperationStatus](null) //status ref
    val op = opFact.get(key, new GetOperation.Callback(){
        override def receivedStatus(status: OperationStatus){
          sref.set(status)
        }
        override def gotData(k: String, flags: Int, data: Array[Byte]){
          assert(key == k, "Wrong key returned")
          //we dont need the following assert because the complete & completeWith method of Promise will check that.
          //assert(! tcP.isCompleted, "received gotData multi times!")
          val cachedData = new CachedData(flags, data, tc.getMaxSize)
          if(tc.asyncDecode(cachedData))
            tcP completeWith Future[T](tc.decode(cachedData))
          else
            tcP complete Try(tc.decode(cachedData))
        }
        override def complete(){
          if(retP.isCompleted) {
            logger.warn("Get {}, received complete multi times!", key)
            return
          }
          val status = sref.get
          if(status != null && status.isSuccess){
            retP completeWith tcP.future
          }else{
            val msg = if(status == null) "Cant get status" else status.getMessage
            retP failure new Exception(msg)
          }
        }
      })

    mconn.enqueueOperation(key, op)

    //@see [[net.spy.memcached.internal.OperationFuture.get(long, java.util.concurrent.TimeUnit)]]
    //but in a reverse order :D
    retP.future.andThen{case _ =>
      if(op.getState != OperationState.COMPLETE) MemcachedConnection.opTimedOut(op)
      else MemcachedConnection.opSucceeded(op)
    }.transform(x => x, e => if(op.hasErrored) op.getException else e)
  }
}
