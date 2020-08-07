package it.unibo.scalapacman.client.controller

import java.util.concurrent.Semaphore

import grizzled.slf4j.Logging
import it.unibo.scalapacman.client.communication.PacmanRestClient
import Action.{END_GAME, EXIT_APP, MOVEMENT, RESET_KEY_MAP, SAVE_KEY_MAP, START_GAME, SUBSCRIBE_TO_GAME_UPDATES}
import it.unibo.scalapacman.client.event.{GameUpdate, PacmanPublisher, PacmanSubscriber}
import it.unibo.scalapacman.client.input.JavaKeyBinding.DefaultJavaKeyBinding
import it.unibo.scalapacman.client.input.KeyMap
import it.unibo.scalapacman.client.map.PacmanMap
import it.unibo.scalapacman.client.model.GameModel
import it.unibo.scalapacman.common.MoveCommandType.MoveCommandType
import it.unibo.scalapacman.common.{Command, CommandType, CommandTypeHolder, JSONConverter, MapUpdater, MoveCommandTypeHolder, UpdateModelDTO}
import it.unibo.scalapacman.lib.model.Map

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.util.{Failure, Success}

trait Controller {
  /**
   * Gestisce le azioni dell'utente
   *
   * @param action tipo di azione avvenuta
   * @param param  parametro che arricchisce l'azione avvenuta con ulteriori informazioni
   */
  def handleAction(action: Action, param: Option[Any]): Unit

  /**
   * Recupera l'ultima azione dell'utente avvenuta in partita
   *
   * @return l'ultima azione dell'utente avvenuta in partita
   */
  def userAction: Option[MoveCommandType]

  /**
   * Recupera il modello di gioco
   *
   * @return il modello di gioco
   */
  def model: GameModel
}

object Controller {
  def apply(pacmanRestClient: PacmanRestClient): Controller = ControllerImpl(pacmanRestClient)
}

private case class ControllerImpl(pacmanRestClient: PacmanRestClient) extends Controller with Logging {
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  val UNKNOWN_ACTION = "Azione non riconosciuta"
  /**
   * Mappatura dei tasti iniziale, prende i valori di default di DefaultJavaKeyBinding.
   * Viene utilizzata in PlayView per applicare la mappatura iniziale della board di gioco.
   * Viene utilizzata in OptionsView per inizializzare i campi di testo.
   */
  val _defaultKeyMap: KeyMap = KeyMap(DefaultJavaKeyBinding.UP, DefaultJavaKeyBinding.DOWN, DefaultJavaKeyBinding.RIGHT, DefaultJavaKeyBinding.LEFT)
  // TODO keyMap, gameId e prevUserAction in un oggetto che definisce il Model dell'applicazione, insieme a Map/Ghosts/Pacman?
  var _gameId: Option[String] = None
  var _prevUserAction: Option[MoveCommandType] = None
  val _publisher: PacmanPublisher = PacmanPublisher()
  var _model: GameModel = GameModel(None, _defaultKeyMap, Map.classic)

  def handleAction(action: Action, param: Option[Any]): Unit = action match {
    case START_GAME => evalStartGame(_model.gameId)
    case END_GAME => evalEndGame(_model.gameId)
    case SUBSCRIBE_TO_GAME_UPDATES => evalSubscribeToGameUpdates(param.asInstanceOf[Option[PacmanSubscriber]])
    case MOVEMENT => evalMovement(param.asInstanceOf[Option[MoveCommandType]], _prevUserAction)
    case SAVE_KEY_MAP => evalSaveKeyMap(param.asInstanceOf[Option[KeyMap]])
    case RESET_KEY_MAP => evalSaveKeyMap(Some(_defaultKeyMap))
    case EXIT_APP => evalExitApp()
    case _ => error(UNKNOWN_ACTION)
  }

  def userAction: Option[MoveCommandType] = _prevUserAction

  def model: GameModel = _model

  private def evalStartGame(gameId: Option[String]): Unit = gameId match {
    case None => pacmanRestClient.startGame onComplete {
      case Success(value) =>
        _prevUserAction = None
        info(s"Partita creata con successo: id $value") // scalastyle:ignore multiple.string.literals
        _model = _model.copy(gameId = Some(value))
        new Thread(webSocketRunnable).start()
        pacmanRestClient.openWS(value, handleWebSocketMessage)
      case Failure(exception) => error(s"Errore nella creazione della partita: ${exception.getMessage}") // scalastyle:ignore multiple.string.literals
    }
    case Some(_) => error("Impossibile creare nuova partita quando ce n'è già una in corso")
  }

  private def evalEndGame(gameId: Option[String]): Unit = {
    webSocketRunnable.terminate()
    gameId match {
      case Some(id) => pacmanRestClient.endGame(id) onComplete {
        case Success(message) =>
          info(s"Partita $id terminata con successo: $message")
          _model = _model.copy(gameId = None)
        case Failure(exception) =>
          error(s"Errore nella terminazione della partita: ${exception.getMessage}")
          _model = _model.copy(gameId = None)
      }
      case None => info("Nessuna partita da dover terminare")
    }
  }

  private def evalSubscribeToGameUpdates(maybeSubscriber: Option[PacmanSubscriber]): Unit = maybeSubscriber match {
    case None => error("Subscriber mancante, impossibile registrarsi")
    case Some(subscriber) => _publisher.subscribe(subscriber)
  }

  // TODO: non passare publisher ma un handler per il messaggio deserializzato
  val webSocketRunnable = new WebSocketConsumer(updateFromServer)

  // TODO: CREARE MODELLO CHE MANTIENE LA MAPPA DI GIOELE AGGIORNATA E I MODELLI DEI FANTASMI E DI PACMAN
  /* TODO:
      Primo step -> Aggiornare il mio modello con quello ricevuto dal server (utilizo funzioni in common che aggiornano la mappa coi pellet e i frutti)
      Secondo step -> Passare le informazioni del model MIO al Subscriber che si dovrà preoccupare di convertire il model in List[List[String]]
      Bonus step -> Chi usa il subscriber dovrà convertire il modello, da solo nì, se usa una classe esterna tanto meglio!! (consigliatissimo)
  */
  private def handleWebSocketMessage(message: String): Unit = webSocketRunnable.addMessage(message)

  private def evalMovement(newUserAction: Option[MoveCommandType], prevUserAction: Option[MoveCommandType]): Unit = (newUserAction, prevUserAction) match {
    case (Some(newInt), Some(prevInt)) if newInt == prevInt => info("Non invio aggiornamento al server")
    case (None, _) => error("Nuova azione utente è None")
    case _ =>
      info("Invio aggiornamento al server")
      debug(s"Invio al server l'azione ${newUserAction.get} dell'utente")
      _prevUserAction = newUserAction
      sendMovement(newUserAction.get)
  }

  private def sendMovement(moveCommandType: MoveCommandType): Unit = _gameId match {
    case None => info("Nessuna partita in corso, non invio informazione movimento al server")
    case _ => pacmanRestClient.sendOverWebSocket(
      JSONConverter.toJSON(
        Command(
          CommandTypeHolder(CommandType.MOVE),
          Some(JSONConverter.toJSON(MoveCommandTypeHolder(moveCommandType)))
        )
      )
    )
  }

  private def evalSaveKeyMap(maybeKeyMap: Option[KeyMap]): Unit = maybeKeyMap match {
    case None => error("Configurazione tasti non valida")
    case Some(keyMap) =>
      _model = _model.copy(keyMap = keyMap)
      info(s"Nuova configurazione dei tasti salvata $keyMap") // scalastyle:ignore multiple.string.literals
  }

  private def evalExitApp(): Unit = {
    evalEndGame(_model.gameId)
    info("Chiusura dell'applicazione")
    System.exit(0)
  }

  private def updateFromServer(model: UpdateModelDTO): Unit = {
    _model = _model.copy(map = MapUpdater.update(_model.map, model.dots, model.fruit))
  }
}

class WebSocketConsumer(f: UpdateModelDTO => Unit) extends Runnable with Logging {
  val semaphore = new Semaphore(0)
  private var message: Option[String] = None
  private var running = true

  def addMessage(msg: String): Unit = {
    message = Some(msg)
    if (semaphore.availablePermits() == 0) semaphore.release()
  }

  private def getMessage: Option[UpdateModelDTO] = {
    semaphore.acquire()
    message flatMap(JSONConverter.fromJSON[UpdateModelDTO](_))
  }

  def terminate(): Unit = running = false

  override def run(): Unit = {
    message = None
    semaphore tryAcquire semaphore.availablePermits()
    running = true
    while (running) {
      getMessage match {
        case None => error("Aggiornamento dati dal server non valido")
        case Some(model) => debug(model); f(model)
      }
    }
  }
}
