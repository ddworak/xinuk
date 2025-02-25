package pl.edu.agh.xinuk.simulation

import akka.actor.{Actor, ActorRef, Props, Stash}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import com.avsystem.commons.SharedExtensions._
import org.slf4j.{Logger, LoggerFactory, MarkerFactory}
import pl.edu.agh.xinuk.algorithm.MovesController
import pl.edu.agh.xinuk.config.XinukConfig
import pl.edu.agh.xinuk.gui.GuiActor.GridInfo
import pl.edu.agh.xinuk.model._
import pl.edu.agh.xinuk.model.parallel.{ConflictResolver, Neighbour}

import scala.collection.immutable.TreeSet
import scala.collection.mutable

class WorkerActor[ConfigType <: XinukConfig](
  regionRef: => ActorRef,
  movesControllerFactory: (TreeSet[(Int, Int)], ConfigType) => MovesController,
  conflictResolver: ConflictResolver[ConfigType],
  emptyCellFactory: => SmellingCell = EmptyCell.Instance)(implicit config: ConfigType) extends Actor with Stash {

  import pl.edu.agh.xinuk.simulation.WorkerActor._

  var grid: Grid = _

  var bufferZone: TreeSet[(Int, Int)] = _

  var id: WorkerId = _

  val guiActors: mutable.Set[ActorRef] = mutable.Set.empty

  var neighbours: Map[WorkerId, Neighbour] = _

  private val finished: mutable.Map[Long, Vector[IncomingNeighbourCells]] = mutable.Map.empty.withDefaultValue(Vector.empty)

  var logger: Logger = _

  var movesController: MovesController = _

  override def receive: Receive = stopped

  private def propagateSignal(): Unit = {
    (0 until config.signalSpeedRatio).foreach { _ =>
      val cells = Array.tabulate(config.gridSize, config.gridSize)((x, y) =>
        grid.propagatedSignal(x, y)
      )
      grid = Grid(cells)
    }
  }

  def stopped: Receive = {
    case SubscribeGridInfo(_) =>
      guiActors += sender()
    case NeighboursInitialized(id, neighbours) =>
      this.id = id
      this.logger = LoggerFactory.getLogger(id.value.toString)
      this.neighbours = neighbours.mkMap(_.position.neighbourId(id).get, identity)
      this.bufferZone = neighbours.foldLeft(TreeSet.empty[(Int, Int)])((builder, neighbour) => builder | neighbour.position.bufferZone)
      this.movesController = movesControllerFactory(bufferZone, config)
      grid = Grid.empty(bufferZone)
      logger.info(s"${id.value} neighbours: ${neighbours.map(_.position).toList}")
      self ! StartIteration(1)
    case StartIteration(1) =>
      val (newGrid, newMetrics) = movesController.initialGrid
      this.grid = newGrid
      logMetrics(1, newMetrics)
      propagateSignal()
      guiActors.foreach(_ ! GridInfo(1, grid, newMetrics))
      notifyNeighbours(1, grid)
      unstashAll()
      context.become(started)
    case _: IterationPartFinished =>
      stash()
  }

  var currentIteration: Long = 1

  def started: Receive = {
    case StartIteration(i) =>
      finished.remove(i - 1)
      logger.debug(s"$id started $i")
      propagateSignal()
      val (newGrid, newMetrics) = movesController.makeMoves(i, grid)
      this.grid = newGrid
      logMetrics(i, newMetrics)
      guiActors.foreach(_ ! GridInfo(i, grid, newMetrics))
      notifyNeighbours(i, grid)
      if (i % 100 == 0) logger.info(s"$id finished $i")
    case IterationPartFinished(workerId, _, iteration, neighbourBuffer) =>
      val currentlyFinished: Vector[IncomingNeighbourCells] = finished(iteration)
      val incomingNeighbourCells: IncomingNeighbourCells =
        if (workerId != id) {
          val neighbour = neighbours(workerId)
          val affectedCells: Iterator[(Int, Int)] = neighbour.position.affectedCells
          val incoming: Vector[((Int, Int), BufferCell)] =
            affectedCells.zip(neighbourBuffer.iterator)
              .filterNot { case ((x, y), _) => bufferZone.contains((x, y)) } //at most 8 cells are discarded
              .toVector
          new IncomingNeighbourCells(incoming)
        } else {
          new IncomingNeighbourCells(Vector.empty)
        }
      finished(iteration) = currentlyFinished :+ incomingNeighbourCells
      if (config.iterationsNumber > currentIteration) {
        val incomingCells = finished(currentIteration)
        if (incomingCells.size == neighbours.size + 1) {
          incomingCells.foreach(_.cells.foreach {
            case ((x, y), BufferCell(cell)) =>
              val currentCell = grid.cells(x)(y)
              val resolved = conflictResolver.resolveConflict(currentCell, cell)
              grid.cells(x)(y) = resolved
          })

          //clean buffers
          bufferZone.foreach { case (x, y) =>
            grid.cells(x)(y) = BufferCell(emptyCellFactory)
          }

          currentIteration += 1
          self ! StartIteration(currentIteration)
        }
      } else if (finished(currentIteration).size == neighbours.size + 1) {
        import scala.concurrent.duration._
        Thread.sleep(10.seconds.toMillis)
        context.system.terminate()
      }
  }

  private def notifyNeighbours(iteration: Long, grid: Grid): Unit = {
    self ! IterationPartFinished(id, id, iteration, Array.empty)
    neighbours.foreach { case (neighbourId, ngh) =>
      val bufferArray = ngh.position.bufferZone.iterator.map { case (x, y) => grid.cells(x)(y).asInstanceOf[BufferCell] }.toArray
      regionRef ! IterationPartFinished(id, neighbourId, iteration, bufferArray)
    }
  }

  private def logMetrics(iteration: Long, metrics: Metrics): Unit = {
    logger.info(WorkerActor.MetricsMarker, "{};{}", iteration.toString, metrics: Any)
  }
}

object WorkerActor {

  final val Name: String = "WorkerActor"

  final val MetricsMarker = MarkerFactory.getMarker("METRICS")

  private final class IncomingNeighbourCells(val cells: Vector[((Int, Int), BufferCell)]) extends AnyVal

  final case class NeighboursInitialized(id: WorkerId, neighbours: Vector[Neighbour])

  final case class StartIteration private(i: Long) extends AnyVal

  final case class SubscribeGridInfo(id: WorkerId)

  //sent to listeners
  final case class IterationPartFinished private(worker: WorkerId, to: WorkerId, iteration: Long, incomingBuffer: Array[BufferCell])

  final case class IterationPartMetrics private(workerId: WorkerId, iteration: Long, metrics: Metrics)

  def props[ConfigType <: XinukConfig](
    regionRef: => ActorRef,
    movesControllerFactory: (TreeSet[(Int, Int)], ConfigType) => MovesController,
    conflictResolver: ConflictResolver[ConfigType],
    emptyCellFactory: => SmellingCell = EmptyCell.Instance
  )(implicit config: ConfigType): Props = {
    Props(new WorkerActor(regionRef, movesControllerFactory, conflictResolver, emptyCellFactory))
  }

  private def idToShard(id: WorkerId)(implicit config: XinukConfig): String = (id.value % config.shardingMod).toString

  def extractShardId(implicit config: XinukConfig): ExtractShardId = {
    case NeighboursInitialized(id, _) => idToShard(id)
    case IterationPartFinished(_, id, _, _) => idToShard(id)
    case SubscribeGridInfo(id) => idToShard(id)
  }

  def extractEntityId: ExtractEntityId = {
    case msg@NeighboursInitialized(id, _) =>
      (id.value.toString, msg)
    case msg@IterationPartFinished(_, to, _, _) =>
      (to.value.toString, msg)
    case msg@SubscribeGridInfo(id) =>
      (id.value.toString, msg)
  }
}