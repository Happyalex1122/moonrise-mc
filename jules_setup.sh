#!/bin/bash
set -e

echo "Setting up Paper-fork environment on Jules VM..."

# Install SDKMAN and Java 25 (Linux x64)
curl -s "https://get.sdkman.io" | bash
export SDKMAN_DIR="$HOME/.sdkman"
set +u
[[ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]] && source "$HOME/.sdkman/bin/sdkman-init.sh"
# Try Temurin first, fallback to OpenJDK if not found
sdk install java 25-tem || sdk install java 25-open || true
set -u

export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
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
