# Week 4

---

## 1부. 스레드 풀의 원리

---

### 1. 스레드 풀이 필요한 이유는?

-> 스레드를 매 작업마다 새로 생성하면 생성·소멸 비용이 크다. 스레드 풀은 미리 만들어 둔 스레드들이 작업 큐(BlockingQueue)에서 태스크를 꺼내 재사용함으로써 이 비용을 제거한다.

-> 또한 스레드 수를 제한해 과도한 컨텍스트 스위칭과 메모리 소비를 막는다.

---

### 2. 단순한 스레드 풀을 직접 만든다면 핵심 구성 요소는?

-> ① **작업 큐** (`BlockingQueue<Runnable>`): 외부에서 제출된 태스크를 대기시킨다.

-> ② **Worker 스레드 배열**: 큐에서 `take()`로 태스크를 꺼내 실행하는 루프를 돌린다.

-> ③ **running 플래그** (`volatile boolean`): `shutdown()` 호출 시 루프를 종료시킨다.

```java
class Worker extends Thread {
    public Worker(ThreadGroup threadGroup, String name) {
        super(threadGroup, name);
    }

    @Override
    public void run() {
        while (running) {
            try {
                Runnable task = queue.take();
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

---

### 3. SimpleThreadPool의 submit()과 shutdown() 동작 방식은?

-> `submit(Runnable task)`: `queue.put(task)`를 호출해 BlockingQueue에 태스크를 추가한다. 큐가 가득 찼으면 블로킹된다.

-> `shutdown()` / `close()`: `running = false`로 설정 후 모든 Worker에 `interrupt()`를 호출한다. `AutoCloseable`을 구현해 try-with-resources로 사용 가능하다.

---

## 2부. 이그제큐터 프레임워크

---

### 4. Java의 ExecutorService를 직접 구현한 스레드 풀과 비교하면?

-> Java 표준 라이브러리의 `ExecutorService`는 직접 만든 풀보다 훨씬 풍부한 기능(Future 반환, 스레드 팩토리, 거부 정책, 라이프사이클 관리 등)을 제공한다.

```java
ExecutorService service = Executors.newFixedThreadPool(10);
```

---

### 5. ThreadPoolExecutor의 핵심 파라미터는?

-> `ThreadPoolExecutor`의 생성자에서 직접 설정할 수 있는 주요 파라미터:

| 파라미터 | 설명 |
|---------|------|
| `corePoolSize` | 기본으로 유지할 스레드 수 |
| `maximumPoolSize` | 최대 스레드 수 |
| `keepAliveTime` | corePoolSize 초과 스레드가 유휴 상태로 유지되는 시간 |
| `unit` | keepAliveTime 단위 |
| `workQueue` | 태스크를 대기시킬 큐 |

---

### 6. 주요 Executors 팩토리 메서드 종류와 특징은?

-> **FixedThreadPool**: 고정된 수의 스레드. CPU 바운드 작업에 적합. `Executors.newFixedThreadPool(n)`

-> **CachedThreadPool**: 필요에 따라 스레드 생성/재사용. I/O 바운드 단기 작업에 적합. 유휴 스레드는 60초 후 제거. `Executors.newCachedThreadPool()`

-> **SingleThreadExecutor**: 단일 스레드로 순차 실행 보장. `Executors.newSingleThreadExecutor()`

-> **ScheduledThreadPoolExecutor**: 지연 실행 또는 주기적 실행. `Executors.newScheduledThreadPool(n)`

-> **WorkStealingPool**: 내부적으로 ForkJoinPool 사용. Work-Stealing 알고리즘 적용. `Executors.newWorkStealingPool()`

---

### 7. ThreadFactory는 무엇이고 왜 쓰는가?

-> `ThreadFactory`는 스레드 풀이 새 스레드를 생성할 때 호출하는 팩토리 인터페이스다.

-> 스레드 이름, 데몬 여부, 우선순위, ThreadGroup 등을 커스터마이징할 수 있다.

```java
public ThreadPoolExecutor(int corePoolSize, ...,
    ThreadFactory threadFactory, ...) { ... }
```

---

## 3부. Callable과 Future

---

### 8. Runnable과 Callable의 차이는?

-> `Runnable`: 반환값 없음, 체크 예외 선언 불가.

-> `Callable<V>`: `V call() throws Exception` — 결과를 반환하고 체크 예외를 던질 수 있다.

```java
public interface Callable<V> {
    V call() throws Exception;
}
```

---

### 9. Future의 주요 메서드는?

-> `ExecutorService.submit(Callable)`은 `Future<V>`를 반환한다.

| 메서드 | 설명 |
|--------|------|
| `V get()` | 결과가 나올 때까지 블로킹 |
| `V get(timeout, unit)` | 타임아웃 지정 블로킹 |
| `boolean cancel(boolean)` | 태스크 취소 시도 |
| `boolean isCancelled()` | 취소 여부 확인 |
| `boolean isDone()` | 완료 여부 확인 |

---

### 10. Future.get()의 주의점은?

-> `get()`은 블로킹 호출이다. 태스크가 완료되지 않으면 호출 스레드가 대기한다.

-> 여러 Future를 순서대로 `get()`하면 앞 태스크가 느릴 경우 뒤의 완료된 결과도 늦게 가져오게 된다.

-> `get(timeout, TimeUnit)`으로 타임아웃을 지정하거나, `CompletableFuture`를 사용하는 것이 더 유연하다.

---

## 4부. ForkJoinPool

---

### 11. ForkJoinPool이란 무엇이고 일반 스레드 풀과의 차이는?

-> `ForkJoinPool`은 **Work-Stealing 알고리즘**을 사용하는 특수 스레드 풀이다.

-> 각 Worker 스레드가 자신의 **덱(deque)**을 갖고, 자신의 큐가 비면 다른 Worker의 큐에서 태스크를 훔쳐(steal)와 실행한다.

-> 분할 정복(divide and conquer) 형태의 재귀 태스크에 적합하다.

---

### 12. ForkJoinPool에서 태스크를 분기·합류하는 방법은?

-> `RecursiveTask<V>`를 상속하고 `compute()` 메서드를 구현한다.

-> `fork()`: 하위 태스크를 비동기로 실행 큐에 넣는다.

-> `join()`: 하위 태스크 결과를 기다린다.

```java
FibonacciTask f1 = new FibonacciTask(n - 1);
FibonacciTask f2 = new FibonacciTask(n - 2);
f1.fork();
long result = f2.compute() + f1.join();
return result;
```

---

### 13. ForkJoinPool을 사용할 때 asyncMode란 무엇인가?

-> `asyncMode = true`로 설정하면 Worker 스레드의 덱이 FIFO 방식으로 동작한다(기본은 LIFO).

-> fork/join 재귀 분해보다는 이벤트 기반 태스크처럼 독립적인 Runnable 태스크를 처리할 때 유용하다.

```java
ForkJoinPool pool = new ForkJoinPool(
    Runtime.getRuntime().availableProcessors(),
    ForkJoinPool.defaultForkJoinWorkerThreadFactory,
    null, true  // asyncMode = true
);
```

---

### 14. 가상 스레드가 내부적으로 ForkJoinPool을 사용하는 이유는?

-> 가상 스레드의 스케줄러는 `ForkJoinPool`을 캐리어 스레드 풀로 사용한다.

-> Work-Stealing 덕분에 특정 캐리어 스레드가 놀지 않고 다른 가상 스레드를 계속 실행할 수 있어 CPU 활용률이 높다.

-> 가상 스레드가 블로킹되면 캐리어 스레드에서 언마운트되고, ForkJoinPool의 빈 Worker가 다른 가상 스레드를 즉시 실행한다.

---

## 5부. CAS(Compare-And-Swap)

---

### 15. CAS 연산이란 무엇인가?

-> **Compare-And-Swap**: "현재 값이 expected와 같으면 newValue로 교체하고, 아니면 아무것도 하지 않는다"는 원자적 연산이다.

-> CPU 명령어 수준(lock cmpxchg 등)에서 지원되므로 `synchronized` 없이 스레드 안전한 업데이트가 가능하다.

-> Java에서는 `AtomicInteger`, `AtomicLong`, `VarHandle.compareAndSet()` 등으로 사용한다.

---

### 16. VarHandle을 사용한 CAS 카운터 구현 방법은?

```java
public class AtomicCounter {
    private static final VarHandle COUNTER_HANDLE =
        MethodHandles.lookup().findVarHandle(
            AtomicCounter.class, "counter", int.class);

    private volatile int counter = 0;

    public void increment() {
        int current, next;
        do {
            current = counter;
            next = current + 1;
        } while (!COUNTER_HANDLE.compareAndSet(this, current, next));
    }

    public int get() { return counter; }
}
```

-> CAS가 실패하면(다른 스레드가 먼저 변경) 재시도(spin)한다. 성공할 때까지 루프를 반복한다.

---

### 17. CAS의 한계 — ABA 문제란?

-> 값이 A → B → A로 변경된 경우, CAS는 현재 값이 A임을 보고 변경이 없었다고 판단해 버린다.

-> 해결책: `AtomicStampedReference`처럼 버전 번호(stamp)를 함께 비교해 ABA를 감지한다.

---

## 6부. 컨티뉴에이션(Continuation)

---

### 18. 컨티뉴에이션이란 무엇인가?

-> 컨티뉴에이션은 실행을 **일시 중단(yield)했다가 나중에 재개(resume)할 수 있는 실행 단위**다.

-> Java에서는 `jdk.internal.vm.Continuation`(내부 API)으로 구현되어 있으며, 가상 스레드의 핵심 동작 원리다.

```java
ContinuationScope scope = new ContinuationScope("main");
Continuation continuation = new Continuation(scope, () -> {
    System.out.println("Hello from continuation");
    Continuation.yield(scope);
    System.out.println("Again from continuation");
});
continuation.run(); // "Hello from continuation" 출력 후 일시 중단
continuation.run(); // "Again from continuation" 출력 후 종료
```

---

### 19. Continuation.yield()와 run()의 동작 흐름은?

-> `Continuation.run()`: 컨티뉴에이션 실행을 시작하거나 중단된 지점부터 재개한다.

-> `Continuation.yield(scope)`: 현재 실행을 일시 중단하고 `run()`을 호출한 호출자에게 제어를 돌려준다.

-> 상태 전이: **시작 전** → `run()` → **실행 중** → `yield()` → **일시 중단** → `run()` → **재개** → **완료**

---

### 20. 컨티뉴에이션이 가상 스레드와 어떻게 연결되는가?

-> 가상 스레드는 내부적으로 컨티뉴에이션으로 구현된다.

-> 가상 스레드가 블로킹 I/O를 만나면 `Continuation.yield()`가 호출되어 캐리어 스레드에서 언마운트된다.

-> I/O가 완료되면 `Continuation.run()`이 다시 호출되어 다른 캐리어 스레드에 마운트되어 재개된다.

---

## 7부. 가상 스레드 직접 만들어보기 (NanoThread)

---

### 21. NanoThread를 직접 구현하는 핵심 구조는?

-> `NanoThread`는 `Continuation`을 래핑해 가상 스레드의 동작을 흉내낸다.

```java
public class NanoThread {
    private static final ContinuationScope SCOPE =
        new ContinuationScope("NANO_THREAD_SCOPE");
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private final Continuation continuation;
    private final int id;

    public NanoThread(Runnable runnable) {
        this.id = COUNTER.incrementAndGet();
        this.continuation = new Continuation(SCOPE, runnable);
    }

    public void start() {
        NANO_THREAD_SCHEDULER.schedule(this);
    }

    @Override
    public String toString() {
        return "NanoThread-" + id;
    }
}
```

---

### 22. NanoThreadScheduler는 어떻게 동작하는가?

-> `WorkStealingPool`(내부적으로 ForkJoinPool)과 `ScheduledExecutorService`를 조합해 NanoThread를 스케줄링한다.

-> `schedule(NanoThread nanoThread)` 호출 시 Worker 스레드에서 `nanoThread`의 `continuation.run()`을 실행한다.

-> `CURRENT_NANO_THREAD` ThreadLocal에 현재 실행 중인 NanoThread를 저장해 나중에 재스케줄링에 활용한다.

```java
public void schedule(NanoThread nanoThread) {
    workStealingPool.submit(() -> {
        CURRENT_NANO_THREAD.set(nanoThread);
        nanoThread.continuation.run();
        CURRENT_NANO_THREAD.remove();
    });
}
```

---

### 23. NanoThread에서 I/O 이벤트를 처리하는 방식은?

-> I/O 작업 중에 `Continuation.yield(NANO_THREAD_SCOPE)`를 호출해 현재 NanoThread를 일시 중단한다.

-> `IO_EVENT_SCHEDULER`(별도 스케줄러)가 I/O 완료 이벤트를 감지하면 해당 NanoThread를 다시 `schedule()`한다.

-> 이를 통해 캐리어 스레드(WorkStealingPool의 워커)가 블로킹되지 않고 다른 NanoThread를 실행할 수 있다.

---

### 24. NanoThread 실행 결과에서 Worker 이동을 어떻게 확인하는가?

-> NanoThread가 yield → 재스케줄 될 때 다른 ForkJoinPool Worker에서 실행될 수 있다:

```
NanoThread-1 running in VThread: NanoThread-1@ForkJoinPool-1-worker-1
Transfer: File_0 Running in VThread: NanoThread-1@ForkJoinPool-1-worker-3
Transfer Completed for File_0: NanoThread-1@ForkJoinPool-1-worker-2
```

-> worker-1 → worker-3 → worker-2 처럼 재개 시 다른 Worker가 담당할 수 있다.

---

## 8부. 가상 스레드의 I/O 블로킹 내부 동작

---

### 25. 가상 스레드가 I/O로 블로킹될 때 내부에서 일어나는 일은?

-> 가상 스레드가 블로킹 I/O를 호출하면 JDK 내부에서 `LockSupport.park()`가 호출된다.

-> `park()`는 `Continuation.yield()`를 트리거해 현재 가상 스레드를 캐리어 스레드에서 언마운트한다.

```java
static void park() {
    if (Thread.currentThread().isVirtual()) {
        // 가상 스레드: yield → 언마운트
    } else {
        // 플랫폼 스레드: 기존 park 동작
    }
}
```

-> 운영체제의 epoll/kqueue 등 비동기 I/O 멀티플렉서가 I/O 완료를 감지하면 `unpark()`가 호출되어 가상 스레드가 재스케줄된다.

---

### 26. JDK 라이브러리의 블로킹 API가 가상 스레드를 지원하는 방식은?

-> JDK 라이브러리의 거의 모든 블로킹 지점(`Socket`, `InputStream`, `Thread.sleep()` 등)은 가상 스레드를 감지하면 캐리어 스레드를 블로킹하는 대신 **언마운트** 방식으로 동작하도록 수정되어 있다.

-> 현재 대부분의 블로킹 연산은 가상 스레드를 지원하며, 캐리어 스레드가 유휴 상태로 낭비되지 않는다.

-> 이 구조 덕분에 개발자는 기존 블로킹 스타일 코드를 그대로 사용하면서도 높은 동시성을 얻을 수 있다.

---
