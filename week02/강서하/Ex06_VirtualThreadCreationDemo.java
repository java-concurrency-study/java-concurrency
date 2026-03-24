/**
 * 예제 7. 가상 스레드 생성 시연
 *
 * 10,000개 작업 (각 1초 대기) 을 3가지 방식으로 실행하고 결과 비교
 *
 * 실행: javac Ex06_VirtualThreadCreationDemo.java && java Ex06_VirtualThreadCreationDemo
 */
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

public class Ex06_VirtualThreadCreationDemo {

    static final int TASK_COUNT = 10_000;

    public static void main(String[] args) {

        System.out.printf("작업 수: %,d개  /  작업당 대기: 1초%n%n", TASK_COUNT);

        // 1. Virtual Threads
        System.out.println("=== 1. Virtual Threads ===");
        run("Virtual Threads", Executors.newVirtualThreadPerTaskExecutor());

        // 2. CachedThreadPool — 플랫폼 스레드를 10,000개 생성 시도 → 비정상 종료 위험
        System.out.println("\n=== 2. CachedThreadPool ===");
        System.out.println("  ⚠ 플랫폼 스레드 10,000개 생성 시도 → OOM 또는 비정상 종료 가능");
        run("CachedThreadPool", Executors.newCachedThreadPool());

        // 3. FixedThreadPool(200) — 동시에 200개만 처리 → 나머지 9,800개는 대기
        System.out.println("\n=== 3. FixedThreadPool(200) ===");
        System.out.println("  ⚠ 동시 처리 = 200개 → 나머지는 큐에서 대기 → 동시성 급격히 감소");
        run("FixedThreadPool(200)", Executors.newFixedThreadPool(200));
    }

    static void run(String label, ExecutorService executor) {
        long start = System.currentTimeMillis();
        try (executor) {
            IntStream.range(0, TASK_COUNT).forEach(i ->
                executor.submit(() -> {
                    Thread.sleep(Duration.ofSeconds(1));
                    return i;
                })
            );
        } catch (Exception e) {
            System.out.println("  [ERROR] " + e.getMessage());
            return;
        }
        long elapsed = System.currentTimeMillis() - start;
        double throughput = TASK_COUNT * 1000.0 / elapsed;
        System.out.printf("  총 시간: %,dms  /  처리량: %.2f tasks/s%n", elapsed, throughput);
    }
}