package stanford.infolab.joins.gj

import scala.collection.mutable.ArrayBuffer
import org.apache.spark.Logging

/**
 * This is a columnar-like representation of a set of tables for a particular query.
 * Assume there are m different relations. R1, ..., Rm, where Ri=(A_i1, ..., A_ik). Each
 * Ri is partitioned by its first attribute A_i1, which we also assume is the smallest index
 * amongst A_i1, ..., A_ik. We store all relations inside a single partition.
 * 
 * As a running example, assume we have 3 relations R1(A_1, A_2, A_4), R2(A2, A3), and R3(A1, A3).
 * R1 = { (1, 12, 13), (1, 10, 11), (2, 20, 21) }
 * R2 = { (1, 20), (10, 22) }
 * R3 = { (2, 30), (3, 31) }
 * 
 * A RelationPartition contains the following:
 *  <ul>
 *    <li> Keys: Sorted list of distinct A_i1 values across all R_i. In our example: [1, 2, 3, 10]
 *    <li> Values: For each key in Keys, contains the values of key in each relation it appears
 *         in a consecutive fashion. The values are first sorted by the relation ID, and within each
 *         relation, the values are sorted by the first attribute. 
 *         In our example: [10 11 12 13 20 20 21 30 31 22]:
 *         <ul>
 *           <li> We first store the values of key 1 in R1 [10, 11, 12, 13], where 10 11 appears
 *           before 12 13 because 10 < 12, the first attributes of value for key 1 in relation 1.
 *           This is followed by key 1's values in R2 = empty, then R3 = [20].
 *           <li> Then we store key 2's attributes in R1 = 20 21, then in R2 = empty, then R3 = [30].
 *           <li> Then we store key 3's attributes in R1 = empty, R2 = empty, then R3 = [31].
 *           <li> Finally we store key 10's attributes in R1 = empty, R2 = [22], then R3 = empty.
 *         </ul>
 *    <li> IndividualOffsets: Contains the individualOffsets of the non-empty values of each key
 *         in each relation in Values. In our example: [0, 4, 5, 7, 8, 9]. Key 1's R1 starts at 0 and ends at 4.
 *    <li> keyBeginningOffsets : For each key k, stores the number of relations that have k as
 *         its key. In our example: [0, 2, 4, 5] b/c key 1 has values in 2 relations: R1, R2;
 *         key 2 has values in 2 relations R1, R3; key 3 has values in 1 relation R3; and finally
 *         key 10 has values in 1 relation R2.
 *    <li> RelIDsForEachKey : For each key k, stores the relation IDs that have k as its key
 *         consecutively. In our example: [R1, R2, R1, R3, R3, R2] since for example key 1 has
 *         values in R1, R2; key 2 has values in R1, R3; key 3 has values in R3; and key 10 has
 *         values in R10.
 *  </ul>
 */
class RelationPartition(val schemas: Array[(Byte, Byte)], val keys: ArrayBuffer[Int], 
  val values: ArrayBuffer[Int], val individualOffsets: ArrayBuffer[Int], val keyBeginningOffsets: ArrayBuffer[Int],
  val relIDsForEachKey: ArrayBuffer[Byte]) extends Logging with Serializable {
  
  def getCountOffer(attributeVal: Int, relID: Byte): Int = {
    val (beginIndOffset, endIndOffset) = getOffsetIndicesForAttributeRelationID(attributeVal, relID)
    return endIndOffset - beginIndOffset
  }
  
  def getOffsetIndicesForAttributeRelationID(attributeVal: Int, relID: Byte): (Int, Int) = {
    val index = binarySearchIndexWithValue(keys, attributeVal)
    log.debug("index: " + index)
    if (index < 0) {
      log.debug("incdex for attributeVal NOT found. attributeVal: " + attributeVal)
      return (-1, -1)
    } else {
      log.debug("key(index): " + keys(index) + " is attributeVal: " + attributeVal)
      val keyBeginningOffset = keyBeginningOffsets(index)
      val keyEndOffset = keyBeginningOffsets(index + 1)
      log.debug("keyBeginningOffset: " + keyBeginningOffset + " keyEndOffset: " + keyEndOffset)
      for (i <- keyBeginningOffset to keyEndOffset - 1) {
        log.debug("i: " + i)
        if (relIDsForEachKey(i) == relID) {
          log.debug("FOUND!! attributeVal and relation.returning (" + individualOffsets(i) + ", "
            + individualOffsets(i+1) + ")")
          return (individualOffsets(i), individualOffsets(i+1))
        }
      }
      (-1, -1)
    }
  }
  
  
  def binarySearchIndexWithValue(array: ArrayBuffer[Int], value: Int): Int = {
    var l = 0;
    var h = array.length - 1;
    var m = -1;
    while (l <= h) {
      m = (h + l)/2
      if (array(m) < value) l = m+1
      else if (array(m) > value) h = m-1
      else return m
    }
    -1
  }

  override def toString(): String = {
    var retVal = if (schemas == null) "schemas: null" else "schemas: [" + schemas.mkString(" ") + "]";
    retVal += "\nkeys: [" + keys.mkString(" ") + "]"
    retVal += "\nvalues: [" + values.mkString(" ") + "]"
    retVal += "\nindividualOffsets: [" + individualOffsets.mkString(" ") + "]"
    retVal += "\nkeyBeginningOffsets: [" + keyBeginningOffsets.mkString(" ") + "]"
    retVal += "\nrelIDsForEachKey: [" + relIDsForEachKey.mkString(" ") + "]"
    retVal
  }
}

object RelationPartitionFactory {

  // Warning/Known Limitation: Currently we only handle binary relations. Therefore for each
  // relation we expect only one value corresponding to a key. That is why the input to
  // apply is Seq[(Int, Byte, Int)]. When we extend this with GJ + DYS, we will need
  // to change this signature to Seq[(Int, Byte, Array[Int])] or have another apply
  // that handles multiple values.
  def apply(schemas: Array[(Byte, Byte)], keyRelIDValues: Seq[(Int, Byte, Int)]):
    RelationPartition = {
    val numValues = keyRelIDValues.size
    val krvGroupedByK = keyRelIDValues.groupBy(krv => krv._1)
    val numKeys = krvGroupedByK.keys.size
    var sortedKeys = new ArrayBuffer[Int](numKeys)
    sortedKeys.appendAll(krvGroupedByK.keys)
    sortedKeys = sortedKeys.sortWith(_ < _)
    val values = new ArrayBuffer[Int](numValues)
    // individualOffsets have size at least numKeys, but possibly as much as m*numKeys
    val individualOffsets = new ArrayBuffer[Int](numKeys)
    // keyBeginningOffsets implicitly holds where the first offset of each key starts.
    // Each key can exist in multiple relations, therefore can have multiple offsets
    val keyBeginningOffsets =  new ArrayBuffer[Int](numKeys)
    // Similar to individualOffsets, relIDsForEachKey has size at least numKeys, but possibly
    // as much as m*numKeys
    val relIDsForEachKey =  new ArrayBuffer[Byte](numKeys)
    keyBeginningOffsets += 0
    sortedKeys.foreach { key =>
      val relIDVals = krvGroupedByK(key).map(krv => (krv._2, krv._3))
      val relIDValsGroupedByRelID = relIDVals.groupBy(relIDVal => relIDVal._1)
      var sortedRelIDs = new ArrayBuffer[Byte](relIDValsGroupedByRelID.keys.size)
      sortedRelIDs.appendAll(relIDValsGroupedByRelID.keys)
      sortedRelIDs = sortedRelIDs.sortWith(_ < _)
      keyBeginningOffsets += keyBeginningOffsets.last + sortedRelIDs.size
      sortedRelIDs.foreach { relID =>
        relIDsForEachKey += relID
        // offset for this key's relID is current size of the values array
        individualOffsets += values.size
        values.appendAll(relIDValsGroupedByRelID(relID).map(a => a._2).sortWith(_ < _))
      }
    }
    // WARNING: POSSIBLE CAUSE FOR A BUG: We add an extra offset with the final size of the
    // values array so that users of RelationPartition do not have to special case the edge case of
    // the last key's last offset. In order to find the number of values for the last key's last
    // offset, users can simply access individualOffsets(t+1)-individualOffsets(t) where t is the
    // number of tuples.
    individualOffsets += values.size

    new RelationPartition(schemas, sortedKeys, values, individualOffsets,
      keyBeginningOffsets, relIDsForEachKey)
  }  
}