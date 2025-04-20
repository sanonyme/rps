#!/bin/bash

# Create bin directory if it doesn't exist
mkdir -p bin

# Compile the server and clients
echo "Compiling the RPS Server and Clients..."
javac -d bin src/server/RPSServer.java
javac -d bin src/client/RPSClient.java
javac -d bin src/client/RPSClientGUI.java

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

# Create rps_gui client script
cat > rps_gui << EOF
#!/bin/bash
java -cp bin src.client.RPSClientGUI
EOF

# Make the scripts executable
chmod +x rps_server
chmod +x rps
chmod +x rps_gui

echo "Done! You can now run:"
echo "- Server: './rps_server [port]'" 
echo "- Text Client: './rps'"
echo "- GUI Client: './rps_gui'" 