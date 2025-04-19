package src.server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class RPSServer {
    private static final int DEFAULT_PORT = 5000;
    private ServerSocket serverSocket;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<String, Integer> scores = new ConcurrentHashMap<>();
    private final Map<ClientHandler, ClientHandler> matches = new ConcurrentHashMap<>();
    private final Map<ClientHandler, String> moves = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        int port = DEFAULT_PORT;

        // Parse command-line arguments if provided
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number. Using default port " + DEFAULT_PORT);
            }
        }

        RPSServer server = new RPSServer();
        server.start(port);
    }

    public void start(int port) {
        try {
            try {
                serverSocket = new ServerSocket(port);
            } catch (BindException e) {
                System.err.println("Port " + port + " is already in use. Try using a different port.");
                System.err.println("Usage: java -cp bin src.server.RPSServer [port]");
                return;
            }

            System.out.println("RPS Server started on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getInetAddress().getHostAddress());

                ClientHandler clientHandler = new ClientHandler(clientSocket, this);
                new Thread(clientHandler).start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            stop();
        }
    }

    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized boolean registerClient(String nickname, ClientHandler handler) {
        if (clients.containsKey(nickname)) {
            return false;
        }
        clients.put(nickname, handler);
        scores.putIfAbsent(nickname, 0);
        return true;
    }

    public synchronized void removeClient(String nickname) {
        ClientHandler client = clients.remove(nickname);
        if (client != null) {
            ClientHandler opponent = matches.remove(client);
            if (opponent != null) {
                matches.remove(opponent);
                opponent.sendMessage("***Your opponent has disconnected***");
            }
        }
    }

    public synchronized String getPlayers() {
        return String.join(", ", clients.keySet());
    }

    public synchronized int getScore(String nickname) {
        return scores.getOrDefault(nickname, 0);
    }

    public synchronized void playGame(ClientHandler player) {
        // If player is already in a match, don't queue them again
        if (matches.containsKey(player)) {
            player.sendMessage("***You are already in a game***");
            return;
        }

        // Look for another player waiting for a match
        for (ClientHandler client : clients.values()) {
            if (client != player && !matches.containsKey(client) && client.isWaitingForMatch()) {
                // Match found
                matches.put(player, client);
                matches.put(client, player);

                moves.remove(player);
                moves.remove(client);

                player.sendMessage("***You are now playing with " + client.getNickname() + "***");
                client.sendMessage("***You are now playing with " + player.getNickname() + "***");

                client.setWaitingForMatch(false);
                return;
            }
        }

        // No match found, put player in waiting state
        player.setWaitingForMatch(true);
        player.sendMessage("***Waiting for another player to join***");
    }

    public synchronized void handleMove(ClientHandler player, String move) {
        ClientHandler opponent = matches.get(player);
        if (opponent == null) {
            player.sendMessage("***You are not in a game***");
            return;
        }

        moves.put(player, move);

        // If both players have made moves, determine the winner
        if (moves.containsKey(opponent)) {
            String playerMove = moves.get(player);
            String opponentMove = moves.get(opponent);

            player.sendMessage("***Your move: " + playerMove + ", Opponent's move: " + opponentMove + "***");
            opponent.sendMessage("***Your move: " + opponentMove + ", Opponent's move: " + playerMove + "***");

            int result = determineWinner(playerMove, opponentMove);

            if (result > 0) {
                // Player wins
                int newScore = scores.getOrDefault(player.getNickname(), 0) + 1;
                scores.put(player.getNickname(), newScore);
                player.sendMessage("***You have won. Your score is " + newScore + "***");
                opponent.sendMessage("***You have lost***");
            } else if (result < 0) {
                // Opponent wins
                int newScore = scores.getOrDefault(opponent.getNickname(), 0) + 1;
                scores.put(opponent.getNickname(), newScore);
                opponent.sendMessage("***You have won. Your score is " + newScore + "***");
                player.sendMessage("***You have lost***");
            } else {
                // Draw
                player.sendMessage("***It's a draw***");
                opponent.sendMessage("***It's a draw***");
            }

            // Reset the match
            matches.remove(player);
            matches.remove(opponent);
            moves.remove(player);
            moves.remove(opponent);
        } else {
            player.sendMessage("***Waiting for opponent's move***");
        }
    }

    private int determineWinner(String playerMove, String opponentMove) {
        if (playerMove.equalsIgnoreCase(opponentMove)) {
            return 0; // Draw
        }

        if (playerMove.equalsIgnoreCase("R") && opponentMove.equalsIgnoreCase("S") ||
                playerMove.equalsIgnoreCase("P") && opponentMove.equalsIgnoreCase("R") ||
                playerMove.equalsIgnoreCase("S") && opponentMove.equalsIgnoreCase("P")) {
            return 1; // Player wins
        } else {
            return -1; // Opponent wins
        }
    }
}

class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private final RPSServer server;
    private PrintWriter out;
    private BufferedReader in;
    private String nickname;
    private boolean waitingForMatch = false;

    public ClientHandler(Socket socket, RPSServer server) {
        this.clientSocket = socket;
        this.server = server;
    }

    public String getNickname() {
        return nickname;
    }

    public boolean isWaitingForMatch() {
        return waitingForMatch;
    }

    public void setWaitingForMatch(boolean waiting) {
        this.waitingForMatch = waiting;
    }

    public void sendMessage(String message) {
        out.println(message);
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            // Request nickname
            out.println("***Choose a nickname***");
            nickname = in.readLine();

            // Register client
            while (!server.registerClient(nickname, this)) {
                out.println("***Nickname already taken. Choose another one***");
                nickname = in.readLine();
            }

            out.println("***Welcome " + nickname
                    + "! Type 'play' to start a game, 'score' to see your score, or 'players' to list online players***");

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                if (inputLine.equalsIgnoreCase("play")) {
                    server.playGame(this);
                } else if (inputLine.equalsIgnoreCase("score")) {
                    int score = server.getScore(nickname);
                    out.println("***Your score is " + score + "***");
                } else if (inputLine.equalsIgnoreCase("players")) {
                    String players = server.getPlayers();
                    out.println("***Players online: " + players + "***");
                } else if (inputLine.equalsIgnoreCase("R") ||
                        inputLine.equalsIgnoreCase("P") ||
                        inputLine.equalsIgnoreCase("S")) {
                    server.handleMove(this, inputLine.toUpperCase());
                } else {
                    out.println("***Invalid command. Available commands: play, score, players, R, P, S***");
                }
            }
        } catch (IOException e) {
            System.out.println("Error handling client: " + e.getMessage());
        } finally {
            try {
                if (nickname != null) {
                    server.removeClient(nickname);
                }
                in.close();
                out.close();
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}