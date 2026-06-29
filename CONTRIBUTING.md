# Moonrise Contributing Guidelines

Welcome to the Moonrise project! We are an extreme-performance, ARM-optimized Minecraft server engine based on Paper.

## How to Contribute

1. **Fork the Repository**: Create your own fork and clone it locally.
2. **Set up the Environment**: Ensure you have Java 22 or higher installed. Moonrise heavily relies on the Foreign Function & Memory API (Panama) and the Vector API.
3. **Run the Build**: Use `./gradlew applyPatches` and `./gradlew createPaperclipJar` to verify your environment.
4. **Make your Changes**: Create a new branch for your feature or bug fix. Keep your commits clean and focused.
5. **Code Style**: We follow the standard Java coding conventions. Please ensure your code is properly formatted before submitting.
6. **Submit a Patch**: We use the patch system. Do not submit direct changes to the `paper-server` source files. Instead, use `./gradlew rebuildPatches` after making changes in the generated source directories.

## Pull Requests
While we appreciate external contributions, please ensure you have tested your changes against our `MoonriseStressTest` benchmark to verify no performance regressions exist.

Thank you for helping us make Moonrise the fastest Minecraft server engine!
