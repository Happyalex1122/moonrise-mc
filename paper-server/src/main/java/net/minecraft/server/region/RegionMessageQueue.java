package net.minecraft.server.region;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Region 간 메시지 패싱(Actor Model)을 지원하기 위해 설계된 Lock-free MPSC 메시지 큐입니다.
 * ConcurrentLinkedQueue를 기반으로 하여 여러 스레드가 동시에 안전하게 메시지(Intent)를 보낼 수 있으며,
 * 단일 RegionThread가 순차적으로 메시지를 소비(Consume)합니다.
 */
public class RegionMessageQueue {

    /**
     * Region 간에 주고받는 메시지 및 비동기 작업의 의도를 정의하는 인터페이스입니다.
     */
    @FunctionalInterface
    public interface RegionIntent {
        /**
         * 대상 RegionThread 컨텍스트 내에서 실행할 로직을 정의합니다.
         */
        void execute();
    }

    // Lock-free MPSC(Multi-Producer Single-Consumer) 큐를 3개의 도메인으로 분할
    private final Queue<RegionIntent> blockQueue = new ConcurrentLinkedQueue<>();
    private final Queue<RegionIntent> entityQueue = new ConcurrentLinkedQueue<>();
    private final Queue<RegionIntent> playerQueue = new ConcurrentLinkedQueue<>();

    public void addBlockIntent(RegionIntent intent) {
        if (intent != null) blockQueue.add(intent);
    }

    public void addEntityIntent(RegionIntent intent) {
        if (intent != null) entityQueue.add(intent);
    }

    public void addPlayerIntent(RegionIntent intent) {
        if (intent != null) playerQueue.add(intent);
    }

    /**
     * 레거시 호환성을 위한 범용 추가 메서드 (기본값으로 Entity 우선순위를 가짐)
     */
    public void add(RegionIntent intent) {
        addEntityIntent(intent);
    }

    /**
     * 현재 큐에 쌓여 있는 모든 Intent를 우선순위에 따라 순차적으로 꺼내어 실행합니다.
     * 순서: Block (환경) -> Entity (몹/투사체) -> Player (상호작용)
     */
    public void processAll() {
        processQueue(blockQueue);
        processQueue(entityQueue);
        processQueue(playerQueue);
    }

    private void processQueue(Queue<RegionIntent> targetQueue) {
        RegionIntent intent;
        while ((intent = targetQueue.poll()) != null) {
            try {
                intent.execute();
            } catch (Throwable t) {
                // 특정 작업의 실패가 전체 루프나 스레드 정지를 유발하지 않도록 예외 처리
                System.err.println("Error processing region intent: " + t.getMessage());
                t.printStackTrace();
            }
        }
    }

    /**
     * 모든 큐가 비어있는지 확인합니다.
     */
    public boolean isEmpty() {
        return blockQueue.isEmpty() && entityQueue.isEmpty() && playerQueue.isEmpty();
    }

    /**
     * 대기 중인 모든 Intent의 총 개수를 반환합니다.
     */
    public int size() {
        return blockQueue.size() + entityQueue.size() + playerQueue.size();
    }
}
