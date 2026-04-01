package week02.donghwi;

public class Week02 {

    // 예제 1: Virtual Thread
    static class Ex01 {
        public static void main(String[] args) throws InterruptedException {
            Thread vt = Thread.startVirtualThread(() ->
                System.out.println("Virtual Thread: " + Thread.currentThread())
            );
            vt.join();
        }
    }

    static class Ex02 {
        //예제 1 : Platform thread Interttuption
        public static void main(String[] args) {
            Thread platformThread = Thread.ofPlatform().start(() -> {
                try {
                    System.out.println("Platform Thread: " + Thread.currentThread());
                    for (int i=0; i<5; i++) {
                        System.out.println("Platform thread working : " + i);
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException e) {
                    System.out.println("Platform thread interrupted");
                }
            });
            try  {
                platformThread.join(); //일정 시간동안 스레드가 계속 실행
            } catch (InterruptedException e) {}

            platformThread.interrupt();
        }
    }

    static class Ex03 {
        //예제 2: VirtualThreadInterruption
        public static void main(String[] args) {
            Thread virtualThread = Thread.ofVirtual().start(() -> {
                try {
                    System.out.println("Virtual thread started ...");
                    for (int i=0; i<5; i++) {
                        System.out.println("Platform thread working : " + i);
                        Thread.sleep(1000);
                    }
                    System.out.println("Virtual thread finished");
                } catch (InterruptedException e) {
                    System.out.println("Virtual thread interrupted");
                }
            });
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {}
        }
    }

    // 예제 4: 가상 스레드에 적합한 I/O 작업
    // - 네트워크/DB 요청처럼 대기가 많은 작업은 가상 스레드가 유리
    // - 블로킹 중에 캐리어 스레드를 반납하므로 수천 개 동시 처리 가능
    static class Ex04 {
        static final int TASK_COUNT = 20;

        public static void main(String[] args) throws InterruptedException {
            System.out.println("=== [적합] I/O 작업 - 가상 스레드 ===");
            System.out.println("총 " + TASK_COUNT + "개의 I/O 작업 시작\n");

            long start = System.currentTimeMillis();
            Thread[] threads = new Thread[TASK_COUNT];

            for (int i = 0; i < TASK_COUNT; i++) {
                final int taskNo = i + 1;
                threads[i] = Thread.ofVirtual().start(() -> {
                    try {
                        System.out.println("[작업 " + taskNo + "] DB 조회 시작 - " + Thread.currentThread());
                        Thread.sleep(500); // DB/네트워크 I/O 대기 시뮬레이션
                        System.out.println("[작업 " + taskNo + "] DB 조회 완료 ✓");
                    } catch (InterruptedException e) {
                        System.out.println("[작업 " + taskNo + "] 작업 중단됨");
                    }
                });
            }

            for (Thread t : threads) t.join();

            long elapsed = System.currentTimeMillis() - start;
            System.out.println("\n" + TASK_COUNT + "개 작업 완료 | 총 소요시간: " + elapsed + "ms");
            System.out.println("→ 직렬로 실행했다면 " + (TASK_COUNT * 500) + "ms 걸렸을 것");
        }
    }

    // 예제 5: 가상 스레드에 부적합한 CPU 집중 작업
    // - CPU를 계속 점유하는 작업은 가상 스레드여도 캐리어 스레드를 반납하지 못함
    // - 결국 플랫폼 스레드와 성능 차이가 없고, 오히려 오버헤드만 생김
    static class Ex05 {
        static final int TASK_COUNT = 5;

        public static void main(String[] args) throws InterruptedException {
            System.out.println("=== [부적합] CPU 집중 작업 - 가상 스레드 vs 플랫폼 스레드 비교 ===\n");

            // 가상 스레드로 CPU 집중 작업 실행
            System.out.println("--- 가상 스레드로 CPU 작업 ---");
            long virtualStart = System.currentTimeMillis();
            Thread[] virtualThreads = new Thread[TASK_COUNT];
            for (int i = 0; i < TASK_COUNT; i++) {
                final int taskNo = i + 1;
                virtualThreads[i] = Thread.ofVirtual().start(() -> {
                    System.out.println("[가상 스레드 " + taskNo + "] 소수 계산 시작 - " + Thread.currentThread());
                    long count = countPrimes(1_000_000);
                    System.out.println("[가상 스레드 " + taskNo + "] 소수 계산 완료 - 소수 개수: " + count);
                });
            }
            for (Thread t : virtualThreads) t.join();
            System.out.println("가상 스레드 총 소요시간: " + (System.currentTimeMillis() - virtualStart) + "ms\n");

            // 플랫폼 스레드로 CPU 집중 작업 실행
            System.out.println("--- 플랫폼 스레드로 CPU 작업 ---");
            long platformStart = System.currentTimeMillis();
            Thread[] platformThreads = new Thread[TASK_COUNT];
            for (int i = 0; i < TASK_COUNT; i++) {
                final int taskNo = i + 1;
                platformThreads[i] = Thread.ofPlatform().start(() -> {
                    System.out.println("[플랫폼 스레드 " + taskNo + "] 소수 계산 시작 - " + Thread.currentThread());
                    long count = countPrimes(1_000_000);
                    System.out.println("[플랫폼 스레드 " + taskNo + "] 소수 계산 완료 - 소수 개수: " + count);
                });
            }
            for (Thread t : platformThreads) t.join();
            System.out.println("플랫폼 스레드 총 소요시간: " + (System.currentTimeMillis() - platformStart) + "ms");
            System.out.println("\n→ CPU 작업은 두 스레드 간 성능 차이가 거의 없다.");
            System.out.println("→ 가상 스레드의 장점(블로킹 중 캐리어 반납)이 발휘되지 않기 때문.");
        }

        // 1부터 n까지 소수 개수 계산 (CPU 집중 작업 시뮬레이션)
        static long countPrimes(int n) {
            long count = 0;
            for (int i = 2; i <= n; i++) {
                boolean isPrime = true;
                for (int j = 2; j * j <= i; j++) {
                    if (i % j == 0) { isPrime = false; break; }
                }
                if (isPrime) count++;
            }
            return count;
        }
    }
}
