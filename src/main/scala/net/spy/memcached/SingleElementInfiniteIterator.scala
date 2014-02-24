package net.spy.memcached

final class SingleElementInfiniteIterator[T](t: T) extends Iterator[T]{
  override def next() = t
  override def hasNext = true
}
