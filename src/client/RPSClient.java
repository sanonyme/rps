package src.client;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class RPSClient {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Scanner scanner;
    private volatile boolean running = true;

    public static void main(String[] args) {
        RPSClient client = new RPSClient();
        client.start();
    }

    public void start() {
        scanner = new Scanner(System.in);

        System.out.print("Enter server IP: ");
        String serverIP = scanner.nextLine();

        System.out.print("Enter server port: ");
        int serverPort = Integer.parseInt(scanner.nextLine());

        try {
            connectToServer(serverIP, serverPort);

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

    private void connectToServer(String serverIP, int serverPort) throws IOException {
        socket = new Socket(serverIP, serverPort);
        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        System.out.println("Connected to server " + serverIP + ":" + serverPort);
    }

    private void handleServerMessages() {
        try {
            String message;
            while (running && (message = in.readLine()) != null) {
                System.out.println(message);
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
}