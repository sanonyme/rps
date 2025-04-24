package src.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class RPSClientGUI extends JFrame {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean running = true;
    private static final int HEARTBEAT_PORT = 5001; // Must match server's heartbeat port
    private static final int DISCOVERY_TIMEOUT = 5000; // Time to wait for server responses in ms
    private final Map<String, ServerInfo> discoveredServers = new ConcurrentHashMap<>();
    private final AtomicBoolean discoveryActive = new AtomicBoolean(false);

    // Game state tracking
    private boolean inGame = false;
    private String opponentName;
    private int playerWins = 0;
    private int opponentWins = 0;
    private int winsNeeded = 3;

    // GUI Components
    private JTextArea messageArea;
    private JTextField inputField;
    private JButton rockButton, paperButton, scissorsButton;
    private JButton playButton, scoreButton, playersButton;
    private JPanel gamePanel;
    private JPanel controlPanel;
    private CardLayout cardLayout;
    private JLabel statusLabel;
    private JLabel scoreLabel;
    private JPanel moveHistoryPanel;
    private JButton sendButton;

    // Game move display
    private JLabel playerMoveLabel;
    private JLabel opponentMoveLabel;
    private JLabel resultLabel;

    // Connection panel components
    private JPanel connectionPanel;
    private JTextField serverIPField;
    private JTextField serverPortField;
    private JButton connectButton;
    private JButton discoverButton;
    private JList<String> serverList;
    private DefaultListModel<String> serverListModel;

    // Icons for Rock, Paper, Scissors
    private ImageIcon rockIcon;
    private ImageIcon paperIcon;
    private ImageIcon scissorsIcon;
    private ImageIcon questionIcon;

    private JPanel mainPanel; // Add reference to the main panel

    public static void main(String[] args) {
        try {
            // Set system look and feel
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            RPSClientGUI client = new RPSClientGUI();
            client.setVisible(true);
        });
    }

    public RPSClientGUI() {
        setTitle("Rock-Paper-Scissors Game");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);

        // Load game icons
        loadIcons();

        initComponents();
        layoutComponents();
        setupEventHandlers();
    }

    private void loadIcons() {
        // Create simple icons using Unicode characters
        rockIcon = createTextIcon("🪨", 32);
        paperIcon = createTextIcon("📃", 32);
        scissorsIcon = createTextIcon("✂️", 32);
        questionIcon = createTextIcon("❓", 32);
    }

    private ImageIcon createTextIcon(String text, int size) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, size));
        label.setSize(size + 10, size + 10);
        label.setHorizontalAlignment(JLabel.CENTER);

        BufferedImage image = new BufferedImage(
                label.getWidth(), label.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics g = image.getGraphics();
        label.paint(g);
        g.dispose();

        return new ImageIcon(image);
    }

    private void initComponents() {
        // Main layout
        cardLayout = new CardLayout();

        // Connection panel
        connectionPanel = new JPanel(new BorderLayout(10, 10));
        connectionPanel.setBorder(new EmptyBorder(20, 20, 20, 20));

        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JLabel titleLabel = new JLabel("Rock-Paper-Scissors Game");
        titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
        headerPanel.add(titleLabel);

        JPanel manualPanel = new JPanel(new GridLayout(3, 2, 10, 10));
        manualPanel.setBorder(BorderFactory.createTitledBorder("Manual Connection"));
        JLabel ipLabel = new JLabel("Server IP:");
        serverIPField = new JTextField("127.0.0.1");
        JLabel portLabel = new JLabel("Server Port:");
        serverPortField = new JTextField("5000");
        connectButton = new JButton("Connect");
        connectButton.setBackground(new Color(100, 180, 100));
        connectButton.setForeground(Color.WHITE);
        manualPanel.add(ipLabel);
        manualPanel.add(serverIPField);
        manualPanel.add(portLabel);
        manualPanel.add(serverPortField);
        manualPanel.add(new JLabel()); // Empty cell
        manualPanel.add(connectButton);

        serverListModel = new DefaultListModel<>();
        serverList = new JList<>(serverListModel);
        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane serverListScrollPane = new JScrollPane(serverList);
        serverListScrollPane.setBorder(BorderFactory.createTitledBorder("Discovered Servers"));

        JPanel discoveryPanel = new JPanel(new BorderLayout());
        discoveryPanel.setBorder(BorderFactory.createTitledBorder("Auto Discovery"));
        discoverButton = new JButton("Discover Servers");
        discoverButton.setBackground(new Color(100, 150, 200));
        discoverButton.setForeground(Color.WHITE);
        discoveryPanel.add(discoverButton, BorderLayout.NORTH);
        discoveryPanel.add(serverListScrollPane, BorderLayout.CENTER);

        JPanel connectionControlsPanel = new JPanel(new GridLayout(1, 2, 10, 10));
        connectionControlsPanel.add(manualPanel);
        connectionControlsPanel.add(discoveryPanel);

        connectionPanel.add(headerPanel, BorderLayout.NORTH);
        connectionPanel.add(connectionControlsPanel, BorderLayout.CENTER);

        // Game panel
        gamePanel = new JPanel(new BorderLayout(10, 10));
        gamePanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Status/score panel
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createEtchedBorder());
        statusLabel = new JLabel("Not in a game", SwingConstants.CENTER);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        scoreLabel = new JLabel("Score: 0 - 0", SwingConstants.CENTER);
        statusPanel.add(statusLabel, BorderLayout.NORTH);
        statusPanel.add(scoreLabel, BorderLayout.SOUTH);

        // Game visualization panel
        JPanel gameVisualPanel = new JPanel(new GridLayout(1, 3, 10, 0));
        gameVisualPanel.setBorder(BorderFactory.createTitledBorder("Current Game"));

        // Player move panel
        JPanel playerPanel = new JPanel(new BorderLayout());
        playerPanel.setBorder(BorderFactory.createTitledBorder("Your Move"));
        playerMoveLabel = new JLabel(questionIcon, SwingConstants.CENTER);
        playerPanel.add(playerMoveLabel, BorderLayout.CENTER);

        // Result panel
        JPanel resultPanel = new JPanel(new BorderLayout());
        resultPanel.setBorder(BorderFactory.createTitledBorder("Result"));
        resultLabel = new JLabel("", SwingConstants.CENTER);
        resultLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        resultPanel.add(resultLabel, BorderLayout.CENTER);

        // Opponent move panel
        JPanel opponentPanel = new JPanel(new BorderLayout());
        opponentPanel.setBorder(BorderFactory.createTitledBorder("Opponent's Move"));
        opponentMoveLabel = new JLabel(questionIcon, SwingConstants.CENTER);
        opponentPanel.add(opponentMoveLabel, BorderLayout.CENTER);

        gameVisualPanel.add(playerPanel);
        gameVisualPanel.add(resultPanel);
        gameVisualPanel.add(opponentPanel);

        // Move history panel
        moveHistoryPanel = new JPanel();
        moveHistoryPanel.setLayout(new BoxLayout(moveHistoryPanel, BoxLayout.Y_AXIS));
        moveHistoryPanel.setBorder(BorderFactory.createTitledBorder("Move History"));
        JScrollPane moveHistoryScrollPane = new JScrollPane(moveHistoryPanel);
        moveHistoryScrollPane.setPreferredSize(new Dimension(150, 200));

        // Message area
        messageArea = new JTextArea();
        messageArea.setEditable(false);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        messageArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        JScrollPane scrollPane = new JScrollPane(messageArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Game Messages"));

        // Input field
        inputField = new JTextField();
        inputField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));

        // Game buttons panel
        JPanel gameButtonsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        gameButtonsPanel.setBorder(BorderFactory.createTitledBorder("Make Your Move"));

        rockButton = new JButton("Rock", rockIcon);
        rockButton.setVerticalTextPosition(SwingConstants.BOTTOM);
        rockButton.setHorizontalTextPosition(SwingConstants.CENTER);

        paperButton = new JButton("Paper", paperIcon);
        paperButton.setVerticalTextPosition(SwingConstants.BOTTOM);
        paperButton.setHorizontalTextPosition(SwingConstants.CENTER);

        scissorsButton = new JButton("Scissors", scissorsIcon);
        scissorsButton.setVerticalTextPosition(SwingConstants.BOTTOM);
        scissorsButton.setHorizontalTextPosition(SwingConstants.CENTER);

        gameButtonsPanel.add(rockButton);
        gameButtonsPanel.add(paperButton);
        gameButtonsPanel.add(scissorsButton);

        // Control buttons panel
        controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        controlPanel.setBorder(BorderFactory.createTitledBorder("Game Controls"));

        playButton = new JButton("Play Game");
        playButton.setBackground(new Color(100, 180, 100));
        playButton.setForeground(Color.WHITE);

        scoreButton = new JButton("Show Score");
        playersButton = new JButton("Show Players");

        controlPanel.add(playButton);
        controlPanel.add(scoreButton);
        controlPanel.add(playersButton);

        // Combined button panel
        JPanel buttonPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        buttonPanel.add(gameButtonsPanel);
        buttonPanel.add(controlPanel);

        // Input panel
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        inputPanel.setBorder(BorderFactory.createTitledBorder("Chat/Commands"));
        inputPanel.add(inputField, BorderLayout.CENTER);
        sendButton = new JButton("Send");
        sendButton.setBackground(new Color(100, 150, 200));
        sendButton.setForeground(Color.WHITE);
        inputPanel.add(sendButton, BorderLayout.EAST);

        // Left side panel with game visuals and controls
        JPanel leftPanel = new JPanel(new BorderLayout(10, 10));
        leftPanel.add(statusPanel, BorderLayout.NORTH);
        leftPanel.add(gameVisualPanel, BorderLayout.CENTER);
        leftPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Right side panel with messages
        JPanel rightPanel = new JPanel(new BorderLayout(10, 10));
        rightPanel.add(scrollPane, BorderLayout.CENTER);
        rightPanel.add(inputPanel, BorderLayout.SOUTH);

        // Split the game panel
        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                leftPanel,
                rightPanel);
        splitPane.setResizeWeight(0.4);
        gamePanel.add(splitPane, BorderLayout.CENTER);

        // Initialize buttons as disabled until connected
        setGameButtonsEnabled(false);
    }

    private void layoutComponents() {
        // Set up the card layout
        mainPanel = new JPanel(cardLayout);
        mainPanel.add(connectionPanel, "connection");
        mainPanel.add(gamePanel, "gamePanel");

        // Add to frame
        add(mainPanel);

        // Start with connection panel
        cardLayout.show(mainPanel, "connection");
    }

    private void setupEventHandlers() {
        // Connect button
        connectButton.addActionListener(e -> {
            String serverIP = serverIPField.getText().trim();
            int serverPort;
            try {
                serverPort = Integer.parseInt(serverPortField.getText().trim());
                connectToServer(serverIP, serverPort);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid port number", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        // Discover button
        discoverButton.addActionListener(e -> {
            discoverServers();
        });

        // Server list double-click
        serverList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int index = serverList.getSelectedIndex();
                    if (index >= 0) {
                        String displayName = serverListModel.getElementAt(index);

                        // Find the matching server info based on port number
                        int port = -1;
                        if (displayName.startsWith("RPS Server @")) {
                            try {
                                port = Integer.parseInt(displayName.substring("RPS Server @".length()));
                            } catch (NumberFormatException ex) {
                                // Ignore parsing errors
                            }
                        }

                        // Find the server with this port
                        for (Map.Entry<String, ServerInfo> entry : discoveredServers.entrySet()) {
                            if (entry.getValue().getPort() == port) {
                                ServerInfo server = entry.getValue();
                                connectToServer(server.getIp(), server.getPort());
                                return;
                            }
                        }
                    }
                }
            }
        });

        // Game move buttons
        rockButton.addActionListener(e -> {
            sendCommand("R");
            playerMoveLabel.setIcon(rockIcon);
        });

        paperButton.addActionListener(e -> {
            sendCommand("P");
            playerMoveLabel.setIcon(paperIcon);
        });

        scissorsButton.addActionListener(e -> {
            sendCommand("S");
            playerMoveLabel.setIcon(scissorsIcon);
        });

        // Control buttons
        playButton.addActionListener(e -> {
            sendCommand("play");
            resetGameDisplay();
        });

        scoreButton.addActionListener(e -> sendCommand("score"));

        playersButton.addActionListener(e -> sendCommand("players"));

        // Input field and send button
        ActionListener sendAction = e -> {
            String input = inputField.getText().trim();
            if (!input.isEmpty()) {
                sendCommand(input);
                inputField.setText("");
            }
        };

        inputField.addActionListener(sendAction);
        sendButton.addActionListener(sendAction);

        // Window close listener
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                running = false;
                closeConnection();
            }
        });
    }

    private void resetGameDisplay() {
        playerMoveLabel.setIcon(questionIcon);
        opponentMoveLabel.setIcon(questionIcon);
        resultLabel.setText("");
    }

    private void setGameButtonsEnabled(boolean enabled) {
        rockButton.setEnabled(enabled);
        paperButton.setEnabled(enabled);
        scissorsButton.setEnabled(enabled);
        playButton.setEnabled(enabled);
        scoreButton.setEnabled(enabled);
        playersButton.setEnabled(enabled);
        inputField.setEnabled(enabled);
        sendButton.setEnabled(enabled);
    }

    private void discoverServers() {
        // Clear previously discovered servers
        discoveredServers.clear();
        serverListModel.clear();

        appendToGameLog("Discovering servers...");
        discoverButton.setEnabled(false);
        statusLabel.setText("Status: Discovering servers...");

        discoveryActive.set(true);

        // Start discovery in a separate thread
        new Thread(() -> {
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

            // Update UI with results
            SwingUtilities.invokeLater(() -> {
                discoverButton.setEnabled(true);
                if (discoveredServers.isEmpty()) {
                    statusLabel.setText("Status: No servers found");
                    appendToGameLog("No servers found");
                } else {
                    statusLabel.setText("Status: Found " + discoveredServers.size() + " server(s)");
                    appendToGameLog("Found " + discoveredServers.size() + " server(s)");
                }
            });
        }).start();
    }

    private void listenForHeartbeats() {
        try (DatagramSocket socket = new DatagramSocket(HEARTBEAT_PORT)) {
            socket.setBroadcast(true);
            socket.setSoTimeout(1000); // 1-second timeout to allow loop interruption

            byte[] buffer = new byte[512];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            while (discoveryActive.get()) {
                try {
                    socket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength());
                    processHeartbeat(message, packet.getAddress().getHostAddress());
                } catch (SocketTimeoutException e) {
                    // This is expected, continue loop
                }
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> {
                appendToGameLog("Error during server discovery: " + e.getMessage());
            });
        }
    }

    private void processHeartbeat(String message, String sourceIP) {
        // Parse the heartbeat message
        if (message.startsWith("RPS_SERVER:") && discoveryActive.get()) {
            String[] parts = message.split(":");
            if (parts.length >= 3) {
                // Use the source IP (where the heartbeat came from) instead of the IP in the
                // message
                // This ensures we connect to the actual reachable IP address
                String serverIP = sourceIP;
                int serverPort = Integer.parseInt(parts[2]);

                // Store server info - use simple server name to avoid duplicates
                String serverName = "RPS Server @" + serverPort;
                String key = serverIP + ":" + serverPort;

                if (!discoveredServers.containsKey(key)) {
                    discoveredServers.put(key, new ServerInfo(serverIP, serverPort));
                    SwingUtilities.invokeLater(() -> {
                        // Use a more friendly display name in the UI
                        serverListModel.addElement(serverName);
                        appendToGameLog("Discovered server: " + serverName + " (" + key + ")");
                    });
                }
            }
        }
    }

    private void connectToServer(String serverIP, int serverPort) {
        try {
            // Close existing connection if any
            if (out != null) {
                out.close();
                in.close();
                socket.close();
            }

            // Attempt to connect
            socket = new Socket(serverIP, serverPort);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Update UI
            setConnectionStatus(true);
            appendToGameLog("Connected to server " + serverIP + ":" + serverPort);

            // Switch to game panel
            cardLayout.show(mainPanel, "gamePanel");
            setGameButtonsEnabled(true);

            // Start a thread to handle server messages
            new Thread(this::handleServerMessages).start();

        } catch (IOException e) {
            appendToGameLog("Error connecting to server: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Failed to connect: " + e.getMessage(),
                    "Connection Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleServerMessages() {
        try {
            String message;
            while (running && (message = in.readLine()) != null) {
                final String displayMessage = message;
                SwingUtilities.invokeLater(() -> {
                    messageArea.append(displayMessage + "\n");
                    // Auto-scroll to bottom
                    messageArea.setCaretPosition(messageArea.getDocument().getLength());

                    // Process game-specific messages
                    processGameMessage(displayMessage);
                });
            }
        } catch (IOException e) {
            if (running) {
                SwingUtilities.invokeLater(() -> {
                    appendToGameLog("Lost connection to server: " + e.getMessage());
                    setConnectionStatus(false);
                });
            }
        }
    }

    private void processGameMessage(String message) {
        // Handle welcome message to get player name
        if (message.contains("***Welcome ") && message.contains("! Type 'play'")) {
            // No need to extract and store player name as it's not used
        }

        // Handle game start message
        else if (message.contains("***You are now playing with ")) {
            String[] parts = message.split("\\*\\*\\*You are now playing with ");
            if (parts.length > 1) {
                parts = parts[1].split("\\*\\*\\*");
                if (parts.length > 0) {
                    opponentName = parts[0].trim();
                    inGame = true;
                    playerWins = 0;
                    opponentWins = 0;
                    updateGameStatus();
                }
            }
        }

        // Handle first to win message
        else if (message.contains("***First to win ") && message.contains(" rounds wins the match!***")) {
            String[] parts = message.split("\\*\\*\\*First to win ");
            if (parts.length > 1) {
                parts = parts[1].split(" rounds");
                if (parts.length > 0) {
                    try {
                        winsNeeded = Integer.parseInt(parts[0].trim());
                    } catch (NumberFormatException e) {
                        winsNeeded = 3; // Default
                    }
                }
            }
        }

        // Handle move results
        else if (message.contains("***Your move: ") && message.contains(", Opponent's move: ")) {
            String[] parts = message.split("\\*\\*\\*Your move: ");
            if (parts.length > 1) {
                parts = parts[1].split(", Opponent's move: ");
                if (parts.length > 1) {
                    String playerMove = parts[0].trim();
                    String opponentMove = parts[1].replace("***", "").trim();

                    // Update the move display
                    updateMoveDisplay(playerMove, opponentMove);
                }
            }
        }

        // Handle round win/loss
        else if (message.contains("***You won this round!")) {
            playerWins++;
            resultLabel.setText("You won!");
            resultLabel.setForeground(new Color(0, 150, 0));
            updateGameStatus();

            // Check for match win
            if (playerWins >= winsNeeded) {
                inGame = false;
            }
        } else if (message.contains("***You lost this round!")) {
            opponentWins++;
            resultLabel.setText("You lost!");
            resultLabel.setForeground(new Color(200, 0, 0));
            updateGameStatus();

            // Check for match loss
            if (opponentWins >= winsNeeded) {
                inGame = false;
            }
        } else if (message.contains("***It's a draw for this round!")) {
            resultLabel.setText("It's a draw!");
            resultLabel.setForeground(Color.BLUE);
        }

        // Handle game end
        else if (message.contains("***Congratulations! You've won the match!") ||
                message.contains("***You've lost the match.")) {
            inGame = false;
            updateGameStatus();
        }

        // Handle opponent disconnect
        else if (message.contains("***Your opponent has disconnected***")) {
            inGame = false;
            opponentName = null;
            updateGameStatus();
        }
    }

    private void updateMoveDisplay(String playerMove, String opponentMove) {
        // Update player move icon
        if (playerMove.equals("R")) {
            playerMoveLabel.setIcon(rockIcon);
        } else if (playerMove.equals("P")) {
            playerMoveLabel.setIcon(paperIcon);
        } else if (playerMove.equals("S")) {
            playerMoveLabel.setIcon(scissorsIcon);
        }

        // Update opponent move icon
        if (opponentMove.equals("R")) {
            opponentMoveLabel.setIcon(rockIcon);
        } else if (opponentMove.equals("P")) {
            opponentMoveLabel.setIcon(paperIcon);
        } else if (opponentMove.equals("S")) {
            opponentMoveLabel.setIcon(scissorsIcon);
        }
    }

    private void updateGameStatus() {
        if (inGame && opponentName != null) {
            statusLabel.setText("Playing against: " + opponentName);
            scoreLabel.setText("Score: " + playerWins + " - " + opponentWins + " (First to " + winsNeeded + ")");
        } else {
            statusLabel.setText("Not in a game");
            if (playerWins > 0 || opponentWins > 0) {
                scoreLabel.setText("Last game: " + playerWins + " - " + opponentWins);
            } else {
                scoreLabel.setText("Score: 0 - 0");
            }
        }
    }

    private void sendCommand(String command) {
        if (out != null) {
            out.println(command);
        } else {
            messageArea.append("Not connected to a server.\n");
        }
    }

    private void closeConnection() {
        running = false;
        try {
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

    private void setConnectionStatus(boolean isConnected) {
        if (isConnected) {
            statusLabel.setText("Status: Connected");
            statusLabel.setForeground(new Color(0, 128, 0)); // Dark green
        } else {
            statusLabel.setText("Status: Disconnected");
            statusLabel.setForeground(Color.RED);
        }
    }

    private void appendToGameLog(String message) {
        messageArea.append(message + "\n");
        // Auto-scroll to bottom
        messageArea.setCaretPosition(messageArea.getDocument().getLength());
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