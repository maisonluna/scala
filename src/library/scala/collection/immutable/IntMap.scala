package scala.collection.immutable;

/**
 * @author David MacIver
 */
private[immutable] object IntMapUtils{
  def zero(i : Int, mask : Int) = (i & mask) == 0;
  def mask(i : Int, mask : Int) = i & (complement(mask - 1) ^ mask)
  def hasMatch(key : Int, prefix : Int, m : Int) = mask(key, m) == prefix;
  def unsignedCompare(i : Int, j : Int) = (i < j) ^ (i < 0) ^ (j < 0)
  def shorter(m1 : Int, m2 : Int) = unsignedCompare(m2, m1)
  def complement(i : Int) = (-1) ^ i;
  def highestOneBit(j : Int) = {
    var i = j;
    i |= (i >>  1);
    i |= (i >>  2);
    i |= (i >>  4);
    i |= (i >>  8);
    i |= (i >> 16);
    i - (i >>> 1);
  }

  def branchMask(i : Int, j : Int) = highestOneBit(i ^ j);

  def join[T](p1 : Int, t1 : IntMap[T], p2 : Int, t2 : IntMap[T]) : IntMap[T] = {
    val m = branchMask(p1, p2);
    val p = mask(p1, m);
    if (zero(p1, m)) IntMap.Bin(p, m, t1, t2)
    else IntMap.Bin(p, m, t2, t1);
  }

  def bin[T](prefix : Int, mask : Int, left : IntMap[T], right : IntMap[T]) : IntMap[T] = (left, right) match {
    case (left, IntMap.Nil) => left;
    case (IntMap.Nil, right) => right;
    case (left, right) => IntMap.Bin(prefix, mask, left, right);
  }
}

import IntMapUtils._

object IntMap{
  def empty[T] : IntMap[T]  = IntMap.Nil;
  def singleton[T](key : Int, value : T) : IntMap[T] = IntMap.Tip(key, value);
  def apply[T](elems : (Int, T)*) : IntMap[T] =
    elems.foldLeft(empty[T])((x, y) => x.updated(y._1, y._2));


  private[immutable] case object Nil extends IntMap[Nothing]{
    override def equals(that : Any) = that match {
      case (that : AnyRef) if (this eq that) => true;
      case (that : IntMap[_]) => false; // The only empty IntMaps are eq Nil
      case that => super.equals(that);
    }
  };

  private[immutable] case class Tip[+T](key : Int, value : T) extends IntMap[T]{
    def withValue[S](s : S) =
      if (s.asInstanceOf[AnyRef] eq value.asInstanceOf[AnyRef]) this.asInstanceOf[IntMap.Tip[S]];
      else IntMap.Tip(key, s);
  }
  private[immutable] case class Bin[+T](prefix : Int, mask : Int, left : IntMap[T], right : IntMap[T]) extends IntMap[T]{
    def bin[S](left : IntMap[S], right : IntMap[S]) : IntMap[S] = {
      if ((this.left eq left) && (this.right eq right)) this.asInstanceOf[IntMap.Bin[S]];
      else IntMap.Bin[S](prefix, mask, left, right);
    }
  }

}

import IntMap._

// Iterator over a non-empty IntMap.
private[immutable] abstract class IntMapIterator[V, T](it : IntMap[V]) extends Iterator[T]{

  // Basically this uses a simple stack to emulate conversion over the tree. However
  // because we know that Ints are at least 32 bits we can have at most 32 IntMap.Bins and
  // one IntMap.Tip sitting on the tree at any point. Therefore we know the maximum stack
  // depth is 33 and
  var index = 0;
  var buffer = new Array[AnyRef](33);

  def pop = {
    index -= 1;
    buffer(index).asInstanceOf[IntMap[V]];
  }

  def push(x : IntMap[V]) {
    buffer(index) = x.asInstanceOf[AnyRef];
    index += 1;
  }
  push(it);

  /**
   * What value do we assign to a tip?
   */
  def valueOf(tip : IntMap.Tip[V]) : T;

  def hasNext = index != 0;
  final def next : T =
    pop match {
      case IntMap.Bin(_,_, t@IntMap.Tip(_, _), right) => {
        push(right);
        valueOf(t);
      }
      case IntMap.Bin(_, _, left, right) => {
        push(right);
        push(left);
        next;
      }
      case t@IntMap.Tip(_, _) => valueOf(t);
      // This should never happen. We don't allow IntMap.Nil in subtrees of the IntMap
      // and don't return an IntMapIterator for IntMap.Nil.
      case IntMap.Nil => error("Empty maps not allowed as subtrees");
    }
}

private[immutable] class IntMapEntryIterator[V](it : IntMap[V]) extends IntMapIterator[V, (Int, V)](it){
  def valueOf(tip : IntMap.Tip[V]) = (tip.key, tip.value);
}

private[immutable] class IntMapValueIterator[V](it : IntMap[V]) extends IntMapIterator[V, V](it){
  def valueOf(tip : IntMap.Tip[V]) = tip.value;
}

private[immutable] class IntMapKeyIterator[V](it : IntMap[V]) extends IntMapIterator[V, Int](it){
  def valueOf(tip : IntMap.Tip[V]) = tip.key;
}

import IntMap._;

/**
 * Specialised immutable map structure for integer keys, based on
 * <a href="http://citeseer.ist.psu.edu/okasaki98fast.html">Fast Mergeable Integer Maps</a>
 * by Okasaki and Gill. Essentially a trie based on binary digits of the the integers.
 */
sealed abstract class IntMap[+T] extends scala.collection.immutable.Map[Int, T] with scala.collection.generic.ImmutableMapTemplate[Int, T, IntMap[T]] {
  override def empty: IntMap[T] = IntMap.Nil;

  override def toList = {
    val buffer = new scala.collection.mutable.ListBuffer[(Int, T)];
    foreach(buffer += _);
    buffer.toList;
  }

  /**
   * Iterator over key, value pairs of the map in unsigned order of the keys.
   */
  def elements : Iterator[(Int, T)] = this match {
    case IntMap.Nil => Iterator.empty;
    case _ => new IntMapEntryIterator(this);
  }

  /**
   * Loops over the key, value pairs of the map in unsigned order of the keys.
   */
  override final def foreach[U](f : ((Int, T)) =>  U) : Unit = this match {
    case IntMap.Bin(_, _, left, right) => {left.foreach(f); right.foreach(f); }
    case IntMap.Tip(key, value) => f((key, value));
    case IntMap.Nil => {};
  }

  override def keys : Iterator[Int] = this match {
    case IntMap.Nil => Iterator.empty;
    case _ => new IntMapKeyIterator(this);
  }

  /**
   * Loop over the keys of the map. The same as keys.foreach(f), but may
   * be more efficient.
   *
   * @param f The loop body
   */
  final def foreachKey(f : Int => Unit) : Unit = this match {
    case IntMap.Bin(_, _, left, right) => {left.foreachKey(f); right.foreachKey(f); }
    case IntMap.Tip(key, _) => f(key);
    case IntMap.Nil => {}
  }

  override def values : Iterator[T] = this match {
    case IntMap.Nil => Iterator.empty;
    case _ => new IntMapValueIterator(this);
  }

  /**
   * Loop over the keys of the map. The same as keys.foreach(f), but may
   * be more efficient.
   *
   * @param f The loop body
   */
  final def foreachValue(f : T => Unit) : Unit = this match {
    case IntMap.Bin(_, _, left, right) => {left.foreachValue(f); right.foreachValue(f); }
    case IntMap.Tip(_, value) => f(value);
    case IntMap.Nil => {};
  }

  override def stringPrefix = "IntMap"

  override def isEmpty = this == IntMap.Nil;

  override def filter(f : ((Int, T)) => Boolean) : IntMap[T] = this match {
    case IntMap.Bin(prefix, mask, left, right) => {
      val (newleft, newright) = (left.filter(f), right.filter(f));
      if ((left eq newleft) && (right eq newright)) this;
      else bin(prefix, mask, newleft, newright);
    }
    case IntMap.Tip(key, value) =>
      if (f((key, value))) this
      else IntMap.Nil;
    case IntMap.Nil => IntMap.Nil;
  }

  def transform[S](f : (Int, T) => S) : IntMap[S] = this match {
    case b@IntMap.Bin(prefix, mask, left, right) => b.bin(left.transform(f), right.transform(f));
    case t@IntMap.Tip(key, value) => t.withValue(f(key, value));
    case IntMap.Nil => IntMap.Nil;
  }

  final override def size : Int = this match {
    case IntMap.Nil => 0;
    case IntMap.Tip(_, _) => 1;
    case IntMap.Bin(_, _, left, right) => left.size + right.size;
  }

  final def get(key : Int) : Option[T] = this match {
    case IntMap.Bin(prefix, mask, left, right) => if (zero(key, mask)) left.get(key) else right.get(key);
    case IntMap.Tip(key2, value) => if (key == key2) Some(value) else None;
    case IntMap.Nil => None;
  }

  final override def getOrElse[S >: T](key : Int, default : =>S) : S = this match {
    case IntMap.Nil => default;
    case IntMap.Tip(key2, value) => if (key == key2) value else default;
    case IntMap.Bin(prefix, mask, left, right) => if (zero(key, mask)) left.getOrElse(key, default) else right.getOrElse(key, default);
  }

  final override def apply(key : Int) : T = this match {
    case IntMap.Bin(prefix, mask, left, right) => if (zero(key, mask)) left(key) else right(key);
    case IntMap.Tip(key2, value) => if (key == key2) value else error("Key not found");
    case IntMap.Nil => error("key not found");
  }

  def + [S >: T] (kv: (Int, S)): IntMap[S] = updated(kv._1, kv._2)

  override def updated[S >: T](key : Int, value : S) : IntMap[S] = this match {
    case IntMap.Bin(prefix, mask, left, right) => if (!hasMatch(key, prefix, mask)) join(key, IntMap.Tip(key, value), prefix, this);
                                          else if (zero(key, mask)) IntMap.Bin(prefix, mask, left.updated(key, value), right)
                                          else IntMap.Bin(prefix, mask, left, right.updated(key, value));
    case IntMap.Tip(key2, value2) => if (key == key2) IntMap.Tip(key, value);
                             else join(key, IntMap.Tip(key, value), key2, this);
    case IntMap.Nil => IntMap.Tip(key, value);
  }

  /** @deprecated use updated instead */
  @deprecated override def update[S >: T](key: Int, value: S): IntMap[S] = updated(key, value)

  /**
   * Updates the map, using the provided function to resolve conflicts if the key is already present.
   * Equivalent to
   * <pre>this.get(key) match {
   *         case None => this.update(key, value);
   *         case Some(oldvalue) => this.update(key, f(oldvalue, value) }
   * </pre>
   *
   * @param key The key to update
   * @param value The value to use if there is no conflict
   * @param f The function used to resolve conflicts.
   */
  def updateWith[S >: T](key : Int, value : S, f : (T, S) => S) : IntMap[S] = this match {
    case IntMap.Bin(prefix, mask, left, right) => if (!hasMatch(key, prefix, mask)) join(key, IntMap.Tip(key, value), prefix, this);
                                          else if (zero(key, mask)) IntMap.Bin(prefix, mask, left.updateWith(key, value, f), right)
                                          else IntMap.Bin(prefix, mask, left, right.updateWith(key, value, f));
    case IntMap.Tip(key2, value2) => if (key == key2) IntMap.Tip(key, f(value2, value));
                             else join(key, IntMap.Tip(key, value), key2, this);
    case IntMap.Nil => IntMap.Tip(key, value);
  }

  def - (key : Int) : IntMap[T] = this match {
    case IntMap.Bin(prefix, mask, left, right) =>
      if (!hasMatch(key, prefix, mask)) this;
      else if (zero(key, mask)) bin(prefix, mask, left - key, right);
      else bin(prefix, mask, left, right - key);
    case IntMap.Tip(key2, _) =>
      if (key == key2) IntMap.Nil;
      else this;
    case IntMap.Nil => IntMap.Nil;
  }

  /**
   * A combined transform and filter function. Returns an IntMap such that for each (key, value) mapping
   * in this map, if f(key, value) == None the map contains no mapping for key, and if <code>f(key, value)
   *
   * @param f The transforming function.
   */
  def modifyOrRemove[S](f : (Int, T) => Option[S]) : IntMap[S] = this match {
      case IntMap.Bin(prefix, mask, left, right) => {
        val newleft = left.modifyOrRemove(f);
        val newright = right.modifyOrRemove(f);
        if ((left eq newleft) && (right eq newright)) this.asInstanceOf[IntMap[S]];
        else bin(prefix, mask, newleft, newright)
      }
    case IntMap.Tip(key, value) => f(key, value) match {
      case None => IntMap.Nil;
      case Some(value2) =>
        //hack to preserve sharing
        if (value.asInstanceOf[AnyRef] eq value2.asInstanceOf[AnyRef]) this.asInstanceOf[IntMap[S]]
        else IntMap.Tip(key, value2);
      }
    case IntMap.Nil => IntMap.Nil;
  }


  /**
   * Forms a union map with that map, using the combining function to resolve conflicts.
   *
   * @param that the map to form a union with.
   * @param f the function used to resolve conflicts between two mappings.
   */
  def unionWith[S >: T](that : IntMap[S], f : (Int, S, S) => S) : IntMap[S] = (this, that) match{
    case (IntMap.Bin(p1, m1, l1, r1), that@(IntMap.Bin(p2, m2, l2, r2))) =>
      if (shorter(m1, m2)) {
        if (!hasMatch(p2, p1, m1)) join(p1, this, p2, that);
        else if (zero(p2, m1)) IntMap.Bin(p1, m1, l1.unionWith(that, f), r1);
        else IntMap.Bin(p1, m1, l1, r1.unionWith(that, f));
      } else if (shorter(m2, m1)){
        if (!hasMatch(p1, p2, m2)) join(p1, this, p2, that);
        else if (zero(p1, m2)) IntMap.Bin(p2, m2, this.unionWith(l2, f), r2);
        else IntMap.Bin(p2, m2, l2, this.unionWith(r2, f));
      }
      else {
        if (p1 == p2) IntMap.Bin(p1, m1, l1.unionWith(l2,f), r1.unionWith(r2, f));
        else join(p1, this, p2, that);
      }
    case (IntMap.Tip(key, value), x) => x.updateWith(key, value, (x, y) => f(key, y, x));
    case (x, IntMap.Tip(key, value)) => x.updateWith[S](key, value, (x, y) => f(key, x, y));
    case (IntMap.Nil, x) => x;
    case (x, IntMap.Nil) => x;
  }

  /**
   * Forms the intersection of these two maps with a combinining function. The resulting map is
   * a map that has only keys present in both maps and has values produced from the original mappings
   * by combining them with f.
   *
   * @param that The map to intersect with.
   * @param f The combining function.
   */
  def intersectionWith[S, R](that : IntMap[S], f : (Int, T, S) => R) : IntMap[R] = (this, that) match {
    case (IntMap.Bin(p1, m1, l1, r1), that@IntMap.Bin(p2, m2, l2, r2)) =>
      if (shorter(m1, m2)) {
        if (!hasMatch(p2, p1, m1)) IntMap.Nil;
        else if (zero(p2, m1)) l1.intersectionWith(that, f);
        else r1.intersectionWith(that, f);
      } else if (m1 == m2) bin(p1, m1, l1.intersectionWith(l2, f), r1.intersectionWith(r2, f));
        else {
        if (!hasMatch(p1, p2, m2)) IntMap.Nil;
        else if (zero(p1, m2)) this.intersectionWith(l2, f);
        else this.intersectionWith(r2, f);
      }
    case (IntMap.Tip(key, value), that) => that.get(key) match {
      case None => IntMap.Nil;
      case Some(value2) => IntMap.Tip(key, f(key, value, value2));
    }
    case (_, IntMap.Tip(key, value)) => this.get(key) match {
      case None => IntMap.Nil;
      case Some(value2) => IntMap.Tip(key, f(key, value2, value));
    }
    case (_, _) => IntMap.Nil;
  }

  /**
   * Left biased intersection. Returns the map that has all the same mappings as this but only for keys
   * which are present in the other map.
   *
   * @param that The map to intersect with.
   */
  def intersection[R](that : IntMap[R]) : IntMap[T] = this.intersectionWith(that, (key : Int, value : T, value2 : R) => value);

  def ++[S >: T](that : IntMap[S]) =
    this.unionWith[S](that, (key, x, y) => y)

  /**
   * The entry with the lowest key value considered in unsigned order.
   */
  final def firstKey : Int = this match {
    case Bin(_, _, l, r) => l.firstKey;
    case Tip(k, v) => k;
    case IntMap.Nil => error("Empty set")
  }

  /**
   * The entry with the highest key value considered in unsigned order.
   */
  final def lastKey : Int = this match {
    case Bin(_, _, l, r) => r.lastKey;
    case Tip(k, v) => k;
    case IntMap.Nil => error("Empty set")
  }
}
