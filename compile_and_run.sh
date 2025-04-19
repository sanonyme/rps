#!/bin/bash

# Create bin directory if it doesn't exist
mkdir -p bin

# Compile the server and client
echo "Compiling the RPS Server and Client..."
javac -d bin src/server/RPSServer.java
javac -d bin src/client/RPSClient.java

# Create executable scripts
echo "Creating executable scripts..."

# Create rps_server script
cat > rps_server << EOF
#!/bin/bash
java -cp bin src.server.RPSServer "\$@"
EOF

# Create rps client script
cat > rps << EOF
#!/bin/bash
java -cp bin src.client.RPSClient
EOF

# Make the scripts executable
chmod +x rps_server
chmod +x rps

echo "Done! You can now run the server with './rps_server [port]' and the client with './rps'" 