# 2장. 가상 스레드 (하)

## 고정(Pinning)

가상 스레드가 캐리어 스레드에 묶여 언마운트할 수 없게 되는 상태. 블로킹 연산이 생겨도 캐리어를 반납 못하고 독점하게 됨 → 처리량 감소, 자원 비효율, 확장성 저하.

발생 시나리오는 두 가지다. 첫째는 `synchronized` 블록/메서드 진입 시, 둘째는 네이티브 메서드/외부 함수 호출 시. 네이티브 코드는 JVM이 제어할 수 없어서 스택 프레임 저장/복원이 불가능하기 때문에 고정이 불가피하다.

완화 전략은 `synchronized` 대신 `ReentrantLock`을 쓰거나, 코드 리뷰로 고정 유발 코드를 찾아 최소화하는 것이다.

---

## ReentrantLock

`synchronized`는 객체 모니터를 사용해 JVM이 파킹을 감지할 수 없다. 반면 `ReentrantLock`은 park/unpark 메커니즘을 사용해 JVM이 파킹을 감지하면 언마운트할 수 있다.

```java
// ❌ 고정 발생
synchronized (lock) { Thread.sleep(25); }

// ✅ 고정 없음 — 블로킹 시 언마운트 후 캐리어가 다른 가상 스레드 실행
lock.lock();
try {
    Thread.sleep(25);
} finally {
    lock.unlock(); // 반드시 finally
}
```

블로킹 시 JVM이 하는 일: 상태 저장 → 언마운트 → 다른 가상 스레드 마운트 → 락 반납 후 재마운트.

---

## synchronized 위험도는 코드에 따라 다름

고정 위험의 심각성은 블록 안에서 수행하는 작업의 성격에 달려 있다.

```java
// 위험 낮음 - 블로킹 없이 금방 끝남
synchronized (this) { return this.a + this.b; }

// 위험 높음 - 네트워크 블로킹 동안 캐리어 독점
synchronized (this) { return httpClient.sendBlockingRequest(request); }

// 위험 높음 - notify() 올 때까지 무한 대기
synchronized (this) { this.wait(); }
```

---

## JEP 491 (JDK 24)

`synchronized`를 가상 스레드 친화적으로 개선. 가상 스레드가 스스로 락을 획득/유지/해제할 수 있게 됨 → 블로킹 시 언마운트 허용. 단, 클래스 초기화 중 블로킹이나 심볼릭 참조 해석 시에는 여전히 고정이 발생한다.

LTS는 아직 JDK 21이므로 고정 문제를 계속 인지하고 설계해야 한다.

---

## ThreadLocal 문제

가상 스레드는 수백만 개까지 만들 수 있는데, 각각이 ThreadLocal 복사본을 가지면 메모리가 폭발한다. 초기화/정리 오버헤드도 수백만 번 반복되고, 부모 스레드 값을 상속받아 디버깅하기 어려운 버그도 생긴다.

실측 (500KB LargeObject × 1,000 가상 스레드): ThreadLocal 사용 시 538MB, 미사용 시 34MB — 15배 이상 차이.

대안은 **스코프드 밸류(Scoped Value)**다. 가상 스레드를 염두에 두고 설계된 대안으로, 불변성과 제한된 수명 덕분에 ThreadLocal보다 안전하고 효율적이다 (5장).

---

## 모니터링

ThreadLocal 사용 위치를 추적하려면 `-Djdk.traceVirtualThreadLocals` 플래그로 JVM을 시작하면 된다. 가상 스레드 안에서 ThreadLocal이 사용될 때마다 스택 트레이스를 출력해준다.

고정 현상은 `-Djdk.tracePinnedThreads=full`로 전체 스택 트레이스를, `=short`로 문제 프레임만 볼 수 있다. 출력에서 `reason:MONITOR`는 락 획득으로 고정됐다는 뜻이고, `<== monitors:1`이 고정 위치다.

JFR을 쓰면 더 정밀하게 볼 수 있다. `jdk.VirtualThreadPinned`는 기본 활성화되어 있고 20ms 이상 고정 시 기록된다. `jdk.VirtualThreadSubmitFailed`는 자원 고갈/병목 신호다. 커스텀 `.jfc` 파일로 원하는 이벤트만 선택적으로 추적할 수도 있고, GUI로 보려면 JDK Mission Control을 쓰면 된다.

스레드 덤프는 `jcmd <PID> Thread.dump_to_file -format=json dump.json`으로 생성. 가상 스레드도 포함되며, 객체 주소나 JNI 통계 같은 잡다한 정보는 빠져 있어서 오히려 핵심만 보기 편하다. 코드 내부에서 생성하려면 `ProcessBuilder`로 jcmd를 호출하거나 `HotSpotDiagnosticMXBean`을 쓰면 된다 (원격 진단도 가능).

---

## 세마포어로 자원 제한

가상 스레드는 사실상 무제한 동시성이라 DB나 외부 API 같은 백엔드에 과부하를 줄 수 있다. 플랫폼 스레드 시절에는 스레드 풀 크기가 자동으로 제한 역할을 했지만, 가상 스레드는 직접 세마포어로 제한해야 한다.

```java
private final Semaphore semaphore = new Semaphore(10);

semaphore.acquire();
try {
    queryDatabase(query);
} finally {
    semaphore.release(); // 반드시 finally
}
```

`new Semaphore(n, true)`로 FIFO 공정성을 보장할 수 있지만 성능이 약간 저하된다.

---

## 구조적 동시성

`Future::get`은 예외/타임아웃 처리가 불완전하다. subtask 하나가 실패해도 나머지가 계속 실행되어 자원이 낭비된다. 구조적 동시성은 이를 해결해 한 subtask가 실패하면 나머지를 자동 취소하고, 예외 전파와 생명주기 관리를 언어 구조 수준에서 처리한다.

---

## 마이그레이션 요령

고정 문제를 피하는 가장 좋은 방법은 라이브러리를 가상 스레드용으로 업데이트하는 것이다. 업데이트가 어렵다면 블로킹 코드를 `Executors.newFixedThreadPool()`로 격리해 가상 스레드의 성능에 영향을 주지 않도록 분리한다. 세마포어로 고정 구간 진입을 제한할 수도 있지만, 너무 낮게 설정하면 동시성 장점이 사라지므로 주의해야 한다.

마이그레이션 후에는 CPU 사용 패턴, 메모리/GC, 응답 지연과 처리량 지표를 반드시 모니터링해야 한다. 가상 스레드의 핵심 가치는 빠름이 아니라 **확장성**이므로, 같은 하드웨어로 더 많은 동시 요청을 처리할 수 있게 됐는지가 핵심 판단 기준이다.

## 실무적용 관점

io블로킹이 잦은 곳에 사용
전환하면 가장 효과 큰 곳

sauce-admin-be - PartnerUpdateService.java:41,
PartnerCreateService.java:44
// 현재
private static final ExecutorService executor =
Executors.newFixedThreadPool(20);
// + CompletableFuture.allOf(...).join() 으로 DynamoDB 병렬 업데이트
→ DynamoDB I/O 대기 + 고정 스레드풀 조합이라 가상 스레드로 바꾸면 가장
체감 효과 큼
