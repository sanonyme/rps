# Multiplayer Rock Paper Scissors (LAN Edition)

A multiplayer Rock Paper Scissors game implemented in Java that can be played over a local network.

## Features

- Text-based and graphical user interface options
- Automatic server discovery via network heartbeat
- Player vs player matches with multi-round games
- Persistent score tracking
- Player invitations
- Chat functionality commands
- Coffee Bet Mode (loser buys the winner a coffee)

## Getting Started

### Prerequisites

- Java Development Kit (JDK) 8 or higher

### Installation

1. Clone or download this repository
2. Run the compile script:

```sh
./compile_and_run.sh
```

This will compile the code and create three executable scripts: `rps_server`, `rps` (text client), and `rps_gui` (graphical client).

## Running the Game

### Starting the Server

1. Start the RPS server:

```sh
./rps_server
```

The server will start on port 5000 by default. You can specify a different port as an argument:

```sh
./rps_server 6000
```

### Connecting with Clients

#### Text Client

1. Start the RPS text client:

```sh
./rps
```

2. Choose connection option:
   - Manual connection: Enter server IP and port
   - Automatic discovery: Select from discovered servers on your network

3. Choose a nickname when prompted.

#### Graphical Client

1. Start the RPS GUI client:

```sh
./rps_gui
```

2. In the connection screen:
   - Enter server details manually OR
   - Click "Discover Servers" to find servers on your network
   - Double-click a discovered server to connect

3. Choose a nickname when prompted.

## Playing the Game

### Text Client Commands

Once connected, you can use the following commands:

- `play` - Start a game (waits for another player)
- `play coffee` - Start a game with Coffee Bet Mode where the loser buys a coffee
- `play NICKNAME` - Invite a specific player to a game
- `y` or `yes` - Accept an invitation or coffee bet challenge
- `n` or `no` - Decline an invitation or coffee bet challenge
- `score` - View your current score
- `players` - List players currently in the lobby
- `exit` - Disconnect from the server

When in a game, you can play by sending:
- `R` - Rock
- `P` - Paper
- `S` - Scissors

### GUI Client

The graphical interface provides buttons for all game actions:

- "Play Game" - Start matchmaking
- "Show Score" - Display your score
- "Show Players" - List online players
- "Coffee Bet Mode" - Toggle the coffee bet mode (loser buys winner a coffee)
- Rock, Paper, Scissors buttons for making moves
- Text input field for chat and commands

## Game Modes

### Standard Mode
The standard game where players compete to win 3 rounds first.

### Coffee Bet Mode
A fun way to play with stakes! In this mode:
- The loser of the match buys the winner a coffee
- Players must both consent to Coffee Bet Mode
- If a player with Coffee Bet Mode is matched with a regular player, the regular player will be asked if they accept the coffee bet challenge
- The GUI client has a checkbox to enable Coffee Bet Mode
- In text client, use the `play coffee` command

## Network Discovery

The game implements automatic server discovery using UDP broadcast heartbeats. This allows clients to find servers running on the local network without knowing the exact IP address.

## Notes

- If you experience network issues, make sure your firewall is not blocking the connection.
- Scores are persistent and saved on the server.
- First player to win 3 rounds wins the match (configurable on server). 