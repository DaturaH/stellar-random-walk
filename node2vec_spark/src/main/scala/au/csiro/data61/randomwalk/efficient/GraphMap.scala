package au.csiro.data61.randomwalk.efficient


import org.apache.spark.graphx.Edge

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, HashMap}


/**
  *
  */

object GraphMap {

  private lazy val srcVertexMap: mutable.Map[Long, Int] = new HashMap[Long, Int]()
  private lazy val offsets: ArrayBuffer[Int] = new ArrayBuffer()
  private lazy val lengths: ArrayBuffer[Int] = new ArrayBuffer()
  private lazy val edges: ArrayBuffer[(Long, Double)] = new ArrayBuffer()
  private var indexCounter: Int = 0
  private var offsetCounter: Int = 0

  def addVertex(vId: Long, neighbors: Array[Edge[Double]]) = synchronized {
    srcVertexMap.put(vId, indexCounter)
    offsets.insert(indexCounter, offsetCounter)
    lengths.insert(indexCounter, neighbors.length)
    for (e <- 0 until neighbors.length) {
      edges.insert(offsetCounter, (neighbors(e).dstId, neighbors(e).attr))
      offsetCounter += 1
    }

    indexCounter += 1
  }

  def addVertex(vId: Long, neighbors: Array[(Long, Double)]): Unit = synchronized {
    if (!neighbors.isEmpty) {
      srcVertexMap.put(vId, indexCounter)
      offsets.insert(indexCounter, offsetCounter)
      lengths.insert(indexCounter, neighbors.length)
      for (e <- neighbors) {
        edges.insert(offsetCounter, e)
        offsetCounter += 1
      }

      indexCounter += 1
    } else {
      this.addVertex(vId)
    }
  }

  def addVertex(vId: Long): Unit = synchronized {
    srcVertexMap.put(vId, -1)
  }

  def getNumVertices: Int = {
    srcVertexMap.size
  }

  def getNumEdges: Int = {
    offsetCounter
  }

  /**
    * The reset is mainly for the unit test purpose. It does not reset the size of data
    * structures that are initialy set by calling setUp function.
    */
  def reset {
    indexCounter = 0
    offsetCounter = 0
    srcVertexMap.clear()
    offsets.clear()
    lengths.clear()
    edges.clear()
  }

  def getNeighbors(vid: Long): Array[(Long, Double)] = {
    srcVertexMap.get(vid) match {
      case Some(index) =>
        if (index == -1) {
          return Array.empty[(Long, Double)]
        }
        val offset = offsets(index)
        val length = lengths(index)
        edges.slice(offset, offset + length).toArray
      case None => null
    }
  }
}
