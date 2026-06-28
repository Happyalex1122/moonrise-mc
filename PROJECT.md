# Project: Minecraft Server Engine Moonrise Optimizations

## Architecture
- **Multi-module Gradle Project**:
  - `:paper-api`: Bukkit/Paper API definitions.
  - `:paper-server`: Main implementation of the Minecraft server, containing Mojang-mapped classes, Spigot patches, and Moonrise modifications.
- **Data Storage**:
  - Migrate SQLite storage (`entities.db`, `server_config.db`, `worlds.db`) to LMDB via Java 25 Foreign Function & Memory (FFM) API (Panama).
- **Memory Management**:
  - Tick-scoped off-heap Arena Allocator for transient objects (`BlockPos`, `Vec3` / `Vec3d`).
- **Physics & Collision**:
  - Bounding box collision checks (`CollisionUtil`) optimized with ARM NEON SIMD via Vector API.
- **Asynchronous Execution**:
  - Virtual thread dispatch queues for I/O operations (chunk loads, packet decoding, WAL checkpoints).
  - Player kinematic predictive chunk prefetching based on velocity and direction.
- **Serialization**:
  - Zstd dictionary-trained compression for NBT data.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | E2E Testing Track | Design test runner & write Tiers 1-4 tests | none | PLANNED |
| M2 | Panama LMDB Migration | Implement LMDB FFM wrapper; migrate SQLite | M1 | PLANNED |
| M3 | Off-heap Arena Allocator | Off-heap memory allocator for BlockPos/Vec3d | M1 | PLANNED |
| M4 | Vector API Collisions | Implement Vector API NEON SIMD collisions | M1 | PLANNED |
| M5 | Virtual Thread I/O & Prefetching | Virtual Thread dispatch queues and kinematic prefetching | M1 | PLANNED |
| M6 | Zstd NBT Dictionary | Zstd dictionary training and NBT compression | M1 | PLANNED |
| M7 | Performance Stress-Test Plugin | Bukkit plugin to spawn entities, generate chunks, fly Elytra, and log metrics | M1 | PLANNED |
| M8 | Final E2E & Hardening | Run all tests, Tier 5 adversarial coverage, and audit | M2-M7 | PLANNED |

## Interface Contracts
### `LMDBManager` ↔ Database Providers (`AsyncEntityDB`, `DBConfigProvider`)
- **Signature**: `byte[] get(byte[] key)`
- **Signature**: `void put(byte[] key, byte[] value)`
- **Signature**: `void delete(byte[] key)`
- **Error handling**: Throw custom `LMDBException` on native errors.

### `ArenaAllocator` ↔ Ticking loop
- **Signature**: `MemorySegment allocate(long size)`
- **Signature**: `void reset()`
- **Lifecycle**: Invoked at the end of each world tick.
