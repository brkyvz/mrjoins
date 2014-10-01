package stanford.infolab.joins.gj

import org.apache.spark.{HashPartitioner, SparkContext, Partitioner}
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import scala.collection.mutable.ListBuffer
import org.apache.spark.rdd.PairRDDFunctions
import org.apache.spark.storage.StorageLevel
import stanford.infolab.joins.JoinsArguments
import stanford.infolab.joins.JoinsAlgorithm
import scala.collection.mutable.ArrayBuffer

/**
 * Answers any query with the distributed Generic Join algorithm.
 * TODO(semih): Experiment with not using RelationPartition's serialization but use
 * a tuple containing each field of RelationPartition.
 */
class MemoryOptimizedGenericJoin(joinsArgs: JoinsArguments) extends BaseGenericJoin(joinsArgs) {

  override def computeQuery(sc: SparkContext): RDD[_] = {    
    val startTime = System.currentTimeMillis()
    val relations =  parseEdgesIntoRDDFromEdgeListFormat(sc)
    relations.cache()
    relations.count()
    debugRelation(relations)
    var succ = computeSUCC_A0(relations)
    debugRelation(succ)
    val n = joinsArgs.schemas.flatMap{ a => Seq(a._1, a._2) }.distinct.length
    log.debug("numAttributes: " + n)
    var nextAttribute = 1
    while (nextAttribute < n) {
      succ.cache()
      log.info("Computing next succ for attribute: " + nextAttribute)
      succ = computeNextSucc(relations, succ, nextAttribute.toByte)
      debugRelation(succ)
      nextAttribute += 1
    }
    val finalOutput = succ.flatMap(partIDTuples => {
      val tuples = partIDTuples._2
      val numTuples = tuples.length / n
      val outputs = new ArrayBuffer[(Int, ArrayBuffer[Int])](numTuples)
      var i = 0
      while (i < numTuples) {
        outputs += ((tuples(i * n), tuples.slice(i * n, (i + 1) * n)))
        i += 1
      }
      outputs
    })
    finalOutput.setName("FINAL_OUTPUT")
    debugRelation(finalOutput)
    finalOutput
  }

  def computeNextSucc(relations: RDD[(Short, RelationPartition)], 
    succ: RDD[(Short, ArrayBuffer[Int])], nextAttribute: Byte) : RDD[(Short, ArrayBuffer[Int])] = {
    // TODO(semih): Change this to forEachRelationAttributesToConditionOn(nextAttribute)
    val relationConditionedAttributesMap = findForEachRelationAttributeToCondition(nextAttribute)
    var numRelationsWithNonEmptyConditions = 0
    for (attributesToConditionOn <- relationConditionedAttributesMap) {
      if (!attributesToConditionOn.isEmpty) numRelationsWithNonEmptyConditions += 1
    }
    if (numRelationsWithNonEmptyConditions == 0) {
      throw new UnsupportedOperationException("Attribute " + nextAttribute + " is not conditioned "
        + " in any of the relations. At least one of the relations need to be conditioned on a "
        + "previous attribute.")
    }
    log.debug("numRelationsWithNonEmptyConditions: " + numRelationsWithNonEmptyConditions)
    log.debug("relationConditionedAttributesMap: " + relationConditionedAttributesMap)
    val countRequests = computeCountRequests(succ, nextAttribute, relationConditionedAttributesMap)
    debugRelation(countRequests)
    val countOffers = computeCountOffers(relations, nextAttribute, countRequests)
    debugRelation(countOffers)
    val succCountOffersCogrouped = succ.cogroup(countOffers, joinsArgs.reduceParallelism)
    succCountOffersCogrouped.setName("SUCC_COUNT_OFFERS_COGROUPED_" + nextAttribute)
    debugRelation(succCountOffersCogrouped)
    var intersections = computeFirstNullIntersections(nextAttribute,
      relationConditionedAttributesMap, succCountOffersCogrouped)
    debugRelation(intersections)
    // TODO(semih): We can run this for loop only numRelsWithCondAttributes - 1 times
    // And in the last time just return the final successful tuples after the final intersection
    var i = 0
    while (i < numRelationsWithNonEmptyConditions) {
      val relationsIntMsgsCogrouped = relations.cogroup(intersections, joinsArgs.reduceParallelism)
      relationsIntMsgsCogrouped.setName("RELATIONS_INTERSECTION_MESSAGES_COGROUPED_FOR_NEXT_ATTR_" 
        + nextAttribute + "_" + i)
      debugRelation(relationsIntMsgsCogrouped)
      intersections = computeNextIntersections(relationsIntMsgsCogrouped,
        nextAttribute, relationConditionedAttributesMap, numRelationsWithNonEmptyConditions, i)
      debugRelation(intersections)
      i += 1
    }

    // We perform a cartesian product of tuple with extensions
    val nextSucc = intersections.groupByKey(joinsArgs.numPartitions).flatMap { partIDIntersectionMsgs =>
        val partitionID = partIDIntersectionMsgs._1
        val intersectionMsgs = partIDIntersectionMsgs._2
        val nextSuccTuples = new ArrayBuffer[Int]()
        intersectionMsgs.foreach { intersectionMsg =>
          val succTuples = intersectionMsg.succTuples
          val numTuples = succTuples.length/nextAttribute
          val intersections = intersectionMsg.intersections
          val offsets = intersectionMsg.offsets
          var i = 0
          while (i < numTuples) {
            var k = offsets(i)
            val indEnd = offsets(i + 1)
            while (k < indEnd) {
              var j = 0
              while (j < nextAttribute) {
                nextSuccTuples += succTuples(i * nextAttribute + j)
                j += 1
              }
              nextSuccTuples += intersections(k)
              k += 1
            }
            i += 1
          }
        }
        Iterable((partitionID, nextSuccTuples))
    }
    nextSucc.setName(succ.name + "_A" + nextAttribute)
    debugRelation(nextSucc)
    nextSucc
  }

  private def computeNextIntersections(relationsIntMsgsCogrouped: 
    RDD[(Short, (Iterable[RelationPartition], Iterable[IntersectionMessage]))], nextAttribute: Byte,
    relationConditionedAttributesMap: ArrayBuffer[ArrayBuffer[Byte]],
    numRelationsWithNonEmptyConditions: Int, extensionRoundNo: Int): RDD[(Short, IntersectionMessage)] = {
    val intersections: RDD[(Short, IntersectionMessage)] = relationsIntMsgsCogrouped.flatMap(
      partIDPartitionIntMsgs => {
        val partitionID = partIDPartitionIntMsgs._1
        if (partIDPartitionIntMsgs._2._1.isEmpty) {
          log.debug("partition: " + partitionID + " is empty, skipping when generating next intersections")
          Seq.empty
        } else {
          val relationPartition = partIDPartitionIntMsgs._2._1.head
          val intersectionMsgs = partIDPartitionIntMsgs._2._2
          val intersectionMesssagesToEachPartition = new IntersectionMessagesToEachPartition(
            joinsArgs.numPartitions)
          var beginIntersectionOffset: Int = -1
          var endIntersectionOffset: Int = -1
          intersectionMsgs.foreach { intersectionMsg =>
            var t = 0
            val succTuples = intersectionMsg.succTuples
            var succTupleFirstAttrIndex: Int = -1
            var succTupleEndIndex: Int = -1
            val currentIntersections = intersectionMsg.intersections
            val intersectionOffsets = intersectionMsg.offsets
            val startRelationIDs = intersectionMsg.startRelationIDs
            val numTuples = succTuples.length/nextAttribute
            var startRelationID: Byte = -1
            var currentIntersectingAttributeVal: Int = -1
            var currentIntersectionRelation: Byte = -1
            var nextIntersectingAttributeVal: Int = -1
            while (t < numTuples) {
              currentIntersectingAttributeVal = -1
              nextIntersectingAttributeVal = -1
              startRelationID = startRelationIDs(t)
              succTupleFirstAttrIndex = t*nextAttribute
              succTupleEndIndex = (t+1)*nextAttribute
              // Below code essentially implements the following intersection routine:
              // We start intersecting from the min offer relation. After that we start intersecting
              // from R1, R2, ..., Rm but only consider the ones that have a conditioned attribute.
              // If i == 0, then we are doing the first intersection, so we have to start from
              // the startRelationID. In the following for loop, we will never set the
              // currentIntersectingAttributeVal.
              if (extensionRoundNo == 0) {
                val startRelationsCondAttrIndex = relationConditionedAttributesMap(startRelationID)(0)
                currentIntersectingAttributeVal = succTuples(
                  succTupleFirstAttrIndex + startRelationsCondAttrIndex)
                currentIntersectionRelation = startRelationID
              }
              var count = 0
              var relIndex = 0
              while (relIndex < joinsArgs.schemas.length) {
                if (relIndex != startRelationID &&
                  relationConditionedAttributesMap(relIndex).nonEmpty) {
                  count += 1
                  if (count == extensionRoundNo) {
                    val condAttrIndex = relationConditionedAttributesMap(relIndex)(0)
                    currentIntersectingAttributeVal = succTuples(
                      succTupleFirstAttrIndex + condAttrIndex)
                    currentIntersectionRelation = relIndex.toByte
                  } else if (count == extensionRoundNo+1) {
                    val nextCondAttrIndex = relationConditionedAttributesMap(relIndex)(0)
                    nextIntersectingAttributeVal = succTuples(
                      succTupleFirstAttrIndex + nextCondAttrIndex)                    
                  }                   
                }
                relIndex += 1
              }
              // If there is no new relation/attribute to intersect with, then we send this final
              // tuple message to the partition of the first attribute in the tuple.
              if (nextIntersectingAttributeVal == -1) {
                assert(extensionRoundNo == numRelationsWithNonEmptyConditions - 1,
                  " there shouldn't be any attr to intersect only in the last iteration of intersections.")
                nextIntersectingAttributeVal = succTuples(succTupleFirstAttrIndex)
              }

              val (beginValIndOffset, endValIndOffset) = 
                relationPartition.getOffsetIndicesForAttributeRelationID(
                currentIntersectingAttributeVal, currentIntersectionRelation)
              assert(endValIndOffset - beginValIndOffset > 0,
                "the set of conditioned values for any relation has to be non-emtpy. " +
                  "endIndOffset: " + endValIndOffset + " beginIndOffset: " + beginValIndOffset
                  + " thisPartitionID: " + partitionID
                  + " currentIntersectingAttributeVal: " + currentIntersectingAttributeVal
                  + " currentIntersectionRelation: " + currentIntersectionRelation)
              if (extensionRoundNo == 0) {
                log.debug("intersecting with the first attribute. We should just copy the intersection"
                  + currentIntersectingAttributeVal)
                var beginValIndexToPass = beginValIndOffset
                if (joinsArgs.countMotifsOnce) {
                  beginValIndexToPass = binarySearchIndexWithMinValueGreaterThanX(
                    relationPartition.values, beginValIndOffset, endValIndOffset-1,
                    succTuples(succTupleEndIndex-1))
                }
                intersectionMesssagesToEachPartition.addTuple(nextIntersectingAttributeVal,
                  succTuples, succTupleFirstAttrIndex, succTupleEndIndex, relationPartition.values,
                  beginValIndexToPass, endValIndOffset, startRelationID)
              } else {
                beginIntersectionOffset = intersectionOffsets(t)
                endIntersectionOffset = intersectionOffsets(t + 1)
                val numSuccIntersections = gallopingIntersect2(currentIntersections,
                  beginIntersectionOffset, endIntersectionOffset, relationPartition.values,
                  beginValIndOffset, endValIndOffset)
                if (numSuccIntersections > 0) {
                  intersectionMesssagesToEachPartition.addTuple(nextIntersectingAttributeVal,
                    succTuples, succTupleFirstAttrIndex, succTupleEndIndex,
                    currentIntersections, beginIntersectionOffset,
                    beginIntersectionOffset + numSuccIntersections, startRelationID)
                }
              }
              t += 1
            }
          }
          intersectionMesssagesToEachPartition.getMessages
        }
      })
     intersections.setName("INTERSECTIONS_FOR_ATTR_" + nextAttribute + "_ROUND_" + extensionRoundNo)
     intersections
  }
  
  // Starts the first intersections after making the plan.
  private def computeFirstNullIntersections(nextAttribute: Byte,
    relationConditionedAttributesMap: ArrayBuffer[ArrayBuffer[Byte]],
    succCountOffersCogrouped: RDD[(Short, (Iterable[ArrayBuffer[Int]], Iterable[(Short, ArrayBuffer[ArrayBuffer[Int]])]))])
    : RDD[(Short, IntersectionMessage)] = {

    val intersections = succCountOffersCogrouped.flatMap { partitionIDTuplesOffers =>
      val partitionID = partitionIDTuplesOffers._1
      if (partitionIDTuplesOffers._2._1.isEmpty) {
        log.debug("There are NO succ tuples for partitionID: " + partitionID)
        Seq.empty
      } else {
        val succTuples = partitionIDTuplesOffers._2._1.head
        log.debug("Starting to loop over the succ tuples for partitionID: " + partitionID)
        log.debug("succTuples: " + succTuples)
        val offers = new ArrayBuffer[ArrayBuffer[ArrayBuffer[Int]]](joinsArgs.numPartitions)
        for (i <- 0 to joinsArgs.numPartitions - 1) offers += new ArrayBuffer[ArrayBuffer[Int]]()
        for (partitionIDOffers <- partitionIDTuplesOffers._2._2) {
          offers(partitionIDOffers._1) = partitionIDOffers._2
        }
        log.debug("offers: " + offers + " make sure it's in sorted order")
        // NOTE: We assume that each request was made
        val indices = new ArrayBuffer[ArrayBuffer[Int]](joinsArgs.numPartitions)
        for (i <- 0 to joinsArgs.numPartitions - 1) {
          indices += new ArrayBuffer[Int](joinsArgs.schemas.length)

          for (j <- 0 to joinsArgs.schemas.length - 1) {
            indices(i) += 0
          }
        }
        val numTuples = succTuples.length/nextAttribute
        log.debug("numTuples: " + numTuples)
        // if we're extending to A3, then currently each succ-tuple has 3 attributes in it,
        // assuming attributes start from A0
        val stepSize = nextAttribute
        var attributeVal = -1
        var partitionIDOfAttr: Short = -1
        var minOfferRelationID: Byte = -1
        var minAttributeVal = -1
        var minOffer: Int = -1
        var offer: Int = -1
        var indexToOffer: Int = -1
        var attributesToConditionOn: ArrayBuffer[Byte] = null
        val intersectionMesssagesToEachPartition = new IntersectionMessagesToEachPartition(
          joinsArgs.numPartitions)
        var firstRelation: Boolean = true
        var i = 0
        while (i < numTuples) {
          val succTupleFirstAttrIndex = i * nextAttribute
          val succTupleEndIndex = (i+1) * nextAttribute
          log.debug("succTupleFirstAttrIndex: " + succTupleFirstAttrIndex +
            " succTupleEndIndex: " + succTupleEndIndex)
          firstRelation = true
          var relationID = 0
          while (relationID < relationConditionedAttributesMap.length) {
            attributesToConditionOn = relationConditionedAttributesMap(relationID)
            log.debug("attributesToConditionOn for relationID: " + relationID + " :"
              + attributesToConditionOn)
            // If relation with ID=relationID was conditioned on some attribute(s), then we asked
            // a particular partitionID with for the size of its adjacency list for a particular
            // attributeVal. And that partitionID answered that request in that same order.
            if (!attributesToConditionOn.isEmpty) {
              attributeVal = succTuples(succTupleFirstAttrIndex + attributesToConditionOn(0))
              partitionIDOfAttr = (attributeVal % joinsArgs.numPartitions).toShort
              indexToOffer = indices(partitionIDOfAttr)(relationID)
              log.debug("partitionIDOfAttr: " + partitionIDOfAttr + " attributeVal: " + attributeVal 
                + " indexToOffer: " + indexToOffer)
              indices(partitionIDOfAttr)(relationID) += 1
              offer = offers(partitionIDOfAttr)(relationID)(indexToOffer)
              if (firstRelation || offer < minOffer) {
                firstRelation = false
                minOffer = offer
                minOfferRelationID = relationID.toByte
                minAttributeVal = attributeVal
              }
            }
            relationID += 1
          }
          log.debug("for tuple: " + succTuples.slice(succTupleFirstAttrIndex, succTupleEndIndex)
            + " minOffer: " + minOffer + " minOfferRelationID: " + minOfferRelationID)
          if (minOffer > 0) {
            log.debug("adding tuple: " + succTuples.slice(succTupleFirstAttrIndex, succTupleEndIndex)
              + " minOffer: " + offer + " minOfferRelationID: " + minOfferRelationID)
            intersectionMesssagesToEachPartition.addTuple(minAttributeVal, succTuples,
              succTupleFirstAttrIndex, succTupleEndIndex, minOfferRelationID)
          } else {
            log.debug("minOffer for tuple: " + succTuples.slice(
              succTupleFirstAttrIndex, succTupleEndIndex) + " is: " + minOffer
              + ". Skipping. Can't have a successful intersection.")
          }
          i += 1
        }
        intersectionMesssagesToEachPartition.getMessages
      }
    }
    intersections.setName("INTERSECTIONS_FOR_ATTR_" + nextAttribute + "_0")
    intersections
  }
  
  def computeCountOffers(relations: RDD[(Short, RelationPartition)],
    nextAttribute: Byte, countRequests: RDD[(Short, (Short, ArrayBuffer[ArrayBuffer[Int]]))]):
    RDD[(Short, (Short, ArrayBuffer[ArrayBuffer[Int]]))] = {
    val requestsRelationsCogrouped = countRequests.cogroup(relations, joinsArgs.reduceParallelism)
    requestsRelationsCogrouped.setName("COUNT_REQUESTS_RELATIONS_COGROUPED_" + nextAttribute)
    debugRelation(requestsRelationsCogrouped)
    val countOffers = requestsRelationsCogrouped.flatMap(partitionIDRequestPartition => {
      val partitionID = partitionIDRequestPartition._1
      log.debug("partitionID: " + partitionID)
      if (partitionIDRequestPartition._2._2.isEmpty) {
        log.info("there is no partition for partitionID: " + partitionID + " inside computeCountOffers.")
        Seq.empty
      } else {
        val partition = partitionIDRequestPartition._2._2.head
        log.debug("partitionID: " + partitionID + " partition: " + partition)
        val requests = partitionIDRequestPartition._2._1
        log.debug("requests: " + requests)
        // We loop through the keys and the request for each attribute in tandem
        val keys = partition.keys
        var key = -1
        var countOffer = -1
        val outputs = new ArrayBuffer[(Short, (Short, ArrayBuffer[ArrayBuffer[Int]]))](
          joinsArgs.numPartitions)
        var i = 0
        while (i < joinsArgs.numPartitions) {
          outputs += ((i.toShort, (partitionID, null)))
          i += 1
        }

        requests.foreach { elem =>
          val srcPartitionID = elem._1
          val requestArray = elem._2
          val offersToSrcPartition = new ArrayBuffer[ArrayBuffer[Int]](requestArray.length)
          outputs(srcPartitionID) = (srcPartitionID.toShort, (partitionID, offersToSrcPartition))
          var relID = 0
          while (relID < requestArray.length) {
            val offerToSrcPartition = new ArrayBuffer[Int](requestArray(relID).length)
            offersToSrcPartition += offerToSrcPartition
            requestArray(relID).foreach { attributeVal =>
              val offerSize = partition.getCountOffer(attributeVal, relID.toByte)
              log.debug("partitionID: " + srcPartitionID + " is asking for countOffer. attributeVal: "
                + attributeVal + " relID: " + relID + " fromPartitionID: " + partitionID)
              offerToSrcPartition += offerSize
            }
            relID += 1
          }
        }
        outputs
      }
    })
    countOffers.setName("COUNT_OFFERS_" + nextAttribute)
    countOffers
  }

  def computeCountRequests(succ: RDD[(Short, ArrayBuffer[Int])], nextAttribute: Byte,
    relationConditionedAttributesMap: ArrayBuffer[ArrayBuffer[Byte]])
     : RDD[(Short, (Short, ArrayBuffer[ArrayBuffer[Int]]))] = {
    // partitionIDTuples stands for tupleBoolean
    val countRequests = succ.flatMap { partitionIDTuples =>
      val partitionID = partitionIDTuples._1
      val tuples = partitionIDTuples._2
      val numAttrInSuccTuples = nextAttribute
      val requestsForEachPartition = new ArrayBuffer[ArrayBuffer[ArrayBuffer[Int]]](
        joinsArgs.numPartitions)
      var i = 0
      while (i < joinsArgs.numPartitions) {
        requestsForEachPartition += new ArrayBuffer[ArrayBuffer[Int]](joinsArgs.schemas.length)
        var j = 0
        while (j < joinsArgs.schemas.length) {
          requestsForEachPartition(i) += new ArrayBuffer[Int]()
          j += 1
        }
        i += 1
      }
      val numTuples = tuples.length/numAttrInSuccTuples
      i = 0
      while (i < numTuples) {
        var relationID = 0
        while (relationID < relationConditionedAttributesMap.length) {
          val attributesToConditionOn = relationConditionedAttributesMap(relationID)
          // WARNING: When we start computing non-binary queries the below if should be 
          // removed and we should condition on a set of attributes.
          if(!attributesToConditionOn.isEmpty) {
            // WARNING: Below we implicitly assume that we are always extending
            // attributes in increasing index from A0, A1, ..., Ak
            val attributeToConditionOn = tuples(i * numAttrInSuccTuples + attributesToConditionOn(0))
            requestsForEachPartition(attributeToConditionOn % joinsArgs.numPartitions)(
              relationID) += attributeToConditionOn
          }
          relationID += 1
        }
        i += 1
      }
      
      val outputs = new ArrayBuffer[(Short, (Short, ArrayBuffer[ArrayBuffer[Int]]))]()
      for (i <- 0 to requestsForEachPartition.length - 1) {
        outputs += ((i.toShort, (partitionID, requestsForEachPartition(i))))
      }
      outputs
    }
    countRequests.setName("COUNT_REQUESTS_" + nextAttribute)
    countRequests
  }
  
  def findForEachRelationAttributeToCondition(nextAttribute: Byte): ArrayBuffer[ArrayBuffer[Byte]] = {
    log.debug("inside findForEachRelationAttributeToCondition. nextAttribute: " + nextAttribute)
    val relAttrIndices = new ArrayBuffer[ArrayBuffer[Byte]](nextAttribute)
    var relIndex = 0
    while (relIndex < joinsArgs.schemas.length) {
      relAttrIndices += new ArrayBuffer[Byte]()
      val schema = joinsArgs.schemas(relIndex.toByte)
      var attributeIndex = 0
      while (attributeIndex < nextAttribute) {
        if (schema._1 == attributeIndex && schema._2 == nextAttribute) {
          relAttrIndices(relIndex) += attributeIndex.toByte
        }
        attributeIndex += 1
      }
      relIndex += 1
    }
    relAttrIndices
  }
  
  // Note: This should not have any communication cost.
  def computeSUCC_A0(relations: RDD[(Short, RelationPartition)])
      : RDD[(Short, ArrayBuffer[Int])] = {
    val a0Indices = findIndices(0).sortWith(_ < _)
    val a0IndicesLength = a0Indices.length

    val succ0 = relations.map { partitionIDPartition =>
      // We allocated one partition for each
      val partitionID = partitionIDPartition._1
      val intersection = ArrayBuffer[Int]()
      val partition = partitionIDPartition._2
      val keys = partition.keys
      val keyBeginningOffsets = partition.keyBeginningOffsets
      val relIDsForEachKey = partition.relIDsForEachKey
      
      var nextA0RelationIndex = -1
      var nextA0RelationID = -1
      var keyOffsetBegin = -1
      var keyOffsetEnd = -1
      var nextKeyRelation = -1
      var i = 0
      while (i < keys.length) {
        keyOffsetBegin = keyBeginningOffsets(i)
        keyOffsetEnd = keyBeginningOffsets(i+1)
        nextA0RelationID = a0Indices(0)
        nextA0RelationIndex = 0
        var j = keyOffsetBegin
        while (j < keyOffsetEnd) {
          nextKeyRelation = relIDsForEachKey(j)
          if (nextKeyRelation == nextA0RelationID) {
            if (nextA0RelationIndex+1 >= a0IndicesLength) {
              // Success! This key occurs in each relation in a0Indices once, i.e. is in the
              // intersection.
              intersection += keys(i)
            } else {
              // We start looking for the next relation
              nextA0RelationIndex += 1
              nextA0RelationID = a0Indices(nextA0RelationIndex)
            }
          }
          j += 1
        }
        i += 1
      }
      (partitionID, intersection)
    }
    succ0.setName("SUCC_A0")
    succ0
  }

  def parseEdgesIntoRDDFromEdgeListFormat(sc: SparkContext): RDD[(Short, RelationPartition)] = {
    val timeBeforeParsing = System.currentTimeMillis()
    var edgesRDDList = new ListBuffer[RDD[(Int, Int)]]()
    var partitionIDKeyRelIDValues: RDD[(Short, (Int, Byte, Int))] = null
//    for (i <- 0 to joinsArgs.inputFiles.length-1) {
      val partitionIdKeyRelIDValue = sc.textFile(joinsArgs.inputFiles(0),
        joinsArgs.mapParallelism).flatMap { line =>
        val outputs = new ArrayBuffer[(Short,(Int, Byte, Int))]()
        val split = line.split("\\s+")
        val key = split(0).toInt
        var j = 1
        while (j < split.length) {
          var i = 0
          while (i < joinsArgs.inputFiles.length) {
            outputs += (((key % joinsArgs.numPartitions).toShort, (key, i.toByte, split(j).toInt)))
            i += 1
          }
          j += 1
        }
        outputs
      }
//      if (partitionIDKeyRelIDValues == null) {
        partitionIDKeyRelIDValues = partitionIdKeyRelIDValue
//      } else {
//        partitionIDKeyRelIDValues ++= partitionIdKeyRelIDValue
//      }
//    }
    val relations = partitionIDKeyRelIDValues.groupByKey(joinsArgs.numPartitions).
      map { partIDKeyRelIDValueSeq =>
      // TODO(firas): again, check if it's okay to convert to Seq here
      (partIDKeyRelIDValueSeq._1, RelationPartitionFactory(joinsArgs.schemas, partIDKeyRelIDValueSeq._2.toSeq))
    }
    relations.setName("RELATIONS")
    relations
  }
}