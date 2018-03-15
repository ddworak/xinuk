package pl.edu.agh.fortwist.model

import pl.edu.agh.xinuk.model.Cell.SmellArray
import pl.edu.agh.xinuk.model._

final case class Foraminifera(energy: Energy, lifespan: Long)

final case class FortwistCell(smell: SmellArray, foraminiferas: Vector[Foraminifera], algae: Energy) extends SmellingCell {
  override type Self = FortwistCell

  override def withSmell(smell: SmellArray): FortwistCell = copy(smell = smell)
}