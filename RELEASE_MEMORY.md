# Moonrise Release Memory Log

이 파일은 새로운 릴리스가 배포될 때마다, 해당 버전에 적용된 핵심 기술적 기억과 구성(Configuration)에 대한 히스토리를 AI가 지속적으로 추적하고 학습하기 위해 기록하는 문서입니다.

## [v1.1.5] - 2026-07-15
**릴리스 주요 변경점 & 기억해야 할 기술적 Context:**
1. **Docker 컨테이너 환경의 Thread 프로비저닝 최적화 (`MoonriseCommon.java`)**: 
   - 기존의 `OSNuma` 라이브러리가 Docker의 cgroup 제한(CPU limits)을 무시하거나 `/sys/` 권한 부족으로 물리 코어 수를 1개로 오인출하는 버그를 해결.
   - JVM의 `Runtime.getRuntime().availableProcessors()`를 크로스체크하여, Docker 컨테이너 환경에서도 실제 할당된 코어 수를 정확히 인지하도록 패치함.
   - **4코어 (A1 인스턴스 등) 저사양 최적화**: 4코어 이하 환경에서 워커 스레드와 IO 스레드를 지나치게 적게 잡는 기존 공식을 수정하여 `Math.max(2, defaultWorkerThreads - 1)` 적용. (4코어 기준 Worker 3개, IO 2개 강제 할당).
2. **패킷 스레드 충돌 및 Disconnect Spam 핫픽스 (`PacketSendListener.java`)**:
   - `StacklessClosedChannelException`이 비정상적으로 콘솔을 도배하는 문제 해결. `channel.isActive()` 및 `channel.isOpen()` 체크 로직을 `exceptionallySend` 및 fallback 처리 구간에 추가하여 연결이 이미 끊긴 클라이언트에게 패킷을 강제로 밀어넣다 예외가 발생하는 현상을 방어함.
3. **ARM 최적화 플래그 및 GC 세팅 (`start.sh` 기반 지식)**:
   - `-XX:+ZGenerational` (Java 21/25의 ZGC)를 사용하여 STW(Stop-The-World)를 밀리초 단위로 억제.
   - `network-compression-threshold=1024`로 설정하여 ARM 칩셋에서 불필요하게 작은 패킷까지 압축하며 발생하는 CPU 오버헤드를 경감.

*(※ 이후 릴리스 시, 이 문서 상단에 새로운 버전을 추가하여 과거의 해결책이 유실되지 않고 후속 버그 해결의 단서로 쓰이도록 합니다.)*
