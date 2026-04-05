### 1) Pinning

- **정의**: 가상 스레드가 캐리어 스레드에서 *unmount* 되지 못하고 **캐리어 스레드에 고정(pinned)** 되는 상태

#### 발생 원인

- `synchronized` 사용
    - JVM 모니터(lock)는 **OS 스레드에 종속**된다.
    - 모니터를 잡고 있는 동안에는 가상 스레드가 다른 캐리어 스레드로 이동이 불가능
        - 락 소유자가 해제해야만 진행 가능하기 때문
- **native method 호출**
    - 네이티브 코드는 JVM 영역 밖이므로 JVM이 실행을 제어하기 어렵다.
    - 네이티브 호출 스택은 자바 스택 프레임처럼 저장/복원할 수 없다.
    - OS 스레드에 직접 의존하는 구간이 생긴다.
- 외부 라이브러리의 **blocking native 호출**

#### 단점(영향)

- **자원 비효율**
    - 캐리어 스레드는 유한한 자원이다.
    - pinned 상태에서 블로킹을 만나면 캐리어 스레드가 다른 작업을 수행하지 못하고 대기한다.

#### 왜 `ReentrantLock`은 도움이 되는가?

- `ReentrantLock`은 가상 스레드를 인지할 수 있는 **파킹/언파킹(park/unpark) 메커니즘**을 사용한다.
    - (내부적으로 `LockSupport.park()` / `unpark()` 기반)
    - JVM이 스케줄링 관점에서 더 유리하게 처리할 수 있다.
- `synchronized` 대비 다음과 같은 기능을 제공한다.
    - 공정성(fairness)
    - 잠금 시도(try-lock)
    - 인터럽트 가능(interruptible)

#### 네이티브 호출 구간에 대한 실무 팁

- 가상 스레드가 네이티브 메서드를 호출하면 **호출 전체 구간 동안 캐리어 스레드에 마운트된 상태를 강제로 유지**하게 된다.
- 대응 전략
    - 네이티브 호출 빈도를 줄이도록 **일괄 처리(batch)**
    - 가능하면 **비동기 네이티브 API** 사용
    - 중요한 기능은 **순수 자바 구현**을 고려

---

### 2) ForkJoinPool vs `CommonPool`

#### Virtual Thread 스케줄러

- 가상 스레드 스케줄러는 내부적으로 **ForkJoinPool 기반**이다.
    - work-stealing + FIFO 성격을 함께 가짐
- **common pool을 그대로 사용하지 않는다.**
    - 가상 스레드 전용 스레드풀(캐리어 스레드 풀)을 별도로 생성한다.

#### 캐리어 스레드 수(병렬도) 제한

- JVM 옵션: `-Djdk.virtualThreadScheduler.parallelism`
    - 캐리어 스레드 개수를 제한한다.
    - 가상 스레드는 많이 만들 수 있지만, **동시에 실행되는 수는 캐리어 스레드 수에 의해 제한**된다.

```java
package com.wonjjong;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

public class Main {
	private static final ReentrantLock lock = new ReentrantLock();
	// private static final Object lock = new Object();

	// -Djdk.virtualThreadScheduler.parallelism=2

	public static void main(String[] args) {
		List<Thread> list = IntStream.range(0, 10000)
			.mapToObj(i -> Thread.ofVirtual().unstarted(() -> {
				System.out.println("start : " + Thread.currentThread());
				lock.lock();
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					lock.unlock();
				}

				// synchronized(lock) {
				// 	try {
				// 		Thread.sleep(5000);
				// 	} catch (InterruptedException e) {
				// 		Thread.currentThread().interrupt();
				// 	}
				// }

				System.out.println("end : " + Thread.currentThread());
			}))
			.toList();

		list.forEach(Thread::start);
		for (Thread thread : list) {
			try {
				thread.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
```

---

### 3) 가상 스레드에서 `ThreadLocal` 변수의 문제

- `ThreadLocal`은 **변수를 생성한 스레드만 읽고 쓸 수 있는 저장소**를 제공한다.
- 멀티 스레드 환경에서 동기화 비용을 줄이는 용도로 자주 사용된다.

#### 대표 사례: `SimpleDateFormat`

- `SimpleDateFormat`은 내부 상태(파싱 중간 상태, 포맷 버퍼 등)를 가지므로 **thread-safe 하지 않다.**
- 요청마다 `new SimpleDateFormat(...).format(date)`를 만들면 객체 생성 비용이 커진다.
- 그래서 전통적으로 스레드풀 환경에서는 `ThreadLocal<SimpleDateFormat>`을 써서 재사용했다.

#### 가상 스레드에서는 왜 문제가 되나?

- 가상 스레드는 스레드 수가 매우 많아질 수 있으므로, `ThreadLocal`을 그대로 사용하면 사실상 **요청별 생성**에 가까워질 수 있다.
- 대안 예시
    - `DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");`
    - `DateTimeFormatter`는 immutable 중심 설계로 thread-safe 하다.

#### ThreadLocal이 자주 담는 것(암묵적 컨텍스트)

- DB 커넥션/세션 정보
- 트랜잭션 ID, traceId(MDC) 같은 로깅 컨텍스트

#### 부작용

- 가상 스레드에서 ThreadLocal 값이 복사/상속되며 **메모리 소비 및 오버헤드**가 커질 수 있다.
- **메모리 누수 위험**
    - key는 weak reference지만 value는 strong reference
    - 반드시 remove 습관화

```
try {
	threadLocal.set(value);
} finally {
	threadLocal.remove();
}
```

- **상속(Inheritable/컨텍스트 전파)로 인한 미묘한 버그**
    - 부모 스레드의 값이 자식(가상) 스레드로 넘어가 불필요한 컨텍스트가 섞일 수 있다.

```java
MDC.put("traceId", "A");

Thread.startVirtualThread(() -> {
	log.info("비동기 작업");
});

// traceId=A 비동기 작업
```

#### 대안 방향

- 스코프드 밸류(Scoped Value)
- “정말 ThreadLocal이 필요한지” 공유/컨텍스트 설계를 재검토

---

### 4) 모니터링/디버깅

- ThreadLocal 추적: `-Djdk.traceVirtualThreadLocals`
    - 사용될 때마다 스택 트레이스를 출력해 과도한 사용/오용을 탐지
- pinned thread 추적: `-Djdk.tracePinnedThreads`
    - pinned 상태에서 블로킹 작업을 만날 때 스택 트레이스를 남김
    - `-Djdk.tracePinnedThreads=full`: 전체 스택(모니터 보유 자바 프레임 + 네이티브 프레임 강조)
    - `-Djdk.tracePinnedThreads=short`: 축약 스택

#### JFR / JMC

- 이벤트 예시
    - `jdk.VirtualThreadStart`, `jdk.VirtualThreadEnd`
    - `jdk.VirtualThreadPinned`
    - `jdk.VirtualThreadSubmitFailed`
- JDK Mission Control(JMC) 또는 사용자 지정 JFR 설정 활용

#### `jcmd` 덤프

- 가상 스레드 정보 포함 스레드 덤프 생성
    - `jcmd <PID> Thread.dump_to_file -format=text <file>`
    - `jcmd <PID> Thread.dump_to_file -format=json <file>`

#### 기타

- `HotSpotDiagnosticMXBean`

---

### 5) Parallel Stream을 지양해야 하는 이유

- `parallelStream()`은 내부적으로 **ForkJoinPool.commonPool**을 사용한다.
    - 애플리케이션 전체가 공유하는 풀이라 영향 범위 파악이 어렵다.
    - 특정 작업이 블로킹되면 다른 작업까지 함께 영향을 받을 수 있다.
    - (가상 스레드 스케줄러와도 경쟁 구도가 될 수 있음)

#### CPU 자원 독점 이슈

- `parallelStream()`은 기본적으로 **CPU-bound 작업**에 적합하다.
- 캐리어 스레드(또는 공용 풀의 워커 스레드)를 점유해 다른 작업에 영향을 줄 수 있다.

#### 권장 방향

- CPU 전용 작업은 **별도 스레드풀**로 분리해서 운용
    - 커스터마이징(크기, 정책 등)과 모니터링이 쉬워 운영/관리 측면에서 유리
