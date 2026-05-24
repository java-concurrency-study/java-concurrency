package week03.서동휘;

import java.util.List;
import java.util.stream.IntStream;

//java 21 vs java 25
public class ThreadPinnedExample {

    private static final Object lock = new Object();

    public static void main(String[] args) {
        List<Thread> threeadList = IntStream.range(0, 10)
                .mapToObj(i -> Thread.ofVirtual().unstarted(() -> {
                    if (i == 0) {
                        System.out.println(Thread.currentThread());
                    }
                    synchronized (lock) {
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    if (i == 0) {
                        System.out.println(Thread.currentThread());
                    }
                })).toList();

        threeadList.forEach(Thread::start);
        threeadList.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
