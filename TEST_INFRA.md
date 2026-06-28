# Moonrise E2E Testing Infrastructure & Harness (TEST_INFRA)

This document outlines the architecture, setup, and execution procedures for the End-to-End (E2E) testing framework designed for the Minecraft Server Engine Moonrise Optimizations.

---

## 1. Test Harness Architecture

The testing framework is built around a dual-component architecture:
1. **E2E Test Runner (`test_runner.py`)**: A Python orchestrator that automates compilation, environment setup, JVM booting, and stdout tracking.
2. **E2E Test Plugin (`test-plugin`)**: A native Bukkit/Paper plugin loaded during server boot that executes localized and integrated tests, outputting structured logs to stdout.

```
+------------------+                   +----------------------+
|                  |  1. Gradle Build  |                      |
|                  +------------------>|  Paperclip & Plugin  |
|                  |                   +-----------+----------+
|                  |                               |
|                  |  2. Setup Environment         v
|                  +----------------------------> [test_server_run/]
|  Python Runner   |                               |
| (test_runner.py) |  3. Boot Java 25 Process      |
|                  +------------------------+      |
|                  |                        |      v
|                  |                        |  [Server Startup]
|                  |                        |  (Loads Test Plugin)
|                  |                        |      |
|                  |  4. Monitor logs &     v      v
|                  |     stdout assertions  <------+ [TestPlugin execution]
|                  |                               | (Checks assertions)
|                  |  5. Stop command (/stop)      |
|                  +-------------------------------+
+------------------+
```

---

## 2. Feature Inventory

| Feature Name | Tier 1 Test Target | Tier 2 Test Target | Tier 3 Test Target | Tier 4 Test Target |
|---|---|---|---|---|
| **AdaptiveTPSManager** | Basic boot and enabled check | Boundary check for tick rate adjustment limits | Interaction with virtual thread I/O load metrics | Scenario with sudden player load storm |
| **Command Routing /world** | Command registration and simple dispatch | Corner cases (invalid subcommands, special characters) | Command routing during active world saving/reloads | High-throughput command spam verification |
| **Panama LMDB** | FFM native access validation and basic DB write | Key-value sizes, boundary sizes, null values | DB write/read executing on Virtual Thread dispatch | DB state persistence across server sessions |
| **Arena Allocator** | Basic allocation and isNative check | Zero/large allocations, alignment bounds | Arena reset lifecycle and memory access invalidation | Stress allocation loops under load |
| **Vector API Collisions** | SIMD bounding box collision execution | Off-boundary coordinates, extreme values | SIMD vs Scalar accuracy comparison | Spawning storms of moving entities colliding |
| **Virtual Thread I/O** | Virtual thread dispatch queue verification | Max concurrent jobs boundary tests | FFM LMDB read/write execution from virtual thread | WAL checkpointing and chunk loads concurrent stress |
| **Predictive Chunk Prefetching** | Kinematic prediction logic verification | Zero velocity, maximum speed boundaries | Chunk loading performance under high speed flight | High-speed Elytra flight simulation scenario |
| **Zstd NBT Compression** | Compression and decompression check | Empty tags, max compressed sizes boundaries | DB state persistence with compressed entity NBT | High-frequency entity state serialization stress |

---

## 3. Real-World Application Scenarios (Tier 4)

| Scenario ID | Name | Features Exercised | Description |
|---|---|---|---|
| **SCENARIO_01** | Entity Storm & Collision | Vector API Collisions, Arena Allocator, AdaptiveTPSManager | Spawns 1000 entities in a tight space to stress bounding box collisions, tick allocator, and check if TPS adapts correctly. |
| **SCENARIO_02** | High-Speed Elytra Flight | Predictive Chunk Prefetching, Virtual Thread I/O | Simulates a player flying at high speeds using Elytra to trigger predictive chunk loading over virtual threads. |
| **SCENARIO_03** | High-Frequency World Saving | Panama LMDB, Zstd NBT Compression, Virtual Thread I/O | Simulates continuous rapid world saving, writing Zstd-compressed NBT data into LMDB via virtual threads under heavy disk I/O. |
| **SCENARIO_04** | Command Routing World Load | Command Routing /world, Panama LMDB, AdaptiveTPSManager | Executes high-throughput `/world` management commands (create, unload, teleport) while maintaining TPS stability. |
| **SCENARIO_05** | Chaos Database Persistence | Panama LMDB, Arena Allocator, Zstd NBT Compression | Runs concurrent read/write transactions on LMDB, allocator resets, and Zstd compression, then executes a hard shutdown and verifies database integrity. |

---

## 4. Coverage Thresholds

To qualify for release readiness, the testing suite must meet the following minimum test counts:

* **Tier 1 (Feature Functionality)**: $\ge 40$ tests (5 tests per feature across 8 features)
* **Tier 2 (Boundary & Corner Cases)**: $\ge 40$ tests (5 tests per feature across 8 features)
* **Tier 3 (Cross-Feature Interaction)**: $\ge 8$ tests (pairwise and integration tests)
* **Tier 4 (Real-World Workloads)**: $\ge 5$ scenarios (system-level application tests)

---

## 5. Interface Contracts

To communicate outcomes between the Minecraft server process and the Python test runner, the Test Plugin writes structured signatures directly to the standard output:

```text
[E2E-TEST] START: <test_id>
[E2E-TEST] PASS: <test_id>
[E2E-TEST] FAIL: <test_id> - <reason>
[E2E-TEST] ALL TESTS COMPLETE - PASS: <passed_count> FAIL: <failed_count>
```

Upon receiving the `[E2E-TEST] ALL TESTS COMPLETE` log, the test runner gracefully terminates the server by sending `/stop` to standard input, parses the outcomes, and returns a matching exit code:
* `0`: All tests passed.
* `1`: Build, dependency copy, or config setup failed.
* `2`: One or more tests failed, timed out, or the server crashed.

---

## 6. How to Run

To run the full suite, execute the Python script from the root project directory:

```powershell
python test-plugin/test_runner.py
```

### Environment Requirements
* Python 3.8+
* JDK 25 (installed and configured in `PATH` or `JAVA_HOME`)
* Target CPU architecture: ARM64 / x86_64 with SIMD instructions (NEON/AVX) for full Vector API acceleration support.
