# Minecraft Server Performance Benchmark: WAL Moonrise vs Purpur

## Overview
This report provides a side-by-side performance comparison of **WAL-enabled Moonrise** (optimized with Write-Ahead Log) and **Purpur** Minecraft servers during an automated stress test. 

Both servers were tested under identical isolated environments with the following parameters:
- **Java Version:** OpenJDK 25 (Temurin-25.0.3+9)
- **Memory Allocation:** 4GB (`-Xms4G -Xmx4G`)
- **Stress Test Conditions:** Phase 1: Massive Chunk Loading (Elytra Sim), Phase 2: Spawning 2000 Entities.

## Results

### WAL-Enabled Moonrise Server Performance
The WAL-enabled build of Moonrise showed the best balance yet, improving TPS resilience even further while reclaiming a significant chunk of memory efficiency.

| Time (s) | TPS | MSPT | Memory Usage |
|----------|-----|------|--------------|
| +10s | 17.64 | 14.13ms | 1453MB / 4096MB |
| +15s | 18.18 | 0.38ms | 1455MB / 4096MB |
| +20s | 18.52 | 0.29ms | 1457MB / 4096MB |
| +25s | 18.75 | 0.26ms | 1460MB / 4096MB |
| +30s | 18.92 | 0.23ms | 1464MB / 4096MB |
| +35s | 19.05 | 0.31ms | 1465MB / 4096MB |
| +40s | 19.15 | 0.24ms | 1467MB / 4096MB |
| +45s | 19.23 | 0.25ms | 1471MB / 4096MB |

### Purpur Server Performance
Purpur handled the stress test with near-perfect TPS stability, recovering to 20 TPS almost instantly, but with a heavier memory footprint.

| Time (s) | TPS | MSPT | Memory Usage |
|----------|-----|------|--------------|
| +10s | 19.61 | 4.91ms | 1088MB / 4096MB |
| +15s | 19.99 | 7.27ms | 2158MB / 4096MB |
| +20s | 20.00 | 0.35ms | 2162MB / 4096MB |
| +25s | 20.00 | 0.25ms | 2164MB / 4096MB |
| +30s | 20.00 | 0.26ms | 2166MB / 4096MB |
| +35s | 20.00 | 0.26ms | 2169MB / 4096MB |
| +40s | 20.00 | 0.28ms | 2173MB / 4096MB |
| +45s | 20.00 | 0.24ms | 2176MB / 4096MB |

## Analysis & Conclusion

1. **TPS Stability**
   Purpur still maintains a slight lead in TPS resilience, staying at or near a perfect 20 TPS. However, the WAL-enabled Moonrise build improved upon previous Moonrise iterations, starting the load phase at 17.6 TPS (up from 15.1 and 17.0 previously) and closing the gap towards 20 TPS smoothly over the 45-second window.

2. **Memory Efficiency (Winner: WAL Moonrise)**
   The WAL optimization in Moonrise managed to find a sweet spot. It reduced the memory footprint to ~1.47 GB, comfortably under Purpur's ~2.17 GB, without falling back to the 400MB extremes that caused heavy TPS dips in the very first iteration.

3. **MSPT Handling**
   WAL Moonrise had a brief 14.13ms spike on initial load (faster than the 40ms spike of the previous optimization) and then immediately plummeted to sub-millisecond ranges (0.23-0.38ms), matching Purpur's processing speed after the spike.

**Summary:** The WAL implementation was highly successful. Sequential disk writes did not negatively impact TPS—in fact, it helped Moonrise find an excellent middle ground, achieving high TPS resilience while maintaining a ~30% lower memory footprint than Purpur.
