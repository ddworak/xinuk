package pl.edu.agh.torch

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.readers.ValueReader
import pl.edu.agh.torch.algorithm.TorchMovesController
import pl.edu.agh.torch.model.parallel.TorchConflictResolver
import pl.edu.agh.xinuk.Simulation
import pl.edu.agh.xinuk.config.GuiType
import pl.edu.agh.xinuk.model.{Energy, Signal}

object Main extends LazyLogging {
  private val configPrefix = "torch"
  private val metricHeaders = Vector(
    "peopleCount",
    "fireCount",
    "escapeCount",
    "peopleDeaths"
  )

  implicit val guiTypeReader: ValueReader[GuiType] =
    (config: Config, path: String) => GuiType.byName(config.getString(path))
  implicit val signalReader: ValueReader[Signal] =
    (config: Config, path: String) => Signal(config.getNumber(path).doubleValue())
  implicit val energyReader: ValueReader[Energy] =
    (config: Config, path: String) => Energy(config.getNumber(path).doubleValue())

  def main(args: Array[String]): Unit = {
    import net.ceedubs.ficus.Ficus._
    import net.ceedubs.ficus.readers.ArbitraryTypeReader._
    new Simulation(configPrefix, metricHeaders, TorchConflictResolver)(new TorchMovesController(_)(_)).start()
  }

}

