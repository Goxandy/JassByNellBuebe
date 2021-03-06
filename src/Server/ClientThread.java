package Server;

import Common.Messages.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.logging.Logger;


public class ClientThread extends Thread {
    private Logger logger;
    // the socket to get messages from client
    Socket socket;
    ObjectInputStream in;
    ObjectOutputStream out;
    // my unique id (easier for deconnection)
    int id;
    // the Username of the Client
    String username = "";
    // message object to recieve message and its type
    Message receivedMessage;

    private String userName;

    // static ID to give each client an unique id
    private static int uniqueID;

    // Create a gameModel to react to changes in client
    private GameModel gameModel;

    // Create a serverModel to send messages to clients
    private ServerModel serverModel;


    // Constructor
    ClientThread(Socket socket) {
        // a unique id
        id = ++uniqueID;
        this.socket = socket;
        //Creating both Data Stream
        logger = ServerMain.sMain.getLogger();
        logger.info("Thread trying to create Object Input/Output Streams");
        gameModel = GameModel.getGameModel();
        serverModel = ServerMain.sMain.getServerModel();

        try {
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
            logger.info("IO-Streams successfully created");
        } catch (IOException e) {
            logger.info("Exception creating new Input/output Streams: " + e);
            return;
        }
    }

    // infinite loop to read and forward message
    public void run() {
        // to loop until LOGOUT
        boolean keepGoing = true;

        while (keepGoing) {
            try {
                receivedMessage = (Message) in.readObject();
            } catch (IOException e) {
                logger.info(userName + " Exception reading Streams: " + e);
                break;
            } catch (ClassNotFoundException e2) {
                logger.info(e2.toString());
                break;
            }

            // Different actions based on message type
            switch (receivedMessage.getType()) {
                case LOGOUT:
                    gameModel.removePlayer(receivedMessage.getPlayerName());
                    serverModel.removePlayerByName(receivedMessage.getPlayerName());
                    gameModel.updatePlayerID();
                    serverModel.broadcast(new Message(Message.Type.WHOISIN, serverModel.getPlayerNames()));
                    logger.info(username + " disconnected  with LOGOUT.");
                    keepGoing = false;
                    break;
                case LOGIN:
                    boolean accept = true;
                    if (gameModel.getPlayers().size() != 0) {
                        for (int i = 0; i < gameModel.getPlayers().size(); i++) {
                            if (gameModel.getPlayers().get(i).getPlayerName().equals(receivedMessage.getUserName())) {
                                accept = false;
                                writeMessage(new Message(Message.Type.LOGINREJECTED));
                            }
                        }
                        if (gameModel.getPlayers().size() >= serverModel.getALLOWEDPLAYERS()){
                            writeMessage(new Message(Message.Type.LOGINREJECTEDTOOMANYPLAYERS));
                            accept = false;
                        }
                    }
                    if(accept) {
                        addPlayerToGame();
                        writeMessage(new Message(Message.Type.LOGINACCEPTED, getUsername()));
                    }
                    break;
                case CHATMESSAGE:
                    serverModel.broadcast(new Message(Message.Type.CHATMESSAGE,getUsername()+": "+ receivedMessage.getMessage()));
                    break;

                case STARTGAME:
                    if (gameModel.checkIfFourPlayers()) {
                        serverModel.setGameModelResetted(false);
                        gameModel.setMaxPoints(receivedMessage.getMaxPoints());
                        gameModel.dealCardsToPlayer();
                        if (gameModel.getRoundCounter() == 0) {
                            gameModel.setRandomStartPlayer();
                        } else {
                            gameModel.moveToNextStartPlayer();
                        }
                        serverModel.broadcast(new Message(Message.Type.STARTGAME, gameModel.getCurrentPlayer().getPlayerName(), gameModel.getMaxPoints()));
                        for (int i = 0; i < gameModel.getNumOfPlayers(); i++) {
                            serverModel.broadcast(new Message(Message.Type.DEALCARDS, gameModel.getPlayers().get(i).getPlayerName(), gameModel.getPlayers().get(i).getHandCards()));
                        }
                        logger.info("The cards were sent to the clients");
                        gameModel.putPlayersInOrder();

                    }else{
                        writeMessage(new Message(Message.Type.STARTGAMEREJECTED, gameModel.getPlayers().size()));
                    }
                    break;
                case TRUMPF:
                    gameModel.setTrumpf(receivedMessage.getTrumpf());
                    gameModel.setEvaluationtype(receivedMessage.getGameType());
                    gameModel.createRound();
                    if (receivedMessage.getTrumpf() != null) {
                        serverModel.broadcast(new Message(Message.Type.TRUMPF, gameModel.getTrumpf().toString(), receivedMessage.getGameType()));
                    } else {
                        serverModel.broadcast(new Message(Message.Type.TRUMPF, null, receivedMessage.getGameType()));
                    }
                    serverModel.broadcast(new Message(Message.Type.YOURTURN, gameModel.getCurrentPlayer().getPlayerName()));
                    break;
                case EXITGAME:
                    serverModel.broadcast(new Message(Message.Type.EXITGAME,"Game cannot be continued, because one player has left the game"));
                    logger.info("Game has been terminated");
                    break;
                case CARDPLAYED:
                    // Logger in the server to see which player has played which card
                    for (int i = 0; i < gameModel.getPlayers().size(); i++) {
                        if (gameModel.getPlayers().get(i).getId() == id) {
                            logger.info(gameModel.getPlayers().get(i).getPlayerName()+" has played "+receivedMessage.getCardString());
                        }
                    }
                    // message to the clients to inform them about which player has played which card
                    serverModel.broadcast(new Message(Message.Type.CARDPLAYED, gameModel.getCurrentPlayer().getPlayerName(), receivedMessage.getCardString()));


                    gameModel.addTurnToCurrentRound(receivedMessage.getCardString());
                    gameModel.moveToNextPlayer();
                    // inform the clients that the next turn is happening
                    serverModel.broadcast(new Message(Message.Type.YOURTURN, gameModel.getCurrentPlayer().getPlayerName(), gameModel.getCurrentPlayer().blockCards(gameModel.getCurrentRound())));


                    if (gameModel.checkIfRoundFull()) {
                        gameModel.evaluateCurrentRound();
                        // send message to clients to inform them about the results in the last round
                        logger.info("the round is finished");
                        logger.info(gameModel.getCurrentWinner().getPlayerName()+" has won the round");
                        // This Thread has to sleep to let the clients see the played cards for a while
                        serverModel.broadcast(new Message(Message.Type.YOURTURN, "Nobody"));
                        try {
                            Thread.sleep(4000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        // send a message to the clients to inform the clients about the winner with points
                        for (Player p : gameModel.getPlayers()) {
                            if (gameModel.getCurrentWinner().getPlayerName().equals(p.getPlayerName())) {
                                serverModel.broadcast(new Message(Message.Type.ROUNDFINISHED, p.getPlayerName(), p.getPointCounter()));
                            }
                        }
                        serverModel.broadcast(new Message(Message.Type.YOURTURN, gameModel.getCurrentWinner().getPlayerName()));
                        gameModel.putPlayersInOrder();
                        gameModel.createRound();
                        logger.info("The next round has been created");
                    }

                    // Check if the maximum points chosen by user are reached, if so end game
                    if (gameModel.checkIfMaxPointsReached() && gameModel.getMaxPoints()!= 0) {
                        try {
                            Thread.sleep(4000);
                        } catch (InterruptedException e1) {
                            e1.printStackTrace();
                        }
                        serverModel.broadcast(new Message(Message.Type.MAXPOINTSREACHED, gameModel.getWinnerofGame()));
                    }

                    // inform the clients that the game is finished and let them know about the score
                    if (gameModel.checkIfGameFinished()) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        if (gameModel.getMaxPoints() == 0) {
                            serverModel.broadcast(new Message(Message.Type.MAXPOINTSREACHED, gameModel.getWinnerofGame()));
                        } else {
                            serverModel.broadcast(new Message(Message.Type.GAMEFINISHED, gameModel.getWinnerofGame()));
                            serverModel.broadcast(new Message(Message.Type.YOURTURN, "NOBODY"));
                            gameModel.dealCardsToPlayer();
                            for (int i = 0; i < gameModel.getNumOfPlayers(); i++) {
                                serverModel.broadcast(new Message(Message.Type.DEALCARDS, gameModel.getPlayers().get(i).getPlayerName(), gameModel.getPlayers().get(i).getHandCards()));
                            }
                            // Move to the next start playr so he can choose the trumpf for the next round
                            gameModel.moveToNextStartPlayer();
                            serverModel.broadcast(new Message(Message.Type.STARTGAME, gameModel.getCurrentPlayer().getPlayerName()));
                            logger.info("The cards were sent to the clients");
                            // Set Current player as current winner to get playing order correctly done
                            gameModel.setCurrentWinner(gameModel.getCurrentPlayer());
                            gameModel.putPlayersInOrder();
                        }

                    }
                    break;
                case BACKTOLOBBY:
                    if (!serverModel.isGameModelResetted()) {
                        gameModel.resetGameModel();
                        serverModel.resetPlayerNames();
                        serverModel.setGameModelResetted(true);
                    }
                    addPlayerToGame();
                    break;
                case CLIENTLOST:
                    keepGoing = false;
                    if (!serverModel.isGameModelResetted()) {
                        gameModel.resetGameModel();
                        serverModel.resetPlayerNames();
                        serverModel.setGameModelResetted(true);
                    }
                    break;
            }

        }
        serverModel.removeClient(id);
        close();
    }

    private void addPlayerToGame() {
        setUserName(receivedMessage.getUserName());
        serverModel.broadcast(new Message(Message.Type.CHATMESSAGE, getUsername() + " has joined the room"));
        serverModel.addPlayerByName(getUsername());
        gameModel.addPlayer(getUsername());
        for (int i = 0; i < gameModel.getPlayers().size(); i++) {
            if (gameModel.getPlayers().get(i).getPlayerName().equals(receivedMessage.getUserName())) {
                gameModel.getPlayers().get(i).setId(i+1);
            }
        }
        serverModel.broadcast(new Message(Message.Type.WHOISIN, serverModel.getPlayerNames()));
    }


    // method to close everything in the thread (ObjectOutputStream, ObjectInputStream, Socket)
    private void close() {
        try {
            if(out != null) out.close();
        } catch (Exception e) {}
        try {
            if (in != null) in.close();
        } catch (Exception e) {}
        try {
            if (socket != null) socket.close();
        } catch (Exception e) {}
    }

    // write a message to the client output stream, returns true if successful
    public boolean writeMessage(Message message) {
        // check if the socket is connected, if not close the thread
        if (!socket.isConnected()) {
            close();
            return false;
        }
        // the socket is connected, so write a message to the stream
        try {
            out.writeObject(message);
            out.flush();
            out.reset();
        } catch (IOException e){
            logger.info("Error sending message to " +getUsername());
            logger.info(e.toString());
        }
        return true;
    }

    public String getUsername(){
        return userName;
    }

    public void setUserName(String userName){
        this.userName = userName;
    }
}
