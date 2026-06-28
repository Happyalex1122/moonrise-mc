#!/usr/bin/env python3
import os
import sys
import shutil
import subprocess
import glob
import time
import threading
from queue import Queue, Empty

# Configuration
RUN_DIR = "test_server_run"
GRADLE_CMD = ".\\gradlew.bat" if os.name == "nt" else "./gradlew"

def find_java25():
    # 1. Check environment variable JAVA25_HOME
    java25_home = os.environ.get("JAVA25_HOME")
    if java25_home:
        java_bin = os.path.join(java25_home, "bin", "java.exe" if os.name == "nt" else "java")
        if os.path.exists(java_bin):
            return java_bin

    # 2. Check Gradle jdks folder
    user_home = os.path.expanduser("~")
    gradle_jdks = os.path.join(user_home, ".gradle", "jdks")
    if os.path.exists(gradle_jdks):
        patterns = [
            os.path.join(gradle_jdks, "**", "bin", "java.exe"),
            os.path.join(gradle_jdks, "**", "bin", "java")
        ]
        for pattern in patterns:
            for match in glob.glob(pattern, recursive=True):
                if "25" in match:
                    return match

    # 3. Fallback to path
    return "java"

JAVA_BIN = find_java25()
print(f"Using Java binary: {JAVA_BIN}")

# JVM Flags for Java 25, Panama FFM, Vector API, and classloader opening
JVM_FLAGS = [
    "-Xms2G",
    "-Xmx2G",
    "-XX:+UnlockDiagnosticVMOptions",
    "-XX:+UnlockExperimentalVMOptions",
    "--add-modules", "jdk.incubator.vector",
    "--enable-native-access=ALL-UNNAMED",
    "--add-opens", "java.base/java.net=ALL-UNNAMED",
    "-DrunE2ETests=true",  # Trigger test-plugin's auto-run sequence
]

def run_command(args, cwd=None):
    """Executes a command and returns the exit code."""
    print(f"Executing: {' '.join(args)} (cwd: {cwd})")
    res = subprocess.run(args, cwd=cwd)
    return res.returncode

def build_project():
    """Builds the Paperclip server jar and the test plugin jar."""
    print("=== Building paper-server (Paperclip) ===")
    if run_command([GRADLE_CMD, ":paper-server:createPaperclipJar"]) != 0:
        print("Error: Failed to build Paperclip server jar.")
        return False

    print("=== Building test-plugin ===")
    if run_command([GRADLE_CMD, ":test-plugin:jar"]) != 0:
        print("Error: Failed to build test-plugin jar.")
        return False

    return True

def setup_server_directory():
    """Sets up a clean server run directory."""
    print("=== Setting up server directory ===")
    if os.path.exists(RUN_DIR):
        print(f"Clearing existing run directory: {RUN_DIR}")
        for item in os.listdir(RUN_DIR):
            item_path = os.path.join(RUN_DIR, item)
            if item == "cache":
                # Keep cache directories (e.g. mojang libraries) to save network/disk overhead
                continue
            try:
                if os.path.isdir(item_path):
                    shutil.rmtree(item_path)
                else:
                    os.remove(item_path)
            except Exception as e:
                print(f"Warning: Could not remove {item_path}: {e}")
    else:
        os.makedirs(RUN_DIR)

    # Recreate subdirectories
    os.makedirs(os.path.join(RUN_DIR, "plugins"), exist_ok=True)

    # Locate and copy built jars
    paperclip_patterns = [
        os.path.join("paper-server", "build", "libs", "paper-paperclip-*.jar"),
        os.path.join("paper-server", "build", "libs", "paper-*.jar"),
        os.path.join("paper-server", "build", "libs", "paperclip-*.jar"),
        os.path.join("paper-server", "build", "distributions", "*.jar")
    ]
    paperclip_jar = None
    for pattern in paperclip_patterns:
        matches = glob.glob(pattern)
        if matches:
            # Sort by size or name to get the main paperclip jar
            matches.sort(key=os.path.getsize, reverse=True)
            paperclip_jar = matches[0]
            break

    if not paperclip_jar:
        print("Error: Could not find paper-paperclip jar in build/libs or build/distributions.")
        return False

    test_plugin_jars = glob.glob(os.path.join("test-plugin", "build", "libs", "test-plugin-*.jar"))
    if not test_plugin_jars:
        print("Error: Could not find test-plugin jar in build/libs.")
        return False
    test_plugin_jar = test_plugin_jars[0]

    print(f"Deploying server jar: {paperclip_jar} -> {RUN_DIR}/paper.jar")
    shutil.copy(paperclip_jar, os.path.join(RUN_DIR, "paper.jar"))

    print(f"Deploying test-plugin jar: {test_plugin_jar} -> {RUN_DIR}/plugins/test-plugin.jar")
    shutil.copy(test_plugin_jar, os.path.join(RUN_DIR, "plugins", "test-plugin.jar"))

    # Write eula.txt
    with open(os.path.join(RUN_DIR, "eula.txt"), "w") as f:
        f.write("eula=true\n")

    # Write server.properties for offline test execution
    with open(os.path.join(RUN_DIR, "server.properties"), "w") as f:
        f.write("online-mode=false\n")
        f.write("difficulty=peaceful\n")
        f.write("spawn-protection=0\n")
        f.write("sync-chunk-writes=false\n")
        f.write("view-distance=6\n")
        f.write("simulation-distance=6\n")
        f.write("allow-flight=true\n")
        f.write("level-name=world\n")
        f.write("max-tick-time=-1\n") # Disable watchdog thread to prevent ticks timing out tests

    return True

def enqueue_output(out, queue):
    """Reads stdout of process and enqueues lines."""
    for line in iter(out.readline, b''):
        queue.put(line.decode('utf-8', errors='replace'))
    out.close()

def run_session(session_num):
    """Boots the server for a specific session, monitors stdout for E2E tests, and stops it."""
    print(f"\n=========================================")
    print(f"      STARTING E2E TEST SESSION {session_num}")
    print(f"=========================================")
    
    # Configure session property
    session_flags = JVM_FLAGS + [f"-DrunE2ETests.session={session_num}"]
    cmd = [JAVA_BIN] + session_flags + ["-jar", "paper.jar", "--nogui"]
    
    # Start the process in the RUN_DIR
    process = subprocess.Popen(
        cmd,
        cwd=RUN_DIR,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        bufsize=1
    )

    # Use a thread-safe Queue to read stdout asynchronously without blocking on Windows
    q = Queue()
    t = threading.Thread(target=enqueue_output, args=(process.stdout, q))
    t.daemon = True
    t.start()

    # Track test results
    tests_completed = False
    tests_passed = 0
    tests_failed = 0
    failed_details = []
    
    timeout_seconds = 180  # 3 minutes timeout limit per session
    start_time = time.time()
    server_stopped_gracefully = False

    try:
        while True:
            try:
                line = q.get_nowait()
            except Empty:
                if process.poll() is not None:
                    break
                if time.time() - start_time > timeout_seconds:
                    print(f"ERROR: Execution timed out after {timeout_seconds} seconds.")
                    break
                time.sleep(0.1)
                continue

            # Print output line to standard out for visibility
            sys.stdout.write(f"[SESSION {session_num}] " + line)
            sys.stdout.flush()

            # Parse test signatures
            if "[E2E-TEST] START:" in line:
                pass
            elif "[E2E-TEST] PASS:" in line:
                tests_passed += 1
            elif "[E2E-TEST] FAIL:" in line:
                tests_failed += 1
                failed_details.append(line.strip())
            elif "[E2E-TEST] ALL TESTS COMPLETE" in line:
                print(f"\n=== E2E Test Session {session_num} Finished ===")
                print(line.strip())
                tests_completed = True
                
                # Command server to stop gracefully
                print("Sending '/stop' command to server...")
                try:
                    process.stdin.write(b"stop\n")
                    process.stdin.flush()
                except Exception as e:
                    print(f"Warning: Could not write stop command: {e}")
                server_stopped_gracefully = True

    except KeyboardInterrupt:
        print(f"\nAborting session {session_num}...")
    finally:
        # Graceful shutdown wait
        if process.poll() is None:
            if not server_stopped_gracefully:
                print("Stopping server...")
                try:
                    process.stdin.write(b"stop\n")
                    process.stdin.flush()
                except Exception:
                    pass
            # Wait for exit
            try:
                process.wait(timeout=30)
                print("Server stopped.")
            except subprocess.TimeoutExpired:
                print("Server failed to stop. Killing process...")
                process.kill()
                process.wait()

    # Final summary reports
    print(f"\n--- SESSION {session_num} SUMMARY ---")
    print(f"Tests Passed: {tests_passed}")
    print(f"Tests Failed: {tests_failed}")
    if failed_details:
        print("Failures:")
        for fail in failed_details:
            print(f" - {fail}")

    if not tests_completed:
        print("ERROR: Test suite did not run to completion.")
        return False
    if tests_failed > 0:
        print("ERROR: One or more tests failed.")
        return False

    print(f"SUCCESS: Session {session_num} completed successfully.")
    return True

def main():
    start_time = time.time()
    
    # 1. Setup server directory (re-using pre-built jars if they exist, or assuming built)
    # The runner expects the jars to be built.
    if not setup_server_directory():
        sys.exit(1)

    # 2. Run Session 1 (Write State)
    if not run_session(1):
        print("ERROR: Session 1 failed.")
        sys.exit(2)

    # 3. Run Session 2 (Verify State & Run Remaining Tests)
    if not run_session(2):
        print("ERROR: Session 2 failed.")
        sys.exit(2)
    
    elapsed = time.time() - start_time
    print(f"\nSUCCESS: Both E2E sessions passed! Total time elapsed: {elapsed:.2f} seconds")
    sys.exit(0)

if __name__ == "__main__":
    main()
