package week03.서동휘;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 처리량 비교를 통한 핀닝 영향 수치화
 *
 * 각 가상 스레드에 독립적인 락을 할당해 락 경합 없이
 * 순수한 핀닝 효과(캐리어 스레드 고갈)만 측정한다.
 *
 * [동작 원리]
 *
 * synchronized (JDK 21, 핀닝 발생):
 *   가상스레드가 sleep 중에도 캐리어 스레드를 점유한다.
 *   동시에 실행 가능한 가상스레드 수 = 캐리어 스레드 수(≈ CPU 코어 수)
 *   → 총 소요 시간 ≈ (스레드 수 / 캐리어 수) × sleep 시간
 *
 * ReentrantLock (핀닝 없음):
 *   sleep 중 가상스레드가 언마운트되어 캐리어 스레드가 해방된다.
 *   모든 가상스레드가 거의 동시에 sleep → 거의 동시에 재개
 *   → 총 소요 시간 ≈ sleep 시간
 *
 * [예상 결과 - JDK 21, 100스레드, 8코어]
 *   synchronized : ~250ms  (100/8 = 12배치 × 20ms)
 *   ReentrantLock: ~ 20ms
 *   차이          : 약 12x
 *
 * ※ JDK 24+(JEP 491)에서는 synchronized도 핀닝이 해결되어 두 결과가 비슷해진다.
 */
public class PinningThroughputCompare {

    static final int VIRTUAL_THREAD_COUNT = 100;
    static final int SLEEP_MS = 20;

    public static void main(String[] args) throws Exception {
        int carriers = Runtime.getRuntime().availableProcessors();
        System.out.printf("가상 스레드: %d개 | sleep: %dms | 캐리어 스레드(약): %d개%n%n",
                VIRTUAL_THREAD_COUNT, SLEEP_MS, carriers);

        // 워밍업 (JIT 컴파일 영향 제거)
        measure("워밍업", true);
        measure("워밍업", false);

        System.out.println("\n=== 본 측정 ===");
        long syncTime = measure("synchronized (핀닝 발생)", true);
        long lockTime = measure("ReentrantLock (핀닝 없음)", false);

        System.out.printf("%n[결과 요약]%n");
        System.out.printf("  synchronized : %4dms%n", syncTime);
        System.out.printf("  ReentrantLock: %4dms%n", lockTime);
        if (lockTime > 0) {
            System.out.printf("  차이          : %.1fx%n", (double) syncTime / lockTime);
        }
    }

    static long measure(String label, boolean useSynchronized) throws InterruptedException {
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
            // 각 스레드에 독립 락 → 락 경합 제거, 핀닝 효과만 측정
            final Object syncLock = new Object();
            final ReentrantLock reentrantLock = new ReentrantLock();

            threads.add(Thread.ofVirtual().unstarted(() -> {
                if (useSynchronized) {
                    synchronized (syncLock) {
                        try {
                            Thread.sleep(SLEEP_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } else {
                    reentrantLock.lock();
                    try {
                        Thread.sleep(SLEEP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        reentrantLock.unlock();
                    }
                }
            }));
        }

        long start = System.currentTimeMillis();
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("  [%-26s] %dms%n", label, elapsed);
        return elapsed;
    }
}