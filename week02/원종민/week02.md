# 쉼 없이 CPU를 활용하는 가볍고 부지런한 가상 스레드

# 자바의 두 가지 스레드 유형

## 플랫폼 스레드

- 네이티브 스레드 / OS 스레드 / 전통적인 스레드
- 자바 스레드와 커널 스레드가 **1:1로 매핑되는 구조**
- 스케줄링과 관리를 운영체제에 의존
- POSIX 같은 OS 레벨 스레드 라이브러리에 의해 관리됨

<br/>

## 가상 스레드

- 사용자 모드 스레드 / 경량 스레드
- JDK 21부터 새로 도입
- JVM이 직접 스케줄링 및 생명주기를 관리
- 직접적으로 커널 스레드와 매핑되지 않고 다수의 가상 스레드가 적은 수의 캐리어 스레드 풀을 공유
- 캐리어 스레드 위에서 실행 됨 : 스케줄링 및 관리를 운영체제에 의존하던 전통적인 플랫폼 스레드보다 더 효율적으로 동작
    - 블로킹 허용 능력
    - 생성 비용 및 메모리 비용이 적음
    - 운영체제가 다루는 스레드 수를 줄이기 때문
- 가상 스레드 스케줄러
    - Work Stealing 방식의 ForkJoinPool을 기반으로 하지만 FIFO 모드로 동작
- 가상 스레드 스케줄러의 병렬성은 가상 스레드 스케줄링에 사용할 수 있는 플랫폼 스레드의 수를 의미하며 애플리케이션 실행시 파라미터로 설정 가능
    - default는 사용 가능한 프로세서의 수로 설정 됨
    - jdk.virtualThreadScheduler.parallelism을 사용해서 설정 가능
- 블로킹 허용 능력
    - 블로킹 연산 수행시에도 성능저하가 발생하지 않음.
    - 불필요하게 시스템 자원을 점유하지 않고 제어권을 캐리어 스레드에 넘겨서 다른 가상 스레드들이 계속 효율적으로 실행될 수 있기 때문
    
    ```java
    public class Main {
        public static void main(String[] args) throws InterruptedException {
            Thread thread1 = Thread.ofVirtual()
                    .unstarted(() -> {
                        try {
                            System.out.println("Thread 1 start");
                            Thread.sleep(2000);
                            System.out.println("Thread 1 end");
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    });
    
            Thread thread2 = Thread.ofVirtual()
                    .unstarted(() -> {
                        try {
                            System.out.println("Thread 2 start");
                            Thread.sleep(2000);
                            System.out.println("Thread 2 end");
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    });
    
            thread1.start();
            thread2.start();
            thread1.join();
            thread2.join();
    
            System.out.println("main Thread end");
        }
    }
    
    // 실행결과
    Thread 1 start
    Thread 2 start
    Thread 2 end
    Thread 1 end
    main Thread end
    
    ```
    
<br/>
<br/>
    
  #### 내부 동작 방식
  
  - 전통적인 스레드는 스택 프레임을 운영체제가 할당하는 일체형 메모리 블록에 저장: 스레드에 필요한 스택 크기를 예측해야했음
  - 가상 스레드는 스택 프레임을 가비지 컬렉션 대상이 되는 힙에 저장
  - 스택프레임: 함수가 호출될 때 메모리의 스택(Stack) 영역에 생성되는 독립적인 공간 (로컬변수, 매개변수, 반환 주소 등등) <img width="904" height="426" alt="11" src="https://github.com/user-attachments/assets/fbcdea1f-13b2-498f-9754-cd9b5b28eedf" />
  - 스레드에 필요한 스택 크기를 예측할 필요가 없어짐
      - 스택 프레임이 필요할 때만 생성하면 됨
      - 힙에 저장하기 때문에
      - 플랫폼 스레드는 중간에 스택 프레임을 늘릴 수 없기 때문에 시작할 때 크기를 결정해야 했음
  - mount: 자바 런타임은 코드를 가상 스레드에서 실행하기 위해 가상 스레드를 플랫폼 스레드에 mount함. 가상 스레드가 지금 어떤 캐리어 플랫폼 스레드 위에서 실제로 실행 중인 상태
  - unmount
      - 가상 스레드가 캐리어에서 내려오고, 실행 상태(스택 등)를 JVM이 저장한 상태
  - remount
      - 스레드를 블로킹하는 연산을 만나면 플랫폼 스레드(캐리어 스레드)로부터 unmount 됨
      - 저장해둔 가상 스레드를 다시 어떤 캐리어 스레드 위에 올려 실행 재개하는 것
      - 캐리어 스레드의 스택에 복사된 후 코드가 실행되면서 변경이 발생한 스택 프레임 내용이 힙으로 다시 복사되고 캐리어 스레드는 자유로운 몸이 되어 다른 작업을 수행할 수 있게 됨
  - continuation
      - 이 중간 상태를 저장했다가 다시 이어서 실행하게 해 주는 내부 메커니즘
  - 실제 VirtualThread에 존재하는 상태값 들
  
  ```java
  final class VirtualThread extends BaseVirtualThread {
      ...
      /*
       * Virtual thread state transitions:
       *
       *      NEW -> STARTED         // Thread.start, schedule to run
       *  STARTED -> TERMINATED      // failed to start
       *  STARTED -> RUNNING         // first run
       *  RUNNING -> TERMINATED      // done
       *
       *  RUNNING -> PARKING         // Thread parking with LockSupport.park
       *  PARKING -> PARKED          // cont.yield successful, parked indefinitely
       *  PARKING -> PINNED          // cont.yield failed, parked indefinitely on carrier
       *   PARKED -> UNPARKED        // unparked, may be scheduled to continue
       *   PINNED -> RUNNING         // unparked, continue execution on same carrier
       * UNPARKED -> RUNNING         // continue execution after park
       *
       *       RUNNING -> TIMED_PARKING   // Thread parking with LockSupport.parkNanos
       * TIMED_PARKING -> TIMED_PARKED    // cont.yield successful, timed-parked
       * TIMED_PARKING -> TIMED_PINNED    // cont.yield failed, timed-parked on carrier
       *  TIMED_PARKED -> UNPARKED        // unparked, may be scheduled to continue
       *  TIMED_PINNED -> RUNNING         // unparked, continue execution on same carrier
       *
       *  RUNNING -> YIELDING        // Thread.yield
       * YIELDING -> YIELDED         // cont.yield successful, may be scheduled to continue
       * YIELDING -> RUNNING         // cont.yield failed
       *  YIELDED -> RUNNING         // continue execution after Thread.yield
       */
      private static final int NEW      = 0;
      private static final int STARTED  = 1;
      private static final int RUNNING  = 2;     // runnable-mounted
  
      // untimed and timed parking
      private static final int PARKING       = 3;
      private static final int PARKED        = 4;     // unmounted
      private static final int PINNED        = 5;     // mounted
      private static final int TIMED_PARKING = 6;
      private static final int TIMED_PARKED  = 7;     // unmounted
      private static final int TIMED_PINNED  = 8;     // mounted
      private static final int UNPARKED      = 9;     // unmounted but runnable
  
      // Thread.yield
      private static final int YIELDING = 10;
      private static final int YIELDED  = 11;         // unmounted but runnable
  
      private static final int TERMINATED = 99;  // final state
  
      // can be suspended from scheduling when unmounted
      private static final int SUSPENDED = 1 << 8;
  ```
  <img width="945" height="750" alt="22" src="https://github.com/user-attachments/assets/39c56c78-e48c-4024-a5ce-e99f72f0a73b" />

-  가상 스레드의 처리량과 확장성은 아래의 경우에 더 향상할 수 있음
-  높은 동시 작업 수 : 수천 개의 요청을 동시에 처리해야 하는 대용량 웹 서버나, 병렬로 많은 수의 I/O 집중적인 작업을 수행하는 애플리케이션에서 가장 이상적인 효과를 볼 수 있음
-  cpu 집중적이지 않은 작업 부하 : CPU 집중적인 작업을 수행하는 시간보다 i/o작업 처럼 대기하는 시간이 많이 발생할 때 특히 유용
  - 왜 cpu 작업에는 효과가 없을까? 가상 스레드는 플랫폼 스레드 위에서 돌아가며 CPU는 플랫폼 스레드 개수만큼만 병렬로 실행이 가능하다
  - 대기하는 시간을 줄이는 것이기 때문에 작업을 계속 수행하는 cpu 집중 연산에는 맞지 않음

<br/>
<br/>

#### pinning (java 21 이슈)

- 원래는 unmount 되어야 할 상황인데 특정 이유 때문에 캐리어 스레드에 붙잡혀 못 내려오는 상태
- synchronized/native 구간에서 블로킹이 일어나면 pinning 발생 가능
    - synchronized는 JVM이 관리하는 모니터 락을 사용하는데 이 락이 OS 스레드를 사용함
    - 락은 반드시 락을 건 스레드가 락을 해제해야 하는데, 가상스레드의 경우 unmount하면 다른 OS 스레드에 붙어 실행될 수있음.
    - Thread1이 락을 걸었는데 실제로 Thread2가 락을 해제하려고 할 수 있기 때문에 해제하지 못하고 Thread1에 계속 붙어있는 것
- 긴 대기/블로킹이 있는 임계구역에서는 synchronized 대신 ReentrantLock 같은 라이브러리 사용

<br/>
<br/>

#### 요청 제한을 통한 자원 제약 관리

- 가상 스레드는 애플리케이션의 동시성 처리 효율을 증가시키는 것이다.
- 가상 스레드는 많이 생성할 수 있을지언정 웹 애플리케이션이 사용하는 DB는 그런 많은 요청을 처리하지 못할 수도 있음. 처리할 수 있는 양과 처리해도 되는 요청 수는 다른 것임
- 이 문제를 해결하려면 결국 접근하려는 자원에 특화된 요청 제한(rate-limiting) 매커니즘이 필요 함
    - 자원 관리가 가능함
    - 요청 속도를 제어하여 과부하를 방지할 수 있음
- 세마포어
    - 문지기처럼 공유 자원에 대한 접근을 통제하는 동기화 매커니즘
    - 정해진 갯수의 출입증을 관리
    - 실제 운영 시스템에서는 자원 사용량을 모니터링하고 타임아웃을 구현해서 무기한 블로킹을 막는 것이 좋음
    - acquire로 출입증을 획득하고 release로 출입증을 반납
    - 멀티 인스턴스에서 세마포어를 사용할 수 있을까?
        - 인스턴스간 공유가 되지 않으므로 사용할 수 없음
        - redis 나 큐를 구현하여 자원 관리를 해야함
- 세마포어와 rate limit 개념은 다르다
    - 보통 둘을 혼합해서 사용하기는 함
    - Semaphore = 동시에 몇 개까지 실행할지 제한 (동시성 제어)
    - RateLimiter = 일정 시간 동안 몇 개까지 허용할지 제한 (속도 제어)
    
    | 구분 | Semaphore | RateLimiter |
    | --- | --- | --- |
    | 제어 대상 | 동시 실행 수 | 시간당 요청 수 |
    | 예시 | DB connection 제한 | 외부 API 10/sec |
    | 특징 | blocking 기반 | 토큰 기반 |

##
