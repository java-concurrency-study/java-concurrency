/**
 * 가상 스레드 기본 생성
 *
 * 실행: javac Ex05_BasicVirtualThread.java && java Ex05_BasicVirtualThread
 */
public class Ex05_BasicVirtualThread {

    public static void main(String[] args) throws InterruptedException {

        System.out.println("=== 방법 1: Thread.startVirtualThread() ===");
        Thread vt1 = Thread.startVirtualThread(() ->
            System.out.println("  [" + Thread.currentThread() + "] Hello from virtual thread!")
        );
        vt1.join(); // join 없으면 메인 스레드 종료 시 같이 꺼짐 (데몬 스레드이기 때문)

        System.out.println();
        System.out.println("=== 방법 2: Thread.ofVirtual().start() ===");
        Thread vt2 = Thread.ofVirtual()
            .name("my-virtual-thread")
            .start(() ->
                System.out.println("  [" + Thread.currentThread() + "] Hello from named virtual thread!")
            );
        vt2.join();

        System.out.println();
        System.out.println("=== 방법 3: unstarted() — 생성과 시작 분리 ===");
        Thread vt3 = Thread.ofVirtual()
            .name("lazy-virtual-thread")
            .unstarted(() ->
                System.out.println("  [" + Thread.currentThread() + "] Started later!")
            );
        System.out.println("  스레드 생성됨, 아직 시작 안 함. isAlive=" + vt3.isAlive());
        vt3.start();
        vt3.join();

        System.out.println();
        System.out.println("=== 가상 스레드 특성 확인 ===");
        Thread vt4 = Thread.ofVirtual().unstarted(() -> {});
        System.out.println("  isVirtual  : " + vt4.isVirtual());
        System.out.println("  isDaemon   : " + vt4.isDaemon());   // 항상 true
        System.out.println("  priority   : " + vt4.getPriority()); // 항상 5 (NORM_PRIORITY)
        System.out.println("  threadGroup: " + vt4.getThreadGroup().getName()); // 항상 VirtualThreads
    }
}