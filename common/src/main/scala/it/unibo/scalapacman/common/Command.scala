package it.unibo.scalapacman.common

import it.unibo.scalapacman.common.CommandType.CommandType


object CommandType extends Enumeration {
  type CommandType = Value
  val MOVE, PAUSE = Value
}

object MoveCommandType extends Enumeration {
  type MoveCommandType = Value
  val UP, DOWN, RIGHT, LEFT, NONE = Value
}

case class Command(id: CommandTypeHolder, data:Option[String])

