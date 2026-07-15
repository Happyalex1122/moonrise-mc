# Setup & Environment

Setting up the development and execution environment for Moonrise-MC requires specific tools and path configurations. Follow this guide to ensure your workspace is prepared for compiling and running the server core.

## Java Requirement

Moonrise-MC leverages experimental features and optimizations requiring **Java 25**. 
- **Path configuration**: You must run the server with the specific Java 25 binary. 
- Default Expected Path: `/path/to/jdk-25/bin/java`

Ensure that this JDK is set in your IDE or environment variables before attempting to compile or execute the Paper 26.1.2 server.

## Key Directories

The following directory structure is central to the project's development workflow:

- **Server Core Repository**: `<workspace>/moonrise-mc` (The main source tree containing Paper patches and build scripts).
- **Main Working Directory**: `<workspace>/moonrise-server-run` (General directory for production builds and resources).
- **Test Server Run Directory**: `<workspace>/moonrise-mc/test_server_run` (Used for localized testing of newly built paperclips).
- **Dashboard Repository**: `<workspace>/moonrise-dashboard` (The React Native Expo frontend project).

## Build Quirks & Paperweight

When developing with Paperweight, you may encounter specific build quirks related to how patches are handled.

### The `rebuildPatches` Issue
Running the `rebuildPatches` task has a known bug in this environment. Modifying files that are already managed by feature patches (such as `Level.java`) and then running `rebuildPatches` incorrectly exports these files into the `patches/sources/` directory. This breaks the subsequent `compileJava` task, causing over 100 missing package errors due to incomplete or malformed source trees.

### Resolution & Best Practices
If your compilation breaks due to this error, you can resolve it by resetting the source tree:
1. Run `./gradlew applyPatches` to reset the `src/minecraft/java` tree. This will restore the environment and fix the compilation errors.
2. **Important**: Because `applyPatches` resets the directory, any manual modifications made directly in `src/minecraft/java` will be lost. 
3. **Backup Strategy**: Always back up your manual file modifications (e.g., creating a `Level.patch` file) directly to the root `paper-fork` directory *before* applying patches or making major changes.
