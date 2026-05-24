package week03.서동휘;

import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JFR(Java Flight Recorder)을 이용한 가상 스레드 고정현상 실시간 모니터링
 *
 * [JVM 플래그 방식 - 가장 간단]
 *   java -Djdk.tracePinnedThreads=short -cp out week03.서동휘.ThreadPinnedExample
 *   java -Djdk.tracePinnedThreads=full  -cp out week03.서동휘.ThreadPinnedExample
 *     short : 고정을 유발한 핵심 프레임만 출력
 *     full  : 전체 스택 트레이스 출력
 *
 * [JFR 방식 - 이 예제]
 *   java -cp out week03.서동휘.PinningJFRMonitor
 *   RecordingStream으로 jdk.VirtualThreadPinned 이벤트를 실시간 수신한다.
 *   JVM 플래그 없이도 코드 안에서 핀닝을 감지하고 후처리할 수 있다.
 *
 * ※ JDK 21 기준 동작. JDK 24+(JEP 491)에서는 synchronized 핀닝이 해결되어
 *    이벤트가 발생하지 않는다.
 */
public class PinningJFRMonitor {

    private static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        AtomicInteger pinCount = new AtomicInteger();

        try (var rs = new RecordingStream()) {
            // 1ms 이상 지속된 핀닝 이벤트만 감지 (노이즈 제거)
            rs.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(1));

            rs.onEvent("jdk.VirtualThreadPinned", event -> {
                int count = pinCount.incrementAndGet();
                System.out.printf("[핀닝 감지 #%d] 지속시간: %dms  스레드: %s%n",
                        count,
                        event.getDuration().toMillis(),
                        event.getThread().getJavaName());
            });

            rs.startAsync(); // 백그라운드에서 이벤트 수집 시작

            System.out.println("=== synchronized 블록 내 sleep → 핀닝 발생 ===");
            runWithPinning();

            Thread.sleep(500); // 미수신 이벤트 플러시 대기
            System.out.println("\n총 감지된 핀닝 횟수: " + pinCount.get());
        }
    }

    /**
     * synchronized 블록 안에서 sleep → 캐리어 스레드 핀닝 유발
     */
    static void runWithPinning() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            threads.add(Thread.ofVirtual().unstarted(() -> {
                synchronized (lock) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }));
        }

        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
    }
}