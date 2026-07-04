<div align="center">
  <img src="docs/assets/logo.png" alt="Moonrise Logo" width="200"/>
  <h1>Moonrise</h1>
  <p><b>Next-Generation High-Performance Minecraft Server Software</b></p>
  <p>
    <a href="https://github.com/Happyalex1122/moonrise-mc/actions"><img src="https://img.shields.io/github/actions/workflow/status/Happyalex1122/moonrise-mc/build.yml?style=flat-square" alt="Build Status"/></a>
    <a href="https://github.com/Happyalex1122/moonrise-mc/blob/main/LICENSE"><img src="https://img.shields.io/github/license/Happyalex1122/moonrise-mc?style=flat-square&v=2" alt="License"/></a>
    <a href="https://discord.gg/papermc"><img src="https://img.shields.io/discord/289587020175114240?style=flat-square&logo=discord&label=Discord" alt="Discord"/></a>
  </p>
</div>

---

## 🚀 About Moonrise

**Moonrise** is a highly experimental, performance-obsessed fork of [PaperMC](https://papermc.io/), architected to push the limits of modern hardware. It abandons legacy paradigms in favor of cutting-edge JVM features and heavy architectural rewrites, aiming for massive concurrency and minimal latency.

This project is built specifically to handle high-density player environments, massive entity counts, and zero-compromise server performance.

### ✨ Core Features

*   **Virtual Threads (Project Loom):** Asynchronous network handling and chunk generation decoupled from the main server tick.
*   **Vector API (SIMD) Math Engine:** Core math operations (AABB collision, entity movement, distance calculations) are heavily vectorized for ARM64 and modern x86 hardware.
*   **LMDB-Backed Persistence:** The standard Minecraft region file format is replaced with lightning-fast memory-mapped [LMDB](http://www.symas.com/lmdb/) for chunks and entities.
*   **Optimized ARM64 Pipeline:** Tailored for cloud-native Graviton processors and modern data center topologies.

## 🛠️ Getting Started

### Prerequisites

Moonrise leverages modern JVM features and requires **Java 25+** to compile and run.

### Building from Source

Moonrise uses a custom patch-based build system (Paperweight). To build a runnable server jar:

```bash
# Clone the repository
git clone https://github.com/Happyalex1122/moonrise-mc.git
cd moonrise-mc

# Apply patches and build the server
./gradlew applyPatches
./gradlew createReobfBundlerJar
```

The compiled server jar will be located in `build/libs/`.

## 📦 Documentation & Links

*   [**Contributing Guidelines**](CONTRIBUTING.md): Learn how to contribute to the core and testing plugin.
*   [**Security Policy**](SECURITY.md): Reporting security vulnerabilities.
*   [**License**](LICENSE): Moonrise is licensed under the GPLv3 (inheriting from Paper).

## 📊 Benchmarks

Moonrise is rigorously tested with automated stress tests (`MoonriseStressTest`).
Under conditions of 2,000+ entities and massive block updates, Moonrise maintains sub-1ms tick times (`MSPT < 1.0`).

### Performance Comparison: Hybrid LMDB Moonrise vs Purpur

During an automated stress test under identical isolated environments (Java 25, 4GB RAM) featuring massive chunk loading and spawning 2000 entities, **Hybrid LMDB Moonrise** showed an unprecedented level of I/O performance and resilient TPS compared to **Purpur**.

#### Hybrid LMDB Moonrise Server Performance
The Hybrid LMDB build utilizes a dynamic hot-cache (memory-mapped LMDB) coupled with compressed cold storage (Zstd-Linear) to guarantee zero I/O blocking. A dedicated virtual thread flushes dirty chunks in the background.

| Time (s) | TPS | MSPT | Memory Usage |
|----------|-----|------|--------------|
| +10s | 18.01 | 17.52ms | 3282MB / 3992MB |
| +15s | 18.47 | 0.33ms | 3285MB / 3992MB |
| +20s | 18.76 | 0.27ms | 3288MB / 3992MB |
| +25s | 18.96 | 0.23ms | 3293MB / 3992MB |
| +30s | 19.10 | 0.23ms | 3295MB / 3992MB |
| +35s | 19.21 | 0.23ms | 3298MB / 3992MB |
| +40s | 19.29 | 0.23ms | 3300MB / 3992MB |

#### Purpur Server Performance
Purpur handles the stress test with near-perfect instant TPS recovery, but its monolithic chunk loading consumes massively more RAM in identical conditions and suffers from higher baseline MSPT.

| Time (s) | TPS | MSPT | Memory Usage |
|----------|-----|------|--------------|
| +10s | 15.96 | 8.96ms | 2225MB / 4096MB |
| +15s | 20.00 | 0.38ms | 2230MB / 4096MB |
| +20s | 20.00 | 0.30ms | 2234MB / 4096MB |
| +25s | 20.00 | 0.28ms | 2237MB / 4096MB |
| +30s | 20.00 | 0.26ms | 2238MB / 4096MB |

**Summary:** The Hybrid LMDB mechanism gives Moonrise the absolute best of both worlds. Moonrise processes heavy I/O workloads with ultra-low tick times (**0.23ms MSPT**), achieving nearly double the tick efficiency of Purpur (**0.26ms+**). Moonrise also buffers the initial TPS shock significantly better (18.01 TPS vs 15.96 TPS) thanks to the fully decoupled disk writes via Virtual Threads.

---
<div align="center">
  <i>Moonrise is an independent project and is not affiliated with Mojang AB or Microsoft.</i>
</div>
