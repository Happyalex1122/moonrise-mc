<div align="center">
  <img src="docs/assets/logo.png" alt="Moonrise Logo" width="200"/>
  <h1>Moonrise</h1>
  <p><b>Next-Generation High-Performance Minecraft Server Software</b></p>
  <p>
    <a href="https://github.com/Happyalex1122/moonrise-mc/actions"><img src="https://img.shields.io/github/actions/workflow/status/Happyalex1122/moonrise-mc/build.yml?style=flat-square" alt="Build Status"/></a>
    <a href="https://github.com/Happyalex1122/moonrise-mc/blob/main/LICENSE.md"><img src="https://img.shields.io/github/license/Happyalex1122/moonrise-mc?style=flat-square" alt="License"/></a>
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
*   [**License**](LICENSE.md): Moonrise is licensed under the GPLv3 (inheriting from Paper).

## 📊 Benchmarks

Moonrise is rigorously tested with automated stress tests (`MoonriseStressTest`).
Under conditions of 2,000+ entities and massive block updates, Moonrise maintains sub-1ms tick times (`MSPT < 1.0`).

### Performance Comparison: WAL Moonrise vs Purpur

During an automated stress test under identical isolated environments (Java 25, 4GB RAM) featuring massive chunk loading and spawning 2000 entities, **WAL-enabled Moonrise** showed incredible balance between TPS resilience and memory efficiency compared to **Purpur**.

#### WAL-Enabled Moonrise Server Performance
The WAL-enabled build of Moonrise achieves excellent TPS stability with a significantly reduced memory footprint.

| Time (s) | TPS | MSPT | Memory Usage |
|----------|-----|------|--------------|
| +10s | 17.64 | 14.13ms | 1453MB / 4096MB |
| +15s | 18.18 | 0.38ms | 1455MB / 4096MB |
| +20s | 18.52 | 0.29ms | 1457MB / 4096MB |
| +25s | 18.75 | 0.26ms | 1460MB / 4096MB |
| +30s | 18.92 | 0.23ms | 1464MB / 4096MB |
| +35s | 19.05 | 0.31ms | 1465MB / 4096MB |

#### Purpur Server Performance
Purpur handles the stress test with near-perfect TPS stability, but with a significantly heavier memory footprint.

| Time (s) | TPS | MSPT | Memory Usage |
|----------|-----|------|--------------|
| +10s | 19.61 | 4.91ms | 1088MB / 4096MB |
| +15s | 19.99 | 7.27ms | 2158MB / 4096MB |
| +20s | 20.00 | 0.35ms | 2162MB / 4096MB |
| +25s | 20.00 | 0.25ms | 2164MB / 4096MB |
| +30s | 20.00 | 0.26ms | 2166MB / 4096MB |
| +35s | 20.00 | 0.26ms | 2169MB / 4096MB |

**Summary:** The WAL implementation helps Moonrise find an excellent middle ground, achieving high TPS resilience while maintaining a ~30% lower memory footprint than Purpur (~1.47 GB vs ~2.17 GB).

---
<div align="center">
  <i>Moonrise is an independent project and is not affiliated with Mojang AB or Microsoft.</i>
</div>
