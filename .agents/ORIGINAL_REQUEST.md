# Original User Request

## Initial Request — 2026-06-29T07:13:38+09:00

An extreme-performance, ARM-optimized Minecraft server engine (Paper-fork "Moonrise"). It includes radical optimizations like LMDB off-heap storage via Panama, Vector API SIMD, predictive chunk prefetching, and virtual threads for I/O.

Working directory: D:\java_workspace\paper-fork
Integrity mode: benchmark

## Requirements

### R1. Complete Core Engine Modifications (Phases 1 & 2)
Implement DBConfigProvider, AsyncEntityDB, Linear Region format, AdaptiveTPSManager, and native multi-world command routing (`/world`). Add Virtual Thread dispatch queues for I/O (chunk load, packet decode, WAL checkpoints) and predictive chunk prefetching based on player kinematic vectors. Implement Zstd dictionary training for NBT data.

### R2. Radical ARM/Memory Optimizations (Phase 3)
Force the entire Paper-Server project to compile and run on Java 22+. Implement ARM NEON SIMD via Vector API for bounding box collisions. Implement an off-heap Arena Allocator for tick-scoped transient objects (BlockPos, Vec3d) to eliminate GC. Migrate SQLite databases to LMDB accessed via Project Panama (Foreign Function & Memory API) for zero-copy native reads.

### R3. Performance Stress-Test Plugin
Create a dedicated Paper/Bukkit plugin in the test server directory that programmatically spawns thousands of entities, forces massive chunk generation, and simulates high-speed Elytra flight. It must output performance metrics (TPS, MSPT, GC pauses) to the console to prove the efficacy of the ARM optimizations.

## Acceptance Criteria

### Verification & Performance
- [ ] Code compiles successfully via `.\gradlew createPaperclipJar` using Java 22+.
- [ ] Server boots without crashing on Java 22+ with Panama and Vector API flags enabled.
- [ ] The custom stress-test plugin successfully executes its heavy load sequence.
- [ ] Under stress-test load, the server maintains >18 TPS, and the Arena allocator correctly resets memory without causing OutOfMemory errors.

Please resume the Implementation and Testing tracks where they left off previously. The github repo is initialized, so modify files and commit them locally.

## Follow-up — 2026-06-29T13:19:26Z

모든 하위 에이전트 및 태스크에 대해 즉시 Caveman 모드를 강제 적용할 것. 현재 진행 중인 모든 작업 목록(딴짓하는 작업 포함 여부)을 보고하고, 오직 Paper-fork 빌드 및 ARM 최적화 관련 핵심 목표에만 집중할 것. 불필요한 작업은 즉시 중지하고 보고바람.
