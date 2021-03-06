package it.unibo.scalapacman.common

import it.unibo.scalapacman.lib.math.Point2D
import it.unibo.scalapacman.lib.model.Character.{Ghost, Pacman}
import it.unibo.scalapacman.lib.model.GhostType.{BLINKY, CLYDE, INKY, PINKY}
import it.unibo.scalapacman.lib.model.Map.MapIndexes
import it.unibo.scalapacman.lib.model.{Dot, Fruit, GameState}


case class GameEntityDTO(gameCharacterHolder: GameCharacterHolder, position: Point2D, speed: Double, isDead: Boolean, dir: DirectionHolder) {
  def toGhost: Option[Ghost] = gameCharacterHolder.gameChar match {
    case GameCharacter.INKY   => Some(Ghost(INKY, position, speed, dir.direction, isDead))
    case GameCharacter.BLINKY => Some(Ghost(BLINKY, position, speed, dir.direction, isDead))
    case GameCharacter.CLYDE  => Some(Ghost(CLYDE, position, speed, dir.direction, isDead))
    case GameCharacter.PINKY  => Some(Ghost(PINKY, position, speed, dir.direction, isDead))
    case _ => None
  }
  def toPacman: Option[Pacman] =
    if(gameCharacterHolder.gameChar == GameCharacter.PACMAN) Some(Pacman(position, speed, dir.direction, isDead)) else None
}

case class DotDTO(dotHolder: DotHolder, pos: MapIndexes)
object DotDTO {
  implicit def rawToDotDTO(raw: (MapIndexes, Dot.Dot)): DotDTO = DotDTO(DotHolder(raw._2), raw._1)
}

case class FruitDTO(fruitHolder: FruitHolder, pos: MapIndexes)
object FruitDTO {
  implicit def rawToFruitDTO(raw: (MapIndexes, Fruit.Fruit)): FruitDTO = FruitDTO(FruitHolder(raw._2), raw._1)
}

case class GameStateDTO(score: Int, ghostInFear: Boolean, pacmanEmpowered: Boolean, levelStateHolder: LevelStateHolder)
object GameStateDTO {
  implicit def gameStateToDTO(gs: GameState): GameStateDTO =
    GameStateDTO(gs.score, gs.ghostInFear, gs.pacmanEmpowered, LevelStateHolder(gs.levelState))

  implicit def dtoToGameState(dto: GameStateDTO): GameState =
    GameState(dto.score, dto.ghostInFear, dto.pacmanEmpowered, dto.levelStateHolder.levelState)
}

case class UpdateModelDTO(
                           gameEntities: Set[GameEntityDTO],
                           state: GameStateDTO,
                           dots: Set[DotDTO],
                           fruit: Option[FruitDTO]
                      )
