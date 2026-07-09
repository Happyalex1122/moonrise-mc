#!/bin/bash
set -e

echo "Setting up Paper-fork environment on Jules VM..."

# Install OpenJDK 25 (Linux x64)
wget https://download.java.net/java/early_access/jdk25/9/GPL/openjdk-25-ea+9_linux-x64_bin.tar.gz -O jdk25.tar.gz
tar -xzf jdk25.tar.gz
export JAVA_HOME=$(pwd)/jdk-25
export PATH=$JAVA_HOME/bin:$PATH

echo "Using Java version:"
java -version

echo "Fetching latest changes from GitHub..."
git pull

echo "Applying Patches..."
./gradlew applyPatches

echo "Building Paper..."
./gradlew build

echo "Creating Reobf Jar..."
./gradlew createReobfPaperclipJar

echo "Jules VM Setup Complete!"
