# Core Optimizations

Moonrise-MC features a wide array of engine-level optimizations spanning mechanics, database handling, network I/O, and CPU-specific enhancements. 

## Async Pathfinding

Mob pathfinding is one of the most CPU-intensive operations on the main server thread. Moonrise-MC targets full async pathfinding as a staged rollout:

**Current (v1.1.6):** `PathTypeCache` has been made fully thread-safe using `AtomicReferenceArray<CacheEntry>` with `getOpaque`/`setOpaque` for lock-free reads and `compareAndSet` for safe invalidation. This is a prerequisite for background-thread pathfinding and eliminates data races in the cache layer.

**Planned:** A dedicated `ASYNC_PATHFINDER_EXECUTOR` background thread pool will be wired to `PathFinder.findPath`, with `PathNavigation` refactored to return futures seamlessly without blocking the main server thread.

## Java Vector API (SIMD)

Taking advantage of modern CPU capabilities, collision checks in `AABB.java` (Axis-Aligned Bounding Box) have been optimized using the Java Vector API. By utilizing `DoubleVector` and `VectorOperators`, spatial calculations benefit from SIMD (Single Instruction, Multiple Data) processing, allowing for bulk coordinate processing and accelerating heavy entity-collision phases.

## Async Entity DB

To mitigate disk I/O bottlenecks during chunk saves:
- Entity saving and loading is offloaded from standard chunk operations directly to an `AsyncEntityDB` layer managed by `EntityStorage`.
- NBT data processing uses optimized lightweight representations, specifically utilizing `int[]` for UUID storage rather than object-heavy representations, minimizing GC pressure during bulk I/O.

## Docker Thread Provisioning

Containerized environments often report inaccurate hardware metrics. The `MoonriseCommon.java` utility introduces smart thread provisioning:
- **OSNuma Fixes**: Overcomes legacy `OSNuma` library bugs which often misidentified physical core counts when subjected to Docker cgroup limits. The system now cross-checks metrics with `Runtime.getRuntime().availableProcessors()`.
- **A1/4-Core Instance Tuning**: For 4-core servers (like ARM A1 instances), the system forces a minimum threshold for worker/IO threads. It enforces `Math.max(2, defaultWorkerThreads - 1)`, guaranteeing an optimal allocation of 3 Worker threads and 2 IO threads, preventing thread starvation.

## Packet Fixes & Networking

Network reliability has been hardened through `PacketSendListener.java`:
- **Mitigated Exceptions**: Stopped console spam resulting from `StacklessClosedChannelException`. 
- **Lifecycle Checks**: Added explicit `channel.isActive()` and `channel.isOpen()` checks to fallback and `exceptionallySend` pathways. This intercepts forced packet dispatches attempting to reach disconnected clients, saving bandwidth and CPU cycles.
- **Network Compression**: To reduce CPU overhead (particularly on ARM chipsets), the network compression threshold has been raised to `network-compression-threshold=1024`. This avoids the expensive overhead of compressing exceedingly small packets, trading a marginal bandwidth increase for notable CPU savings.

## Garbage Collection

The server is designed and configured to run optimally on Java 21/25's Generational ZGC (`-XX:+ZGenerational`). This ensures that Stop-The-World (STW) pauses are kept strictly in the sub-millisecond range, ensuring buttery-smooth tick rates even under immense memory pressure.
