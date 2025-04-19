# Multiplayer Rock Paper Scissors (LAN Edition)

A text-based LAN multiplayer Rock Paper Scissors game implemented in Java.

## Getting Started

### Prerequisites

- Java Development Kit (JDK) 8 or higher

### Installation

1. Clone or download this repository
2. Run the compile script:

```sh
./compile_and_run.sh
```

This will compile the code and create two executable scripts: `rps_server` and `rps`.

## Running the Game

### Starting the Server

1. Start the RPS server:

```sh
./rps_server
```

The server will start on port 5000 and display "RPS Server started on port 5000".

### Connecting with Clients

1. Start the RPS client:

```sh
./rps
```

2. When prompted, enter:
   - Server IP (the IP address of the computer running the server)
   - Server port (5000 by default)

3. Choose a nickname when prompted.

## Playing the Game

Once connected, you can use the following commands:

- `play` - Start a game (waits for another player)
- `score` - View your current score
- `players` - List players currently in the lobby

When in a game, you can play by sending:
- `R` - Rock
- `P` - Paper
- `S` - Scissors

The server will match you with another player who is also waiting to play.

## Notes

- If you experience network issues, make sure your firewall is not blocking the connection.
- Commands are case-insensitive.
- Your score is reset when you disconnect from the server. 