package src.server;

import java.io.*;
import java.net.*;
// Explicitly import Inet4Address for better network interface handling
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.*;

public class RPSServer {
    private static final int DEFAULT_PORT = 5000;
    private static final int WINS_NEEDED = 3; // Wins needed for a match
    private static final String SCORES_FILE = "player_scores.dat"; // Saved scores
    private static final int HEARTBEAT_PORT = 5001; // For auto-discovery
    private static final int HEARTBEAT_INTERVAL = 3000; // 3 seconds between pings
    private ServerSocket serverSocket;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<String, Integer> scores = new HashMap<>();
    private final Map<ClientHandler, ClientHandler> matches = new ConcurrentHashMap<>();
    private final Map<ClientHandler, String> moves = new ConcurrentHashMap<>();
    private final Map<ClientHandler, Integer> roundWins = new ConcurrentHashMap<>(); // Current match wins
    private final Map<ClientHandler, Boolean> coffeeBetMode = new ConcurrentHashMap<>(); // Coffee mode status
    private final Map<ClientHandler, ClientHandler> pendingCoffeeBetRequests = new ConcurrentHashMap<>(); // Coffee bet
                                                                                                          // requests
    private HeartbeatBroadcaster heartbeatBroadcaster;

    // Invitation tracking
    private final Map<ClientHandler, ClientHandler> pendingInvitations = new ConcurrentHashMap<>(); // Sender->receiver
    private final Map<ClientHandler, List<ClientHandler>> queuedInvitations = new ConcurrentHashMap<>(); // For busy
                                                                                                         // players

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
        server.loadScores(); // Load scores from file
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

            // Start heartbeat broadcasting
            heartbeatBroadcaster = new HeartbeatBroadcaster(port);
            new Thread(heartbeatBroadcaster).start();
            System.out.println("Server discovery heartbeat started on port " + HEARTBEAT_PORT);

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
            // Save scores before shutting down
            saveScores();

            // Stop the heartbeat broadcaster if it's running
            if (heartbeatBroadcaster != null) {
                heartbeatBroadcaster.stop();
            }

            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized boolean registerClient(String nickname, ClientHandler handler) {
        if (nickname == null) {
            return false;
        }
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
            // Save scores when a client disconnects
            saveScores();
        }
    }

    public synchronized String getPlayers() {
        return String.join(", ", clients.keySet());
    }

    public synchronized int getScore(String nickname) {
        return scores.getOrDefault(nickname, 0);
    }

    public synchronized void setScore(String nickname, int score) {
        scores.put(nickname, score);
        // Save scores whenever they are updated
        saveScores();
    }

    private synchronized void incrementScore(String nickname) {
        int newScore = scores.getOrDefault(nickname, 0) + 1;
        scores.put(nickname, newScore);
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
                // Check if the waiting player has coffee bet mode enabled
                if (coffeeBetMode.getOrDefault(client, false)) {
                    // Ask this player if they want to play a coffee bet game
                    player.sendMessage("***Player " + client.getNickname()
                            + " wants to play a Coffee Bet game (loser buys coffee)***");
                    player.sendMessage("***Do you accept the Coffee Bet challenge? (y/n)***");
                    pendingCoffeeBetRequests.put(player, client);
                    return;
                }

                // Match found (regular game)
                startMatch(player, client);
                return;
            }
        }

        // No match found, put player in waiting state
        player.setWaitingForMatch(true);
        player.sendMessage("***Waiting for another player to join***");
    }

    public synchronized void playCoffeeBetGame(ClientHandler player) {
        // If player is already in a match, don't queue them again
        if (matches.containsKey(player)) {
            player.sendMessage("***You are already in a game***");
            return;
        }

        // Set coffee bet mode for this player
        coffeeBetMode.put(player, true);
        player.sendMessage("***Coffee Bet Mode enabled! Winner gets a coffee!***");

        // Look for another player waiting for a match
        for (ClientHandler client : clients.values()) {
            if (client != player && !matches.containsKey(client) && client.isWaitingForMatch()) {
                // If the other player doesn't have coffee bet mode, ask them
                if (!coffeeBetMode.getOrDefault(client, false)) {
                    client.sendMessage("***Player " + player.getNickname()
                            + " wants to play a Coffee Bet game (loser buys coffee)***");
                    client.sendMessage("***Do you accept the Coffee Bet challenge? (y/n)***");
                    pendingCoffeeBetRequests.put(client, player);
                    return;
                }

                // Both players have coffee bet mode, start match
                startMatch(player, client);
                return;
            }
        }

        // No match found, put player in waiting state
        player.setWaitingForMatch(true);
        player.sendMessage("***Waiting for another player to join with Coffee Bet Mode***");
    }

    public synchronized void invitePlayer(ClientHandler inviter, String targetNickname) {
        // Check if inviter is already in a match
        if (matches.containsKey(inviter)) {
            inviter.sendMessage("***You are already in a game***");
            return;
        }

        // Check if target exists
        if (!clients.containsKey(targetNickname)) {
            inviter.sendMessage("***Player '" + targetNickname + "' not found***");
            return;
        }

        // Don't allow self-invites
        if (inviter.getNickname().equals(targetNickname)) {
            inviter.sendMessage("***You cannot invite yourself***");
            return;
        }

        ClientHandler target = clients.get(targetNickname);

        // Check if target is already in a match
        if (matches.containsKey(target)) {
            inviter.sendMessage(
                    "***Player '" + targetNickname + "' is currently in a game. Your invitation will be queued.***");

            // Queue the invitation
            queuedInvitations.computeIfAbsent(target, k -> new ArrayList<>()).add(inviter);
            return;
        }

        // Send invitation
        inviter.sendMessage("***Invitation sent to " + targetNickname + "***");
        target.sendMessage("***You have an invitation from " + inviter.getNickname() + ", play game? (y/n)***");

        // Record the pending invitation
        pendingInvitations.put(inviter, target);
    }

    public synchronized void handleInvitationResponse(ClientHandler responder, boolean accepted) {
        // Find who invited this player
        ClientHandler inviter = null;
        for (Map.Entry<ClientHandler, ClientHandler> entry : pendingInvitations.entrySet()) {
            if (entry.getValue() == responder) {
                inviter = entry.getKey();
                break;
            }
        }

        if (inviter == null) {
            responder.sendMessage("***You don't have any pending invitations***");
            return;
        }

        // Remove the pending invitation
        pendingInvitations.remove(inviter);

        // Both players must not be in other matches
        if (matches.containsKey(inviter) || matches.containsKey(responder)) {
            if (!matches.containsKey(responder)) {
                responder.sendMessage("***Inviter is already in another game***");
            }
            if (!matches.containsKey(inviter)) {
                inviter.sendMessage("***Invited player is already in another game***");
            }
            return;
        }

        if (accepted) {
            // Start the match
            inviter.sendMessage("***" + responder.getNickname() + " accepted your invitation***");
            startMatch(inviter, responder);
        } else {
            // Invitation declined
            inviter.sendMessage("***" + responder.getNickname() + " declined your invitation***");
            responder.sendMessage("***You declined the invitation***");
        }
    }

    private synchronized void checkQueuedInvitations(ClientHandler player) {
        List<ClientHandler> inviters = queuedInvitations.get(player);
        if (inviters != null && !inviters.isEmpty()) {
            // Get the first invitation in the queue
            ClientHandler inviter = inviters.remove(0);

            // Update the queue
            if (inviters.isEmpty()) {
                queuedInvitations.remove(player);
            } else {
                queuedInvitations.put(player, inviters);
            }

            // Check if inviter is still available
            if (!matches.containsKey(inviter)) {
                // Notify about the queued invitation
                player.sendMessage(
                        "***You have a queued invitation from " + inviter.getNickname() + ", play game? (y/n)***");
                inviter.sendMessage("***Your queued invitation to " + player.getNickname() + " is now active***");

                // Record the pending invitation
                pendingInvitations.put(inviter, player);
            }
        }
    }

    private synchronized void startMatch(ClientHandler player1, ClientHandler player2) {
        matches.put(player1, player2);
        matches.put(player2, player1);

        moves.remove(player1);
        moves.remove(player2);

        // Reset round wins for both players
        roundWins.put(player1, 0);
        roundWins.put(player2, 0);

        // Check if this is a coffee bet match
        boolean isCoffeeBet = coffeeBetMode.getOrDefault(player1, false) &&
                coffeeBetMode.getOrDefault(player2, false);

        if (isCoffeeBet) {
            player1.sendMessage("***Coffee Bet Mode enabled!***");
            player2.sendMessage("***Coffee Bet Mode enabled!***");
        }

        player1.sendMessage("***You are now playing with " + player2.getNickname() + "***");
        player1.sendMessage("***First to win " + WINS_NEEDED + " rounds wins the match!***");
        player1.sendMessage("***Choose your move: R (Rock), P (Paper), or S (Scissors)***");

        player2.sendMessage("***You are now playing with " + player1.getNickname() + "***");
        player2.sendMessage("***First to win " + WINS_NEEDED + " rounds wins the match!***");
        player2.sendMessage("***Choose your move: R (Rock), P (Paper), or S (Scissors)***");

        player2.setWaitingForMatch(false);
        player1.setWaitingForMatch(false);
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
                incrementScore(player.getNickname());

                player.sendMessage("***You won this round! (Round wins: " + playerWins + "/" + WINS_NEEDED + ")***");
                opponent.sendMessage("***You lost this round! (Round wins: " + roundWins.getOrDefault(opponent, 0) + "/"
                        + WINS_NEEDED + ")***");

                // Check if player has won the match
                if (playerWins >= WINS_NEEDED) {
                    player.sendMessage("***Congratulations! You've won the match!***");
                    opponent.sendMessage("***You've lost the match. Better luck next time!***");

                    // Save scores when a match is completed
                    saveScores();

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
                incrementScore(opponent.getNickname());

                opponent.sendMessage(
                        "***You won this round! (Round wins: " + opponentWins + "/" + WINS_NEEDED + ")***");
                player.sendMessage("***You lost this round! (Round wins: " + roundWins.getOrDefault(player, 0) + "/"
                        + WINS_NEEDED + ")***");

                // Check if opponent has won the match
                if (opponentWins >= WINS_NEEDED) {
                    opponent.sendMessage("***Congratulations! You've won the match!***");
                    player.sendMessage("***You've lost the match. Better luck next time!***");

                    // Save scores when a match is completed
                    saveScores();

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

        // Reset coffee bet mode
        coffeeBetMode.remove(player);
        coffeeBetMode.remove(opponent);

        // Let players know they can play again
        player.sendMessage("***Your overall score is " + scores.getOrDefault(player.getNickname(), 0) + "***");
        opponent.sendMessage("***Your overall score is " + scores.getOrDefault(opponent.getNickname(), 0) + "***");
        player.sendMessage("***Type 'play' to start a new game***");
        opponent.sendMessage("***Type 'play' to start a new game***");

        // Check if there are queued invitations for either player
        checkQueuedInvitations(player);
        checkQueuedInvitations(opponent);
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

    // Load scores from file
    private synchronized void loadScores() {
        File file = new File(SCORES_FILE);
        if (file.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                @SuppressWarnings("unchecked")
                Map<String, Integer> loadedScores = (Map<String, Integer>) ois.readObject();
                scores.clear();
                scores.putAll(loadedScores);
                System.out.println("Loaded " + scores.size() + " player scores from file.");
            } catch (Exception e) {
                System.err.println("Error loading scores: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // Save scores to file
    private synchronized void saveScores() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(SCORES_FILE))) {
            oos.writeObject(new HashMap<>(scores));
            System.out.println("Saved " + scores.size() + " player scores to file.");
        } catch (Exception e) {
            System.err.println("Error saving scores: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public synchronized void handleCoffeeBetResponse(ClientHandler responder, boolean accepted) {
        // Find who requested the coffee bet
        ClientHandler requester = pendingCoffeeBetRequests.get(responder);
        if (requester == null) {
            responder.sendMessage("***You don't have any pending coffee bet challenges***");
            return;
        }

        // Remove the pending request
        pendingCoffeeBetRequests.remove(responder);

        // Both players must not be in other matches
        if (matches.containsKey(requester) || matches.containsKey(responder)) {
            if (!matches.containsKey(responder)) {
                responder.sendMessage("***Requester is already in another game***");
            }
            if (!matches.containsKey(requester)) {
                requester.sendMessage("***Player is already in another game***");
            }
            return;
        }

        if (accepted) {
            // Set coffee bet mode for the responder as well
            coffeeBetMode.put(responder, true);

            // Start the match with coffee bet mode
            requester.sendMessage("***" + responder.getNickname() + " accepted your coffee bet challenge!***");
            responder.sendMessage("***You accepted the coffee bet challenge!***");
            startMatch(requester, responder);
        } else {
            // Invitation declined
            requester.sendMessage("***" + responder.getNickname() + " declined your coffee bet challenge***");
            responder.sendMessage("***You declined the coffee bet challenge***");

            // Reset coffee bet mode for requester if they initiated specifically for this
            // challenge
            if (requester.isWaitingForMatch()) {
                coffeeBetMode.remove(requester);
                requester.setWaitingForMatch(false);
                requester.sendMessage("***Coffee Bet Mode disabled***");
            }
        }
    }

    public synchronized boolean hasPendingCoffeeBetRequest(ClientHandler client) {
        return pendingCoffeeBetRequests.containsKey(client);
    }

    // Inner class for broadcasting server heartbeats
    private class HeartbeatBroadcaster implements Runnable {
        private volatile boolean running = true;
        private final int serverPort;
        private DatagramSocket socket;

        public HeartbeatBroadcaster(int serverPort) {
            this.serverPort = serverPort;
        }

        @Override
        public void run() {
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);

                while (running) {
                    broadcastHeartbeat();
                    Thread.sleep(HEARTBEAT_INTERVAL);
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("Heartbeat broadcaster error: " + e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        }

        private void broadcastHeartbeat() throws IOException {
            // Create a heartbeat message with server IP and port
            InetAddress localAddress = getLocalAddress();
            String message = "RPS_SERVER:" + localAddress.getHostAddress() + ":" + serverPort;
            byte[] buffer = message.getBytes();

            // Broadcast to the local network
            InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, broadcastAddress, HEARTBEAT_PORT);
            socket.send(packet);
        }

        private InetAddress getLocalAddress() {
            try {
                // Try to find a non-loopback address
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = interfaces.nextElement();
                    // Skip loopback and inactive interfaces
                    if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                        continue;
                    }

                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        // Prefer IPv4 addresses
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr;
                        }
                    }
                }

                // If no suitable address was found, use the default
                return InetAddress.getLocalHost();
            } catch (Exception e) {
                System.err.println("Could not determine local address: " + e.getMessage());
                // Fallback to a loopback address
                try {
                    return InetAddress.getByName("127.0.0.1");
                } catch (UnknownHostException ex) {
                    throw new RuntimeException("Could not create loopback address", ex);
                }
            }
        }

        public void stop() {
            running = false;
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
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
            out.println("***You can also invite a specific player with 'play NICKNAME'***");

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                if (inputLine.equalsIgnoreCase("play")) {
                    server.playGame(this);
                } else if (inputLine.equalsIgnoreCase("play coffee")) {
                    server.playCoffeeBetGame(this);
                } else if (inputLine.toLowerCase().startsWith("play ")) {
                    // Handle targeted invitation (play NICKNAME)
                    String targetNickname = inputLine.substring(5).trim();
                    server.invitePlayer(this, targetNickname);
                } else if (inputLine.equalsIgnoreCase("y") || inputLine.equalsIgnoreCase("yes")) {
                    // Check if this is a response to a coffee bet challenge
                    if (server.hasPendingCoffeeBetRequest(this)) {
                        server.handleCoffeeBetResponse(this, true);
                    } else {
                        // Otherwise, it's a regular invitation response
                        server.handleInvitationResponse(this, true);
                    }
                } else if (inputLine.equalsIgnoreCase("n") || inputLine.equalsIgnoreCase("no")) {
                    // Check if this is a response to a coffee bet challenge
                    if (server.hasPendingCoffeeBetRequest(this)) {
                        server.handleCoffeeBetResponse(this, false);
                    } else {
                        // Otherwise, it's a regular invitation response
                        server.handleInvitationResponse(this, false);
                    }
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
                    out.println(
                            "***Invalid command. Available commands: play, play coffee, play NICKNAME, y/n (for invitations), score, players, R, P, S***");
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