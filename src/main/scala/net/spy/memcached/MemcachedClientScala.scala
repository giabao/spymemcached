package net.spy.memcached

import java.net.InetSocketAddress
import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.{Promise, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import net.spy.memcached.ops.{Operation, OperationState, OperationStatus, GetOperation}
import net.spy.memcached.transcoders.Transcoder
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try
import net.spy.memcached.util.StringUtils
import net.spy.memcached.protocol.binary.BinaryOperationFactory
import net.spy.memcached.compat.log.LoggerFactory

//TODO dont use TranscodeService & ExecutorService
//We need pass nuss as TranscodeService & ExecutorService params to MemcachedClient's Ctor
//when finished migrate the client from java Future to scala Future
//Then, we will override & add @deprecated annotation to some methods in MemcachedClient
class MemcachedClientScala(cf: ConnectionFactory, addrs: Seq[InetSocketAddress])
  extends MemcachedClient(cf, addrs.asJava) //, null, null)
  with GetImpl with GetBulkImpl {

  def this(ia: InetSocketAddress*) = this(new DefaultConnectionFactory, ia)

  protected lazy val logger = LoggerFactory.getLogger(this.getClass)
}

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
        //assert ! tcP.isCompleted
        val cachedData = new CachedData(flags, data, tc.getMaxSize)
        if(tc.asyncDecode(cachedData))
          tcP completeWith Future[T](tc.decode(cachedData))
        else
          tcP complete Try(tc.decode(cachedData))
      }
      override def complete(){
        if(retP.isCompleted) return
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

private[memcached] trait GetBulkImpl {this: MemcachedClientScala =>
  def scalaGetBulk(keys: Seq[String]): Future[Map[String, AnyRef]] = scalaGetBulk(keys, transcoder)

  def scalaGetBulk[T](keys: Seq[String], tc: Transcoder[T]): Future[Map[String, T]] =
    scalaGetBulk(keys, new SingleElementInfiniteIterator(tc).toIterable)

  //This method is migrated from MemcachedClient.asyncGetBulk(Iterator<String>, Iterator<Transcoder<T>>)
  def scalaGetBulk[T](keys: Seq[String], tcIter: Iterable[Transcoder[T]]): Future[Map[String, T]] = {
    keys.foreach(StringUtils.validateKey(_, opFact.isInstanceOf[BinaryOperationFactory]))

    val retP = Promise[Map[String, T]]() //result promise
    val tcPs = keys.map((_, Promise[T]())).toMap //transcoder map key -> promise
    val tcMap = keys.zip(tcIter).toMap
    assert(tcMap.size == keys.size, "not provide enough transcoder for keys")

    val locator = mconn.getLocator
    // Break the gets down into groups by key
    // This map does not need to be a ConcurrentHashMap because it is fully populated when it is used and used read-only
    val chunks = mutable.Map.empty[MemcachedNode, mutable.MutableList[String]]
    keys.foreach {key =>
      val primaryNode = locator.getPrimary(key)
      val node: MemcachedNode =
        if (primaryNode.isActive)
          primaryNode
        else
          locator.getSequence(key).asScala.
            find(n => n.isActive).
            getOrElse(primaryNode)
      assert(node != null, "Didn't find a node for " + key)
      val ks = chunks.get(node).getOrElse{
        val l = mutable.MutableList.empty[String]
        chunks(node) = l
        l
      }
      ks += key
    }

    val sref = new AtomicReference[OperationStatus](null) //status ref

    val cb = new GetOperation.Callback(){
      override def receivedStatus(status: OperationStatus){
        sref.set(status)
        logger.debug("receivedStatus: %s", status)
      }
      override def gotData(k: String, flags: Int, data: Array[Byte]){
        logger.debug("gotData: %s, %d, %d", k, Int.box(flags), Int.box(data.length))
        val tc = tcMap(k)
        val p = tcPs(k)
        //assert ! p.isCompleted
        val cachedData = new CachedData(flags, data, tc.getMaxSize)
        if(tc.asyncDecode(cachedData))
          p completeWith Future[T](tc.decode(cachedData))
        else
          p complete Try(tc.decode(cachedData))
      }
      override def complete(){
        if(retP.isCompleted) return
        val status = sref.get
        if(status != null && status.isSuccess){
          retP completeWith Future.
            traverse(keys)(tcPs(_).future).
            map(tSeq => keys.zip(tSeq).toMap)
        }else{
          val msg = if(status == null) "Cant get status" else status.getMessage
          retP failure new Exception(msg)
        }
        logger.debug("complete: %s", keys)
      }
    }

    // Now that we know how many servers it breaks down into, and the latch
    // is all set up, convert all of these strings collections to operations
    val mops: Map[MemcachedNode, Operation] =
      chunks.map{case (node, keyList) =>
        (node, opFact.get(keyList.asJavaCollection, cb))
      }.toMap

    mconn.checkState()
    mconn.addOperations(mops.asJava)

    //@see [[net.spy.memcached.internal.BulkGetFuture.internalGet]]
    //but in a reverse order :D
    retP.future.andThen{case _ =>
      mops.valuesIterator.foreach{op =>
        if(op.getState != OperationState.COMPLETE) MemcachedConnection.opTimedOut(op)
        else MemcachedConnection.opSucceeded(op)
      }
    }.transform(x => x, e => mops.valuesIterator.find(_.hasErrored).map(_.getException).getOrElse(e))
  }
}

class SingleElementInfiniteIterator[T](t: T) extends Iterator[T]{
  override def next() = t
  override def hasNext = true
}
