# Week 3

---

## 1부. synchronized와 Pinning 이슈

---

### 1. synchronized의 pinning 이슈란 무엇인가? (JDK 24 이전)

-> 가상 스레드가 락을 획득해 `synchronized` 블록에 진입하면, JVM은 해당 가상 스레드를 캐리어 스레드(carrier thread)에서 언마운트하지 않고 고정(pin) 시킨다.

-> 고정된 캐리어 스레드는 다른 가상 스레드가 마운트될 수 없으므로, 유휴 캐리어 스레드가 줄어든다.

-> 결국 "많은 가상 스레드를 적은 캐리어 스레드로 운용"하는 가상 스레드의 핵심 목적인 높은 동시성에 어긋난다.

---

### 2. pinning이 발생하는 두 가지 대표 경우?

-> ① `synchronized` 블록/메서드 내에서 I/O 대기 또는 `sleep` 등 블로킹 동작이 발생할 때

-> ② 네이티브 메서드(JNI) 호출 중일 때 — 네이티브 코드 실행 중에는 JVM이 스택 상태를 제어할 수 없어 언마운트가 불가능하다

---

### 3. ReentrantLock은 어떻게 pinning 이슈를 해결하는가?

-> 이를 이해하려면 `park()` / `unpark()`를 알아야 한다.

-> `ReentrantLock.lock()`은 내부적으로 `LockSupport.park()`를 호출한다. `park()`는 JVM에게 "이 가상 스레드를 잠시 멈춰도 된다"는 신호를 주어, 가상 스레드를 캐리어 스레드에서 언마운트시킨다. (그렇다고 lock이 park()을 무조건 호출하는 것은 아니고, 경합 시 park/unpark 기반으로 대기할 수 있음에 가까울 듯 하다)

-> 락이 해제되면 `LockSupport.unpark(thread)`가 호출되어 가상 스레드가 다시 마운트 가능 상태로 전환된다.

-> 덕분에 캐리어 스레드가 블로킹되지 않고, 다른 가상 스레드를 실행할 수 있게 된다.

```java
// ReentrantLock 사용 예시
private static final ReentrantLock lock = new ReentrantLock();

lock.lock();        // → LockSupport.park() → 가상 스레드 언마운트
try {
    Thread.sleep(25);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
} finally {
    lock.unlock();  // → LockSupport.unpark() → 가상 스레드 마운트 가능
}
```

---

### 4. park() / unpark() 메커니즘이란?

-> `LockSupport.park(thread)` : 해당 스레드를 일시 정지 상태로 만든다. 가상 스레드의 경우 캐리어 스레드에서 언마운트된다.

-> `LockSupport.unpark(thread)` : 정지된 스레드를 재개 가능 상태로 만든다. 가상 스레드는 ForkJoinPool의 빈 캐리어 스레드에 다시 마운트된다.

```
// 스레드 A (가상 스레드)
LockSupport.park();     // 스레드 A 일시 정지 (언마운트)

// 스레드 B
LockSupport.unpark(threadA);  // 스레드 A 재개 (마운트 가능)
```

---

### 5. synchronized vs ReentrantLock 동작 차이?

-> `synchronized` 사용 시: 동일한 worker에 계속 고정

```
VirtualThread[#28]/runnable@ForkJoinPool-1-worker-1
VirtualThread[#28]/runnable@ForkJoinPool-1-worker-1  ← 같은 worker 고정
```

-> `ReentrantLock` 사용 시: park/unpark 후 다른 worker에 마운트 가능

```
VirtualThread[#28]/runnable@ForkJoinPool-1-worker-1
VirtualThread[#28]/runnable@ForkJoinPool-1-worker-3  ← 다른 worker로 이동
```

---

## 2부. JDK 24의 개선 - JEP 491

---

### 6. JDK 24에서 pinning 이슈가 해결된 방법은? (JEP 491)

-> JEP 491 "Synchronize Virtual Threads without Pinning"이 JDK 24에 도입되었다.

-> JDK 24부터는 `synchronized` 블록 내에서도 가상 스레드가 언마운트될 수 있도록 JVM 내부 구현이 변경되었다.

-> JDK 24부터는 `synchronized`와 `ReentrantLock`의 가상 스레드 친화성 차이가 사라진다.

-> 확인 방법: `-Djdk.tracePinnedThreads=full` 옵션으로 pinning 발생 여부를 추적할 수 있으며, JDK 24에서는 동일 코드에서 pinning 로그가 출력되지 않는다.

---

## 3부. ThreadLocal의 문제점

---

### 7. 가상 스레드에서 ThreadLocal을 사용할 때의 문제는?

-> 가상 스레드는 각각 자신의 `ThreadLocal` 변수를 가질 수 있다. 이 자체는 정상 동작한다.

-> 문제는 메모리 소비.... 가상 스레드는 수십만 개가 생성될 수 있는데, 각 스레드가 `ThreadLocal`에 큰 객체를 저장하면 메모리가 폭발적으로 증가한다.

```java
// 1,000개 스레드 × LargeObject(500KB) → ~500MB
static class LargeObject {
    private byte[] data = new byte[1024 * 500]; // 500KB
}
// ThreadLocal<LargeObject> 사용 시 → Used: 338.4 MB, Committed: 3.2 GB
```

-> 추가 문제: 상속(InheritedThreadLocal) — 부모 스레드의 ThreadLocal 값이 자식 가상 스레드로 전파될 수 있어 의도치 않은 데이터 공유가 발생함.

---

### 8. ThreadLocal의 대안은 무엇인가?

-> Java 21부터 도입된 `ScopedValue` 를 사용한다. ScopedValue는 불변(immutable)이며 스코프 내에서만 유효해서, 가상 스레드 환경에서 더 안전하고 메모리도 덜 쓴다.

-> 만약에 그럼에도 사용해야한다면  ThreadLocal 사용 범위를 최소화하고, 가상 스레드 종료 시 `remove()`를 꼭 호출해서 메모리 누수를 막아야 한다.

---

## 4부. 모니터링 방법

---

### 9. 가상 스레드의 pinning 발생 모니터링 방법?

-> JVM 플래그 `-Djdk.tracePinnedThreads=full` 사용:

```bash
java -Djdk.tracePinnedThreads=full MyApp
```

출력 예시:
```
VirtualThread[#29]/runnable@ForkJoinPool-1-worker-1
    java.base/java.lang.VirtualThread.parkOnCarrierThread(VirtualThread.java:393)
    java.base/java.lang.VirtualThread.park(VirtualThread.java:369)
```

-> `short` 옵션으로 요약 출력도 가눙.

---

### 10. JFR(Java Flight Recorder)로 가상 스레드를 모니터링하는 방법은?

-> JDK가 제공하는 `jdk.VirtualThreadStart`, `jdk.VirtualThreadEnd`, `jdk.VirtualThreadPinned` 이벤트를 활용한다.

-> JFR 설정 XML:

```xml
<configuration>
  <event name="jdk.VirtualThreadingEnabled">
    <setting name="enabled">true</setting>
  </event>
  <event name="jdk.VirtualThreadPinned">
    <setting name="enabled">true</setting>
  </event>
  <event name="jdk.VirtualThreadSubmitFailed">
    <setting name="enabled">true</setting>
  </event>
</configuration>
```

-> recording 파일 분석

```bash
jfr print --events jdk.VirtualThreadPinned recording.jfr
```

출력 예시:
```
jdk.VirtualThreadStart {
  startTime = 10:22:14.936 (2024-01-23)
  javaThreadId = 23
  eventThread = "" (javaThreadId = 23)
}
```

---

### 11. jcmd로 가상 스레드 덤프를 생성하는 방법은?

-> `jcmd PID Thread.dump_to_file` 명령 사용함

```bash
# JSON 형식
jcmd <PID> Thread.dump_to_file -format=json <file>

# TEXT 형식 (기본)
jcmd <PID> Thread.dump_to_file -format=text <file>
```

-> JSON 출력 결과에서 가상 스레드를 확인하면 `"virtual": true` 필드로 식별 가능함

```json
{
  "threadCount": "77656",
  "time": "2024-01-23T08:41.0019302",
  "runtimeVersion": "21.35-2513",
  "threadContainers": [
    {
      "container": "...",
      "tid": "20",
      "stack": [
        "java.base/java.lang.VirtualThread.parkNanos(VirtualThread.java:381)",
        "java.base/java.lang.VirtualThread.sleepNanos(VirtualThread.java:38)",
        ...
      ],
      "virtual": true
    }
  ]
}
```

---

### 12. HotSpotDiagnosticMXBean으로 스레드 덤프를 생성하는 방법은?

-> `com.sun.management.HotSpotDiagnosticMXBean`의 `dumpThreads` 메서드를 사용한다.

```java
var hotspotDiagnosticBean =
    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

try {
    hotspotDiagnosticBean.dumpThreads(outputFile, HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
} catch (IOException e) {
    throw new RuntimeException("An error occurred while taking thread dump", e);
}
```

-> 출력 경로는 절대 경로(`isAbsolute()`)여야 하며, `TEXT_PLAIN` 또는 `JSON` 형식을 지정할 수 있다.

---

### 13. 스레드 덤프에서 가상 스레드 외에 어떤 시스템 스레드를 볼 수 있음?

-> 스레드 덤프에서 자주 등장하는 시스템 스레드들:

| 스레드명 | 역할 |
|---------|------|
| `Common-Cleaner` | GC 후 정리 작업 수행 |
| `Signal Dispatcher` | OS 시그널 처리 |
| `Finalizer` | finalizer 메서드 실행 |
| `Reference Handler` | 참조 객체 처리 |

-> `BLOCKED` 또는 `WAITING` 상태 스레드가 보이면 pinning이나 데드락 가능성을 체크해보자. 

-> 단, `java.lang.VirtualThread.sleepOfSeconds` 같은 메서드가 스택에 보이면 그냥 정상 대기 중인 거다.


---

## 5부. 마이그레이션 요령과 장점 정리

---

### 14. 기존 코드를 가상 스레드로 마이그레이션할 때 라이브러리가 호환되지 않는다면?

-> `newVirtualThreadPerTaskExecutor()`를 감싸는 방식으로 우회한다. 라이브러리가 내부적으로 `ExecutorService`를 받는다면, 가상 스레드 기반 executor를 주입하면 된다.

```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
```

-> `synchronized`가 포함된 외부 라이브러리는 JDK 24 이전이라면 pinning이 발생할 수 있다. 이 경우 아래와 같이 대처한다. 
1. `-Djdk.tracePinnedThreads=full`로 pinning 발생 확인
2. 해당 라이브러리의 최신 버전으로 업그레이드 (가상 스레드 대응 버전 확인)
3. 불가피하면 `ReentrantLock` 기반 래퍼로 대체

---

### 15. 가상 스레드의 핵심 장점은?

-> ① 높은 확장성: 수십만 개의 가상 스레드를 소수의 캐리어 스레드로 운용. I/O 대기 중 캐리어 스레드를 다른 작업에 재사용.

-> ② 대기 시간 감소 / 처리량 증가: I/O 바운드 작업에서 동일 CPU 자원으로 훨씬 더 많은 요청을 동시에 처리할 수 있다.

-> ③ 효율적인 자원 사용: CPU가 유휴 상태 없이 지속적으로 가상 스레드를 실행. 스레드 풀 크기 제한으로 인한 병목 문제를 없애준다.

-> ④ 기존 코드와의 호환: `Thread` API와 동일하게 사용 가능. 기존 플랫폼 스레드 코드를 `Thread.ofVirtual().start()`로 교체하는 것만으로 마이그레이션 가능.

---

### 16. 가상 스레드가 적합하지 않은 경우?

-> CPU 바운드 작업: cpu intensive한 작업은 I/O 대기가 없으므로 언마운트가 발생하지 않는다. 이 경우 가상 스레드를 많이 만들어도 캐리어 스레드(= CPU 코어 수)가 병목이 되어 이득이 없다.

-> 네이티브 메서드 집중 호출: JNI를 통해 네이티브 코드를 빈번하게 호출하면 pinning이 자주 발생하여 확장성이 저하된다.

-> 요약: 가상 스레드는 I/O 바운드, 높은 동시성 환경에서 진가를 발휘한다.

---
