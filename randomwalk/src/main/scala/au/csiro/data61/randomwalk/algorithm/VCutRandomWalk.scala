package au.csiro.data61.randomwalk.algorithm

import au.csiro.data61.randomwalk.common.Params
import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import scala.util.{Random, Try}

case class VCutRandomWalk(context: SparkContext,
                          config: Params) extends RandomWalk {

  def loadGraph(hetero: Boolean, bcMetapath: Broadcast[Array[Short]]): RDD[(Int, (Array[Int]))] = {
    //    val bcDirected = context.broadcast(config.directed)
    //    val bcWeighted = context.broadcast(config.weighted) // is weighted?
    //    val bcRddPartitions = context.broadcast(config.rddPartitions)
    //    val bcPartitioned = context.broadcast(config.partitioned)

    val g = hetero match {
      case true => loadHeteroGraph().partitionBy(partitioner).persist(StorageLevel.MEMORY_AND_DISK)
      case false => loadHomoGraph().partitionBy(partitioner).persist(StorageLevel.MEMORY_AND_DISK)
    }

    val edgePartitions: RDD[(Int, (Array[(Int, Int, Float)], Int))] = context.textFile(config
      .input, minPartitions = config.rddPartitions).flatMap { triplet =>
      val parts = triplet.split("\\s+")

      val pId: Int = bcPartitioned.value && parts.length > 2 match {
        case true => Try(parts(2).toInt).getOrElse(Random.nextInt(bcRddPartitions.value))
        case false => Random.nextInt(bcRddPartitions.value)
      }

      // if the weights are not specified it sets it to 1.0
      val weight = bcWeighted.value && parts.length > 3 match {
        case true => Try(parts.last.toFloat).getOrElse(1.0f)
        case false => 1.0f
      }

      val (src, dst) = (parts.head.toInt, parts(1).toInt)
      val srcTuple = (src, (Array((dst, pId, weight)), pId))
      if (bcDirected.value) {
        Array(srcTuple, (dst, (Array.empty[(Int, Int, Float)], pId)))
      } else {
        Array(srcTuple, (dst, (Array((src, pId, weight)), pId)))
      }
    }.partitionBy(partitioner).persist(StorageLevel.MEMORY_AND_DISK)

    val vertexPartitions = edgePartitions.mapPartitions({ iter =>
      iter.map { case (src, (_, pId)) =>
        (src, pId)
      }
    }, preservesPartitioning = true).cache()

    val vertexNeighbors = edgePartitions.reduceByKey((x, y) => (x._1 ++ y._1, x._2)).cache

    val g: RDD[(Int, (Int, Array[(Int, Int, Float)], Short))] = null // TODO: to be implemented
    throw new NotImplementedError("VCut for this version is not implemented yet!")
    //      vertexPartitions.join(vertexNeighbors).map {
    //        case (v, (pId, (neighbors, _))) => (pId, (v, neighbors))
    //      }.partitionBy(partitioner)

    routingTable = buildRoutingTable(g).persist(StorageLevel.MEMORY_ONLY)
    routingTable.count()

    val vAccum = context.longAccumulator("vertices")
    val eAccum = context.longAccumulator("edges")

    val rAcc = context.collectionAccumulator[Int]("replicas")
    val lAcc = context.collectionAccumulator[Int]("links")

    vertexNeighbors.foreachPartition { iter =>
      val (r, e) = HGraphMap.getGraphStatsOnlyOnce
      if (r != 0) {
        rAcc.add(r)
        lAcc.add(e)
      }
      iter.foreach {
        case (_, (neighbors: Array[(Int, Int, Float)], _)) =>
          vAccum.add(1)
          eAccum.add(neighbors.length)
      }
    }
    nVertices = vAccum.sum.toInt
    nEdges = eAccum.sum.toInt

    logger.info(s"edges: $nEdges")
    logger.info(s"vertices: $nVertices")
    println(s"edges: $nEdges")
    println(s"vertices: $nVertices")

    val ePartitions = lAcc.value.toArray.mkString(" ")
    val vPartitions = rAcc.value.toArray.mkString(" ")
    logger.info(s"E Partitions: $ePartitions")
    logger.info(s"V Partitions: $vPartitions")
    println(s"E Partitions: $ePartitions")
    println(s"V Partitions: $vPartitions")

    val walkers = vertexNeighbors.map {
      case (vId: Int, (_, pId: Int)) =>
        (pId, Array(vId))
    }

    initWalkersToTheirPartitions(routingTable, walkers).persist(StorageLevel.MEMORY_AND_DISK)
  }

  def loadHomoGraph(): RDD[(Int, (Array[(Array[(Int, Float)], Short)], Short))] = {
    val bcDirected = context.broadcast(config.directed)
    val bcWeighted = context.broadcast(config.weighted) // is weighted?
    val bcRddPartitions = context.broadcast(config.rddPartitions)

    val edgePartitions = context.textFile(config.input, minPartitions
      = config
      .rddPartitions).flatMap { triplet =>
      val parts = triplet.split("\\s+")
      // if the weights are not specified it sets it to 1.0

      val pId: Int = parts.length > 2 match {
        case true => Try(parts(2).toInt).getOrElse(Random.nextInt(bcRddPartitions.value))
        case false => Random.nextInt(bcRddPartitions.value)
      }

      // if the weights are not specified it sets it to 1.0
      val weight = bcWeighted.value && parts.length > 3 match {
        case true => Try(parts.last.toFloat).getOrElse(1.0f)
        case false => 1.0f
      }

      val (src, dst) = (parts.head.toInt, parts(1).toInt)
      val srcTuple = (src, (Array((dst, pId, weight)), pId))

      if (bcDirected.value) {
        Array(srcTuple, (dst, (Array.empty[(Int, Int, Float)], pId)))
      } else {
        Array(srcTuple, (dst, (Array((src, pId, weight)), pId)))
      }
    }.partitionBy(partitioner).persist(StorageLevel.MEMORY_AND_DISK)

    val vertexPartitions = edgePartitions.mapPartitions({ iter =>
      iter.map { case (src, (_, pId)) =>
        (src, pId)
      }
    }, preservesPartitioning = true).cache()

    val vertexNeighbors = edgePartitions.reduceByKey((x, y) => (x._1 ++ y._1, x._2))
    vertexPartitions.join(vertexNeighbors).map {
      case (v, (pId, (neighbors, _))) => (pId, (v, neighbors))
    }.partitionBy(partitioner).mapPartitions({ iter =>
      val defaultNodeType: Short = 0
      iter.map { case (pId, (src, neighbors)) =>
        (pId, (src, Array((neighbors, defaultNodeType)), defaultNodeType))
      } // TODO: You should do the convesions for node type before partitioning.
    })
  }

  def initWalkersToTheirPartitions(routingTable: RDD[Int], walkers: RDD[(Int, Array[Int])]) = {
    routingTable.zipPartitions(walkers.partitionBy(partitioner)) {
      (_, iter2) =>
        iter2
    }
  }

  def buildRoutingTable(graph: RDD[(Int, (Int, Array[(Int, Int, Float)], Short))]): RDD[Int] = {

    graph.mapPartitionsWithIndex({ (id: Int, iter: Iterator[(Int, (Int, Array[(Int, Int,
      Float)], Short))]) =>
      iter.foreach { case (_, (vId, neighbors, dstType)) =>
        HGraphMap.addVertex(dstType, vId, neighbors)
        id
      }
      Iterator.empty
    }, preservesPartitioning = true
    )

  }

  def prepareWalkersToTransfer(walkers: RDD[(Int, (Array[Int], Array[(Int, Float)], Boolean,
    Short))]) = {
    walkers.mapPartitions({
      iter =>
        iter.map {
          case (_, (steps, prevNeighbors, completed, mpIndex)) =>
            val pId = HGraphMap.getPartition(mpIndex, steps.last) match {
              case Some(pId) => pId
              case None => -1 // Must exists!
            }
            (pId, (steps, prevNeighbors, completed, mpIndex))
        }
    }, preservesPartitioning = false)

  }

}
