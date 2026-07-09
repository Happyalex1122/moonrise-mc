$ErrorActionPreference = "Stop"

$workspace = "D:\java_workspace\paper-fork"
Write-Host "Setting up Paper fork in $workspace..."
Set-Location $workspace

# Set Java to Java 25 as per agent memories
$env:JAVA_HOME = "D:\Java25\jdk-25.0.3+9"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

Write-Host "Using Java version:"
java -version

Write-Host "Applying Patches..."
./gradlew applyPatches

Write-Host "Building Paper..."
./gradlew build

Write-Host "Creating Reobf Jar..."
./gradlew createReobfPaperclipJar

Write-Host "Paper fork setup complete!"
