# 2. 쉼 없이 CPU를 활용하는 가볍고 부지런한 가상 스레드

## 가상스레드 고정

### 개념

가상스레드는 **캐리어 스레드(플랫폼 스레드)** 위에서 실행된다. 핵심 장점은 블로킹 발생 시 캐리어 스레드에서 **언마운트(unmount)** 되어, 캐리어 스레드가 다른 가상스레드를 실행할 수 있다는 점이다.

**고정(Pinning)** 은 이 언마운트가 일어나지 않는 상태다. 가상스레드가 캐리어 스레드에 묶여버려, 블로킹 동안 캐리어 스레드도 같이 블로킹된다.

### 고정이 발생하는 조건

1. **`synchronized` 블록/메서드 내부에서 블로킹 발생 시**
2. **네이티브 메서드(JNI) 호출 중**

### 코드 분석 (ThreadPinnedExample.java)

```java
synchronized (lock) {
    Thread.sleep(25); // 고정 발생 구간
}
```

- `synchronized` 블록 안에서 `Thread.sleep()`을 호출하면 가상스레드가 캐리어 스레드에서 언마운트되지 않음
- 캐리어 스레드가 sleep 동안 점유된 채로 블로킹됨

**i==0 출력으로 고정 확인:**

```java
if (i == 0) System.out.println(Thread.currentThread()); // before
synchronized (lock) { Thread.sleep(25); }
if (i == 0) System.out.println(Thread.currentThread()); // after
```

고정 상태에서는 before/after 출력의 **캐리어 스레드 번호가 동일**하다. 고정이 없었다면 sleep 후 다른 캐리어 스레드에서 재개될 수 있다.

### 고정의 문제점

- 가상스레드 10개가 동시에 `synchronized` 안에서 sleep
- 캐리어 스레드(CPU 코어 수만큼, 보통 8~16개)가 가상스레드에 묶힘
- 가상스레드의 확장성 이점이 사라짐

### 해결책: `ReentrantLock` 사용

```java
private static final ReentrantLock lock = new ReentrantLock();

lock.lock();
try {
    Thread.sleep(25); // 언마운트 가능 → 고정 없음
} finally {
    lock.unlock();
}
```

`ReentrantLock`은 JVM이 내부적으로 가상스레드를 언마운트할 수 있어 고정이 발생하지 않는다.

### 비교

| | `synchronized` | `ReentrantLock` |
|---|---|---|
| 블로킹 시 언마운트 | X (고정) | O |
| 캐리어 스레드 점유 | 블로킹 동안 점유 | 해제 |
| 가상스레드 확장성 | 저하 | 유지 |

### JDK 버전별 동작 차이 (JEP 491)

JDK 24부터 **JEP 491** 이 반영되어 `synchronized` 블록 내 고정 문제가 해결됐다.

**JDK 21 실행 결과 (고정 발생):**

```
VirtualThread[#21]/runnable@ForkJoinPool-1-worker-1   ← before (carrier: worker-1)
VirtualThread[#21]/runnable@ForkJoinPool-1-worker-1   ← after  (carrier: worker-1 동일)
```

sleep 중 캐리어 스레드가 고정되어 언마운트되지 않으므로 before/after 캐리어 번호가 **동일**하다.

**JDK 25 실행 결과 (고정 해제):**

```
VirtualThread[#21]/runnable@ForkJoinPool-1-worker-1   ← before (carrier: worker-1)
VirtualThread[#21]/runnable@ForkJoinPool-1-worker-3   ← after  (carrier: worker-3 다름)
```

sleep 중 가상스레드가 언마운트되었다가 남는 캐리어 스레드에서 재개되므로 before/after 캐리어 번호가 **달라질 수 있다**.

| | JDK 21 | JDK 24+ |
|---|---|---|
| `synchronized` 내 고정 | 발생 | 해결 (JEP 491) |
| sleep 중 캐리어 스레드 | 블로킹 점유 | 해제되어 다른 스레드 실행 |
| before/after 캐리어 번호 | 동일 | 달라질 수 있음 |
| 네이티브 메서드(JNI) 고정 | 발생 | **여전히 발생** |

### 파킹/언파킹 메커니즘

가상스레드가 블로킹될 때 내부적으로 일어나는 과정이다.

**파킹(Parking) — 가상스레드가 멈추는 과정**

```
가상스레드가 Thread.sleep() 또는 I/O 블로킹 호출
         ↓
JVM이 가상스레드를 "파킹" 상태로 전환
         ↓
가상스레드의 스택을 힙 메모리에 저장
         ↓
캐리어 스레드에서 언마운트 → 캐리어 스레드는 자유로워짐
         ↓
ForkJoinPool이 캐리어 스레드에 다른 가상스레드를 마운트
```

**언파킹(Unparking) — 가상스레드가 재개되는 과정**

```
sleep 시간 만료 또는 I/O 완료 이벤트 발생
         ↓
JVM이 해당 가상스레드를 "언파킹" (실행 가능 상태로 변경)
         ↓
ForkJoinPool 작업 큐에 가상스레드 등록
         ↓
여유 캐리어 스레드가 가상스레드를 마운트
         ↓
힙에 저장했던 스택을 복원 → 중단된 지점부터 실행 재개
```

**핵심 포인트**

- 가상스레드의 스택은 **힙**에 저장되기 때문에 수십만 개도 생성 가능
- 캐리어 스레드(플랫폼 스레드)는 항상 바쁘게 유지됨 → CPU 낭비 없음
- **고정(Pinning)** 은 이 파킹/언파킹 과정이 막혀서 캐리어 스레드가 같이 멈춰버리는 것

## 가상 스레드에서 ThreadLocal 변수의 문제

### ThreadLocal이란?

스레드마다 독립적인 변수 복사본을 갖게 해주는 클래스다.
변수를 생성한 스레드만 읽고 쓸 수 있어서 **동기화 없이 스레드 안전하게 데이터를 다룰 수 있다.**

```java
ThreadLocal<String> ctx = new ThreadLocal<>();
ctx.set("user-123");     // 현재 스레드만의 값
ctx.get();               // 다른 스레드에서 호출하면 null
```

**장점:**
- **자원 격리**: DB 커넥션, 트랜잭션 컨텍스트를 스레드별로 독립 유지
- **암묵적 컨텍스트 전달**: 파라미터 없이 요청 유저 정보, 로그 추적 ID 등을 전달

---

### 가상스레드에서 왜 문제가 되나?

플랫폼 스레드는 보통 수백~수천 개라 ThreadLocal을 많이 써도 감당됐다.
하지만 가상스레드는 **수십만 개**까지 생성되므로 문제가 달라진다.

**메모리 폭발:**
```
플랫폼 스레드  1,000개 × ThreadLocal 10개 =     10,000개 복사본
가상 스레드  100,000개 × ThreadLocal 10개 = 1,000,000개 복사본
```

**오버헤드:**
가상스레드는 파킹/언파킹 시 스택을 힙에 저장하는데, ThreadLocal에 큰 객체가 있으면 이 과정의 비용이 커진다.

**상속(InheritableThreadLocal) 문제:**
자식 스레드가 부모 값을 복사 상속하는데, 가상스레드를 매번 새로 만드는 패턴에서는 이 복사가 불필요하게 반복된다.

```java
InheritableThreadLocal<String> ctx = new InheritableThreadLocal<>();
ctx.set("parent-value");

// 가상스레드 생성마다 "parent-value" 복사 → 수십만 번 반복되면 오버헤드
Thread.ofVirtual().start(() -> ctx.get());
Thread.ofVirtual().start(() -> ctx.get());
```

---

### 해결책

**1. ScopedValue 사용 (JDK 23 확정)**

ThreadLocal 대신 불변 컨텍스트를 명시적 스코프 안에서만 공유한다.

```java
ScopedValue<String> USER = ScopedValue.newInstance();

ScopedValue.where(USER, "user-123").run(() -> {
    System.out.println(USER.get()); // "user-123"
    // 블록 종료 시 자동 해제 → 메모리 누수 없음
});
```

| | `ThreadLocal` | `ScopedValue` |
|---|---|---|
| 값 변경 | 가능 | 불가 (불변) |
| 스코프 | 스레드 전체 생애 | 명시적 블록 내 |
| 메모리 해제 | 직접 `remove()` 필요 | 블록 종료 시 자동 |
| 가상스레드 적합성 | 낮음 | 높음 |

**2. 공유에 대한 재검토**

ThreadLocal로 암묵적으로 전달하던 값을 **메서드 파라미터로 명시적으로 전달**하는 방식으로 전환한다.
코드 흐름이 명확해지고 가상스레드 수에 무관하게 메모리가 일정하게 유지된다.

## 가상 스레드 고정현상 모니터링

고정이 실제로 발생하는지 확인하는 방법은 크게 세 가지다.

---

### 방법 1. JVM 플래그 (`-Djdk.tracePinnedThreads`)

가장 간단한 방법이다. JVM 시작 옵션에 플래그를 추가하면 고정 이벤트 발생 시 자동으로 스택 트레이스를 출력한다.

```bash
# 핀닝을 유발한 핵심 프레임만 출력
java -Djdk.tracePinnedThreads=short -cp out week03.서동휘.ThreadPinnedExample

# 전체 스택 트레이스 출력
java -Djdk.tracePinnedThreads=full  -cp out week03.서동휘.ThreadPinnedExample
```

**출력 예시 (JDK 21):**

```
Thread[#21,ForkJoinPool-1-worker-1,5,CarrierThreads]
    week03.서동휘.ThreadPinnedExample.lambda$main$0(ThreadPinnedExample.java:19) <== monitors:1
```

- `monitors:1` — synchronized 블록 안에서 고정이 발생했음을 나타낸다
- 코드 변경 없이 플래그만으로 감지 가능하지만, 로그 파일로 수집하거나 후처리하기 어렵다

---

### 방법 2. JFR (Java Flight Recorder) — `PinningJFRMonitor.java`

`RecordingStream`으로 `jdk.VirtualThreadPinned` 이벤트를 실시간 수신한다.
JVM 플래그 없이도 코드 안에서 핀닝을 감지하고 카운트·알림 등 후처리가 가능하다.

```java
try (var rs = new RecordingStream()) {
    // 1ms 이상 지속된 핀닝 이벤트만 감지
    rs.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(1));

    rs.onEvent("jdk.VirtualThreadPinned", event -> {
        System.out.printf("[핀닝 감지] 지속시간: %dms  스레드: %s%n",
                event.getDuration().toMillis(),
                event.getThread().getJavaName());
    });

    rs.startAsync(); // 백그라운드 이벤트 수집 시작

    runWithPinning(); // 핀닝 유발 코드 실행

    Thread.sleep(500); // 미수신 이벤트 플러시 대기
}
```

**출력 예시 (JDK 21):**

```
=== synchronized 블록 내 sleep → 핀닝 발생 ===
[핀닝 감지 #1] 지속시간: 51ms  스레드: virtual-1
[핀닝 감지 #2] 지속시간: 52ms  스레드: virtual-2
...
총 감지된 핀닝 횟수: 10
```

| 항목 | JVM 플래그 | JFR |
|---|---|---|
| 설정 위치 | JVM 실행 인자 | 코드 내부 |
| 후처리 | 불가 (로그 출력만) | 가능 (카운트, 알림 등) |
| 임계값 설정 | 불가 | `withThreshold()` 로 가능 |
| 프로덕션 적용 | 출력 과다 위험 | 부하 낮고 세밀한 제어 가능 |

---

### 방법 3. 처리량 비교 — `PinningThroughputCompare.java`

각 가상 스레드에 **독립적인 락**을 할당해 락 경합 없이 순수한 핀닝 효과(캐리어 스레드 고갈)만 측정한다.

```
synchronized (JDK 21, 핀닝 발생):
  sleep 중 캐리어 스레드를 점유 → 동시 실행 가능 수 = 캐리어 수(≈ CPU 코어)
  총 소요 시간 ≈ (스레드 수 / 캐리어 수) × sleep 시간

ReentrantLock (핀닝 없음):
  sleep 중 캐리어 스레드 해방 → 모든 스레드가 동시에 sleep, 동시에 재개
  총 소요 시간 ≈ sleep 시간
```

**출력 예시 (JDK 21, 100 스레드, 20ms sleep, 8코어):**

```
가상 스레드: 100개 | sleep: 20ms | 캐리어 스레드(약): 8개

=== 본 측정 ===
  [synchronized (핀닝 발생)    ] 260ms
  [ReentrantLock (핀닝 없음)   ]  22ms

[결과 요약]
  synchronized : 260ms
  ReentrantLock:  22ms
  차이          : 11.8x
```

핀닝이 발생하면 캐리어 스레드가 sleep 중에도 점유되어 가상 스레드의 확장성 이점이 사라진다.
JDK 24+(JEP 491)에서는 `synchronized`도 핀닝이 해결되어 두 결과가 비슷해진다.

---

### 실무에서 많이 쓰는 GUI 모니터링 도구

#### JDK Mission Control (JMC) — 무료, Oracle 공식

JFR 녹화 파일(`.jfr`)을 GUI로 분석하는 공식 도구다.

```bash
# JFR 녹화 후 jfr 파일 생성
java -XX:StartFlightRecording=filename=recording.jfr,duration=60s \
     -cp out week03.서동휘.ThreadPinnedExample
```

JMC에서 `.jfr` 파일을 열면 **이벤트 브라우저 → `jdk.VirtualThreadPinned`** 항목에서 핀닝 발생 시점, 지속시간, 스택 트레이스를 시각적으로 확인할 수 있다.

#### IntelliJ IDEA Profiler — 개발 시 압도적으로 편함

IntelliJ 2023.2+에 내장된 프로파일러. 별도 설치 없이 IDE에서 바로 실행된다.

- **Run → Profile** 으로 실행
- 결과 화면에서 **Virtual Threads** 탭 확인
- JFR 기반이라 `VirtualThreadPinned` 이벤트를 그대로 시각화
- 개발 단계에서 실무자가 가장 많이 사용 (대부분의 Java 개발자가 IntelliJ 사용)

#### async-profiler — 프로덕션 플레임 그래프
(참고 : https://mangkyu.tistory.com/336)

프로덕션 서버에 직접 붙여 HTML 플레임 그래프로 출력한다. JVM 재시작 없이 실행 중인 프로세스에 attach 가능하다.

```bash
./asprof -d 30 -f output.html <pid>
```

---

### 도구 선택 기준

| 상황 | 도구 |
|---|---|
| 개발·디버깅 | **IntelliJ IDEA Profiler** (가장 편함, 별도 설치 불필요) |
| JFR 심층 분석 | **JDK Mission Control** (이벤트·타임라인 상세 분석) |
| 프로덕션 서버 | **async-profiler** (무중단 프로파일링, 플레임 그래프) |
| 엔터프라이즈 APM | Datadog / Dynatrace (JFR 기반 가상스레드 지표 자동 수집) |

현재(JDK 21 기준 가상스레드 도입 초기)에는 **IntelliJ Profiler + JMC** 조합이 가장 범용적이다. 별도 설정 없이 기존 JFR 인프라를 그대로 활용하기 때문이다.


## 이번 장 회고

### 가상스레드 도입 시 가장 중요한 것

가상스레드는 도입 자체보다 **도입 후 고정 여부를 확인하는 것**이 핵심이다.
단순히 `Thread.ofVirtual()`로 바꾼다고 끝이 아니라, 아래 세 가지를 반드시 점검해야 한다.

**1. 내 코드의 `synchronized` 점검**

`synchronized` 블록 안에서 블로킹 작업이 일어나면 캐리어 스레드가 고정되어 가상스레드의 확장성 이점이 사라진다.
`ReentrantLock`으로 교체하는 것이 JDK 21 기준 기본 원칙이다. (JDK 24+는 JEP 491로 해결)

**2. 의존 라이브러리 확인 (가장 놓치기 쉬운 포인트)**

내 코드를 고쳐도 **라이브러리 내부가 `synchronized`를 쓰면 고정은 똑같이 발생한다.**
JDBC 드라이버, Hibernate, Spring Security 등 구버전 라이브러리는 내부적으로 `synchronized`를 사용하는 경우가 많다.
가상스레드 도입 후 성능이 예상보다 안 나온다면 라이브러리 내부 고정을 먼저 의심해야 한다.

**3. `ThreadLocal` 남용 재검토**

플랫폼 스레드 기반 코드를 그대로 가상스레드로 전환하면 `ThreadLocal`이 수십만 개 복사되어 메모리가 폭발할 수 있다.
고정처럼 즉각적인 오류가 나지 않아서 더 찾기 어렵다. `ScopedValue`로 전환하거나 파라미터로 명시적으로 전달하는 방식을 검토해야 한다.

---

### 가상스레드 도입 체크리스트

```
□ 내 코드의 synchronized 블록 안에서 블로킹 작업 여부 확인 → ReentrantLock 전환
□ 의존 라이브러리의 가상스레드 최적화 버전 여부 확인
□ ThreadLocal 과다 사용 여부 검토 → ScopedValue 또는 파라미터 전달 방식 고려
□ 도입 전후 IntelliJ Profiler 또는 JFR로 jdk.VirtualThreadPinned 이벤트 모니터링
```