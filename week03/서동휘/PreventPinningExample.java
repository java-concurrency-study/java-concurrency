package week03.서동휘;

import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

public class PreventPinningExample {
    private static final ReentrantLock lock = new ReentrantLock();

    public static void main(String[] args) {
        var threadList = IntStream.range(0, 10)
                .mapToObj(i -> Thread.ofVirtual().unstarted(() -> {
                    if (i == 0) {
                        System.out.println(Thread.currentThread());
                    }

                    lock.lock();

                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        lock.unlock();
                    }
                    if (i == 0) {
                        System.out.println(Thread.currentThread());
                    }
                })).toList();

        threadList.forEach(Thread::start);
        threadList.forEach(thread -> {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
