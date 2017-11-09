package au.csiro.data61.randomwalk.efficient

import au.csiro.data61.Main
import com.navercorp.common.Property
import org.apache.log4j.LogManager
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{HashPartitioner, SparkContext}

import scala.util.control.Breaks._
import scala.util.{Random, Try}

case class RandomWalk(context: SparkContext,
                      config: Main.Params) extends Serializable {

  lazy val logger = LogManager.getLogger("myLogger")
  var nVertices: Long = 0
  var nEdges: Long = 0

  /**
    * Loads the graph and computes the probabilities to go from each vertex to its neighbors
    *
    * @return
    */
  def loadGraph(): RDD[(Long, Array[Long])] = {
    // the directed and weighted parameters are only used for building the graph object.
    // is directed? they will be shared among stages and executors
    val bcDirected = context.broadcast(config.directed)
    val bcWeighted = context.broadcast(config.weighted) // is weighted?
    val vAccum = context.longAccumulator("vertices")
    val eAccum = context.longAccumulator("edges")

    // inputTriplets is an array of edges (src, dst, weight).

    val edges: RDD[Edge[Double]] = context.textFile(config.input, minPartitions = config
      .rddPartitions).flatMap { triplet =>
      val parts = triplet.split("\\s+")
      // if the weights are not specified it sets it to 1.0

      val weight = bcWeighted.value && parts.length > 2 match {
        case true => Try(parts.last.toDouble).getOrElse(1.0)
        case false => 1.0
      }

      val (src, dst) = (parts.head.toLong, parts(1).toLong)
      if (bcDirected.value) {
        Array(Edge(src, dst, weight))
      } else {
        Array(Edge(src, dst, weight), Edge(dst, src, weight))
      }
    }.cache()

    val graph: Graph[_, Double] = Graph.fromEdges(edges, defaultValue = None,
      edgeStorageLevel =
        StorageLevel.MEMORY_AND_DISK, vertexStorageLevel = StorageLevel.MEMORY_AND_DISK).
      partitionBy(partitionStrategy = PartitionStrategy.EdgePartition2D, numPartitions = config
        .rddPartitions)

    val g = graph.collectEdges(EdgeDirection.Out)
    val deadEnds = graph.collectNeighborIds(EdgeDirection.Out).filter(_._2.isEmpty).map { case
      (id, _) =>
      id
    }
    val numEdges = context.broadcast(graph.edges.count().toInt)
    graph.edges.unpersist(blocking = false)
    val numDeadEnds = context.broadcast(deadEnds.count().toInt)
    val numVertices = context.broadcast(g.count().toInt + numDeadEnds.value)

    g.mapPartitions { iter =>
      GraphMap.setUp(numVertices.value, numDeadEnds.value, numEdges.value)
      val newIter = iter.map {
        case (vId: Long, (neighbors: Array[Edge[Double]])) =>
          GraphMap.addVertex(vId, neighbors)
          vAccum.add(1)
          eAccum.add(neighbors.length)
          vId
      }
      newIter
    }.count()

    g.unpersist(blocking = false)

    deadEnds.mapPartitions { iter =>
      val newIter = iter.map {
        case vId =>
          GraphMap.addVertex(vId)
          vAccum.add(1)
          vId
      }

      newIter
    }.count()

    deadEnds.unpersist(blocking = false)

    nVertices = vAccum.sum
    nEdges = eAccum.sum

    logger.info(s"edges: $nEdges")
    logger.info(s"vertices: $nVertices")

    graph.vertices.map { case (vId: Long, _) =>
      (vId, Array(vId))
    }.partitionBy(new HashPartitioner(config.rddPartitions)).cache()
  }

  def doFirsStepOfRandomWalk(paths: RDD[(Long, Array[Long])], nextDouble: () =>
    Double = Random.nextDouble): RDD[(Long, Array[Long])] = {
    //    val map = gMap.value
    paths.mapPartitions { iter =>
      iter.map { case ((src: Long, path: Array[Long])) =>
        val neighbors = GraphMap.getNeighbors(path.head)
        if (neighbors != null && neighbors.length > 0) {
          val (nextStep, _) = RandomSample(nextDouble).sample(neighbors)
          (src, path ++ Array(nextStep))
        } else {
          // TODO maybe the neighbors are not in this partition.
          (src, path)
        }
      }
    }
  }

  def randomWalk(initPaths: RDD[(Long, Array[Long])], nextDouble: () => Double = Random
    .nextDouble)
  : RDD[Array[Long]] = {
    val bcP = context.broadcast(config.p)
    val bcQ = context.broadcast(config.q)
    val walkLength = context.broadcast(config.walkLength).value
    val numberOfWalks = context.broadcast(config.numWalks).value
    // initialize the first step of the random walk
    var totalPaths: RDD[Array[Long]] = null
    val paths = doFirsStepOfRandomWalk(initPaths, nextDouble)
    for (_ <- 0 until numberOfWalks) {
      val newPaths = paths.mapPartitions { iter =>
        iter.map { case (_, firstStep: Array[Long]) =>
          var path = firstStep
          val rSample = RandomSample(nextDouble)
          if (firstStep.length > 1)
            breakable {
              for (_ <- 0 until walkLength) {
                val curr = path.last
                val currNeighbors = GraphMap.getNeighbors(curr)
                if (currNeighbors != null && currNeighbors.length > 0) {
                  val prev = path(path.length - 2)
                  val prevNeighbors = GraphMap.getNeighbors(prev)
                  // TODO handle sending prevNeighbors to the destination partition
                  val (nextStep, _) = rSample.secondOrderSample(bcP.value, bcQ
                    .value, prev, prevNeighbors, currNeighbors)
                  path = path ++ Array(nextStep)
                } else {
                  // TODO maybe the neighbors are not in this partition
                  break
                }
              }
            }
          path
        }
      }.cache()

      if (totalPaths != null)
        totalPaths = totalPaths.union(newPaths).persist(StorageLevel.MEMORY_AND_DISK)
      else
        totalPaths = newPaths
    }

    totalPaths
  }

  def save(paths: RDD[Array[Long]]) = {

    paths.mapPartitions { iter =>
      iter.map {
        case (path) =>
          val pathString = path.mkString("\t")
          s"$pathString"
      }
    }.repartition(config.rddPartitions).saveAsTextFile(s"${
      config.output
    }" +
      s".${
        Property.pathSuffix
      }")
  }

}
