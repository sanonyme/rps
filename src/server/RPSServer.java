package src.server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class RPSServer {
    private static final int DEFAULT_PORT = 5000;
    private static final int WINS_NEEDED = 3; // Number of wins needed to win a match
    private ServerSocket serverSocket;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<String, Integer> scores = new ConcurrentHashMap<>();
    private final Map<ClientHandler, ClientHandler> matches = new ConcurrentHashMap<>();
    private final Map<ClientHandler, String> moves = new ConcurrentHashMap<>();
    private final Map<ClientHandler, Integer> roundWins = new ConcurrentHashMap<>(); // Track wins in current match

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

                // Reset round wins for both players
                roundWins.put(player, 0);
                roundWins.put(client, 0);

                player.sendMessage("***You are now playing with " + client.getNickname() + "***");
                player.sendMessage("***First to win " + WINS_NEEDED + " rounds wins the match!***");
                player.sendMessage("***Choose your move: R (Rock), P (Paper), or S (Scissors)***");

                client.sendMessage("***You are now playing with " + player.getNickname() + "***");
                client.sendMessage("***First to win " + WINS_NEEDED + " rounds wins the match!***");
                client.sendMessage("***Choose your move: R (Rock), P (Paper), or S (Scissors)***");

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
                // Player wins the round
                int playerWins = roundWins.getOrDefault(player, 0) + 1;
                roundWins.put(player, playerWins);

                // Update overall score
                int newScore = scores.getOrDefault(player.getNickname(), 0) + 1;
                scores.put(player.getNickname(), newScore);

                player.sendMessage("***You won this round! (Round wins: " + playerWins + "/" + WINS_NEEDED + ")***");
                opponent.sendMessage("***You lost this round! (Round wins: " + roundWins.getOrDefault(opponent, 0) + "/"
                        + WINS_NEEDED + ")***");

                // Check if player has won the match
                if (playerWins >= WINS_NEEDED) {
                    player.sendMessage("***Congratulations! You've won the match!***");
                    opponent.sendMessage("***You've lost the match. Better luck next time!***");

                    // End the match
                    endMatch(player, opponent);
                } else {
                    // Continue the match - prompt for next round
                    promptNextRound(player, opponent);
                }
            } else if (result < 0) {
                // Opponent wins the round
                int opponentWins = roundWins.getOrDefault(opponent, 0) + 1;
                roundWins.put(opponent, opponentWins);

                // Update overall score
                int newScore = scores.getOrDefault(opponent.getNickname(), 0) + 1;
                scores.put(opponent.getNickname(), newScore);

                opponent.sendMessage(
                        "***You won this round! (Round wins: " + opponentWins + "/" + WINS_NEEDED + ")***");
                player.sendMessage("***You lost this round! (Round wins: " + roundWins.getOrDefault(player, 0) + "/"
                        + WINS_NEEDED + ")***");

                // Check if opponent has won the match
                if (opponentWins >= WINS_NEEDED) {
                    opponent.sendMessage("***Congratulations! You've won the match!***");
                    player.sendMessage("***You've lost the match. Better luck next time!***");

                    // End the match
                    endMatch(player, opponent);
                } else {
                    // Continue the match - prompt for next round
                    promptNextRound(player, opponent);
                }
            } else {
                // Draw
                player.sendMessage("***It's a draw for this round!***");
                opponent.sendMessage("***It's a draw for this round!***");

                // Prompt for next round
                promptNextRound(player, opponent);
            }

            // Clear the moves for the next round
            moves.remove(player);
            moves.remove(opponent);
        } else {
            player.sendMessage("***Waiting for opponent's move***");
        }
    }

    private void promptNextRound(ClientHandler player, ClientHandler opponent) {
        player.sendMessage("***Next round! Choose your move: R (Rock), P (Paper), or S (Scissors)***");
        opponent.sendMessage("***Next round! Choose your move: R (Rock), P (Paper), or S (Scissors)***");
    }

    private void endMatch(ClientHandler player, ClientHandler opponent) {
        // Reset match data
        matches.remove(player);
        matches.remove(opponent);
        roundWins.remove(player);
        roundWins.remove(opponent);
        moves.remove(player);
        moves.remove(opponent);

        // Let players know they can play again
        player.sendMessage("***Your overall score is " + scores.getOrDefault(player.getNickname(), 0) + "***");
        opponent.sendMessage("***Your overall score is " + scores.getOrDefault(opponent.getNickname(), 0) + "***");
        player.sendMessage("***Type 'play' to start a new game***");
        opponent.sendMessage("***Type 'play' to start a new game***");
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
            out.println("***When in a game, use: R (Rock), P (Paper), or S (Scissors) to make your move***");

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