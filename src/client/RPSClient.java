package src.client;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class RPSClient {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Scanner scanner;
    private volatile boolean running = true;
    private static final int HEARTBEAT_PORT = 5001; // Must match server's heartbeat port
    private static final int DISCOVERY_TIMEOUT = 5000; // Time to wait for server responses in ms
    private final Map<String, ServerInfo> discoveredServers = new ConcurrentHashMap<>();
    private final AtomicBoolean discoveryActive = new AtomicBoolean(false);
    private volatile boolean nicknameAccepted = false; // Flag to track if nickname has been accepted

    public static void main(String[] args) {
        RPSClient client = new RPSClient();
        client.start();
    }

    public void start() {
        scanner = new Scanner(System.in);

        System.out.println("Rock-Paper-Scissors Game Client");
        System.out.println("==============================");
        System.out.println("1. Connect to server manually");
        System.out.println("2. Discover servers automatically");
        System.out.print("Choose an option (1-2): ");

        String option = scanner.nextLine();

        if ("2".equals(option)) {
            discoverServers();

            if (discoveredServers.isEmpty()) {
                System.out.println("No servers found. Falling back to manual connection.");
                connectManually();
            } else {
                connectToDiscoveredServer();
            }
        } else {
            connectManually();
        }
    }

    private void connectManually() {
        System.out.print("Enter server IP: ");
        String serverIP = scanner.nextLine();

        System.out.print("Enter server port: ");
        int serverPort = Integer.parseInt(scanner.nextLine());

        connectToServer(serverIP, serverPort);
    }

    private void discoverServers() {
        System.out.println("Discovering servers...");
        discoveryActive.set(true);

        // Start server discovery thread
        Thread discoveryThread = new Thread(this::listenForHeartbeats);
        discoveryThread.setDaemon(true);
        discoveryThread.start();

        // Wait for discovery period
        try {
            Thread.sleep(DISCOVERY_TIMEOUT);
            discoveryActive.set(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("\nDiscovered " + discoveredServers.size() + " server(s)");
    }

    private void listenForHeartbeats() {
        try (DatagramSocket socket = new DatagramSocket(HEARTBEAT_PORT)) {
            socket.setBroadcast(true);
            socket.setSoTimeout(DISCOVERY_TIMEOUT);

            byte[] buffer = new byte[512];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            while (discoveryActive.get()) {
                try {
                    socket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength());
                    processHeartbeat(message, packet.getAddress().getHostAddress());
                } catch (SocketTimeoutException e) {
                    // Discovery time ended
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Error during server discovery: " + e.getMessage());
        }
    }

    private void processHeartbeat(String message, String sourceIP) {
        // Parse the heartbeat message
        if (message.startsWith("RPS_SERVER:") && discoveryActive.get()) {
            String[] parts = message.split(":");
            if (parts.length >= 3) {
                String serverIP = parts[1];
                int serverPort = Integer.parseInt(parts[2]);

                // Store server info
                String key = serverIP + ":" + serverPort;
                // Only add and print if this server wasn't already discovered
                if (!discoveredServers.containsKey(key)) {
                    discoveredServers.put(key, new ServerInfo(serverIP, serverPort));
                    System.out.println("Discovered server: " + key);
                }
            }
        }
    }

    private void connectToDiscoveredServer() {
        if (discoveredServers.isEmpty()) {
            System.out.println("No servers available.");
            return;
        }

        // Display available servers
        System.out.println("\nAvailable servers:");
        int index = 1;
        List<String> serverKeys = new ArrayList<>(discoveredServers.keySet());

        for (String key : serverKeys) {
            System.out.println(index + ". " + key);
            index++;
        }

        // Let user select a server
        System.out.print("Choose a server (1-" + serverKeys.size() + "): ");
        try {
            int selection = Integer.parseInt(scanner.nextLine());

            if (selection >= 1 && selection <= serverKeys.size()) {
                // Stop discovery before connecting
                discoveryActive.set(false);

                ServerInfo selectedServer = discoveredServers.get(serverKeys.get(selection - 1));
                connectToServer(selectedServer.getIp(), selectedServer.getPort());
            } else {
                System.out.println("Invalid selection. Exiting.");
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid input. Exiting.");
        }
    }

    private void connectToServer(String serverIP, int serverPort) {
        try {
            socket = new Socket(serverIP, serverPort);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            System.out.println("Connected to server " + serverIP + ":" + serverPort);

            // Start a thread to handle server messages
            Thread serverThread = new Thread(this::handleServerMessages);
            serverThread.setDaemon(true);
            serverThread.start();

            // Add a small delay to allow initial server messages to be displayed
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Handle user input
            handleUserInput();

        } catch (IOException e) {
            System.out.println("Error connecting to server: " + e.getMessage());
        } finally {
            closeConnection();
        }
    }

    private void handleServerMessages() {
        try {
            String message;
            while (running && (message = in.readLine()) != null) {
                System.out.println(message);

                // Check if this is the welcome message after nickname setup
                if (message.contains("***Welcome ") && message.contains("! Type 'play'")) {
                    nicknameAccepted = true;

                    // Display the help message after the welcome message
                    System.out.println("\nAvailable commands:");
                    System.out.println("- play: Find a match with another player");
                    System.out.println("- play coffee: Find a match with Coffee Bet Mode (loser buys coffee)");
                    System.out.println("- play NICKNAME: Invite a specific player");
                    System.out.println("- score: Show your current score");
                    System.out.println("- players: List all online players");
                    System.out.println("- R/P/S: Make a move (Rock, Paper, Scissors)");
                    System.out.println("- exit: Disconnect from the server\n");
                }
            }
        } catch (IOException e) {
            if (running) {
                System.out.println("Lost connection to server: " + e.getMessage());
            }
        }
    }

    private void handleUserInput() {
        String userInput;
        while (running) {
            try {
                userInput = scanner.nextLine();
                if (userInput.equalsIgnoreCase("exit")) {
                    running = false;
                    break;
                }

                // Send the command to the server
                out.println(userInput);
            } catch (Exception e) {
                if (running) {
                    System.out.println("Error sending command: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void closeConnection() {
        running = false;
        try {
            if (scanner != null) {
                scanner.close();
            }
            if (out != null) {
                out.close();
            }
            if (in != null) {
                in.close();
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Inner class to store server information
    private static class ServerInfo {
        private final String ip;
        private final int port;

        public ServerInfo(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }

        public String getIp() {
            return ip;
        }

        public int getPort() {
            return port;
        }
    }
}