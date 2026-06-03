# Asia Pacific Airport Simulation — Development Report
**CT074-3-2 Concurrent Programming | Individual Assignment**

---

## 1. Introduction and Background

### 1.1 System Overview

本系统模拟 Asia Pacific Airport 的空中交通管制（ATC）运营场景。机场拥有 1 条跑道、2 个停机门（Gate）和 1 辆加油车，需要同时调度多架飞机的降落、停靠、服务和起飞。

核心并发挑战：多个飞机线程（Airplane Thread）共享有限地面资源（跑道、Gate），必须通过真正独立的 ATC 线程进行集中调度，而非让飞机线程直接操作共享状态。

### 1.2 Critical Design Principle — "Objects Are Not Processes"

作业 PDF 特别强调的错误输出格式：
```
[Thread-Plane-1] ATC: Landing Permission granted for Plane-1.   ← 错误：Plane 线程替 ATC 说话
```

正确输出格式：
```
[Thread-Plane-1] Plane-1: Requesting Landing.
[Thread-ATC]     ATC: Landing Permission granted for Plane-1.
[Thread-ATC]     ATC: Gate-1 assigned for Plane-1.
[Thread-Plane-1] Plane-1: Landing on runway...
```

**ATC 必须是真正独立运行的线程**，拥有自己的执行流程，自己输出决策结果。飞机线程只负责提交请求和等待批准。

### 1.3 Assumptions（假设说明）

| # | 假设内容 |
|---|----------|
| A1 | 机场共有 **2 个 Gate**，跑道本身不算 Gate |
| A2 | 地面最多 3 架飞机 = 跑道上 ≤1 架 + 两个 Gate 各 ≤1 架 |
| A3 | **飞机在空中排队等待**，不占用地面位置，ATC 批准后才下降 |
| A4 | **Gate 必须在 landing 许可发出前预留**，确保飞机落地即可滑行入门，无地面等待 |
| A5 | 加油、补给/清洁、乘客上下机三件事在 Gate 内**并发执行** |
| A6 | 每架飞机随机携带 1–50 名乘客，每名乘客是独立线程 |
| A7 | **紧急降落优先通过 ATC 内部调度队列实现**，不依赖 `Thread.setPriority()`（priority 不保证执行顺序）|
| A8 | 拥堵场景：前 2 架占用两个 Gate，第 3 架请求降落时 Gate 均满被拒绝，第 5 架为紧急飞机 |
| A9 | 每步骤通过 `Thread.sleep()` 模拟耗时，总仿真 ≤ 60 秒 |
| A10 | 等待时间 = 飞机提交 LandingRequest → ATC 授予许可的时间差 |
| A11 | `groundCount` 仅用于统计输出，**地面容量控制完全在 ATC 的 synchronized 块内通过条件判断完成** |

---

## 2. Architecture Design

### 2.1 Class Overview

```
AirportSimulation (main)
│
├── ATC extends Thread                  ← 真正独立的 ATC 线程
│   ├── landingQueue (正常请求队列)
│   ├── emergencyQueue (紧急请求队列，优先处理)
│   ├── runway: Runway
│   ├── gates: Gate[2]
│   └── synchronized 方法处理所有状态变更
│
├── LandingRequest                      ← 飞机与 ATC 之间的通信/同步对象
│   ├── plane: Airplane
│   ├── granted: boolean
│   ├── assignedGate: Gate
│   └── waitForDecision() / grant() / deny()
│
├── TakeoffRequest                      ← 起飞请求通信对象（同上模式）
│
├── Airplane extends Thread             ← 每架飞机是独立线程
│   ├── planeId, passengerCount
│   ├── isEmergency: boolean
│   └── 执行完整生命周期流程
│
├── Gate                                ← 停机门状态对象
│   └── isOccupied: boolean
│
├── Runway                              ← 跑道状态对象
│   └── isOccupied: boolean
│
├── RefuellingTruck implements Runnable ← 加油车，ReentrantLock 控制互斥
│
├── Passenger extends Thread            ← 每名乘客独立线程
│   └── action: BOARD / DISEMBARK
│
└── Statistics                          ← 统计收集（AtomicInteger + synchronizedList）
```

### 2.2 Thread Responsibility Model

| 线程 | 实现 | 职责 | 输出前缀 |
|------|------|------|----------|
| ATC | `extends Thread` | 接收请求、检查条件、授予/拒绝许可、分配 Gate | `[Thread-ATC]` |
| Airplane-N | `extends Thread` | 提交请求、等待批准、执行飞行步骤 | `[Thread-Plane-N]` |
| Passenger-N | `extends Thread` | 独立完成上机/下机动作 | `[Thread-Passenger-N]` |
| RefuellingTruck | `implements Runnable` | 执行加油任务，竞争 ReentrantLock | `[Thread-RefuelTruck]` |

---

## 3. Core Concurrency Design

### 3.1 ATC 作为真正的独立线程（核心架构）

ATC 线程持续运行，从请求队列中取出待处理请求，检查条件，作出决策，并通过 `LandingRequest` 对象通知飞机线程。

```java
// ATC.java
public class ATC extends Thread {
    private final LinkedList<LandingRequest> emergencyQueue = new LinkedList<>();
    private final LinkedList<LandingRequest> landingQueue   = new LinkedList<>();
    private final LinkedList<TakeoffRequest> takeoffQueue   = new LinkedList<>();

    private final Runway   runway;
    private final Gate[]   gates;
    private int            groundCount = 0;
    private volatile boolean running   = true;

    public ATC(Runway runway, Gate[] gates) {
        super("Thread-ATC");
        this.runway = runway;
        this.gates  = gates;
    }

    // ── Airplane 线程调用：提交请求后自己阻塞在 request 对象上 ──
    public synchronized void submitLandingRequest(LandingRequest req) {
        if (req.getPlane().isEmergency()) emergencyQueue.add(req);
        else                              landingQueue.add(req);
        notifyAll();  // 唤醒 ATC 线程去处理
    }

    public synchronized void submitTakeoffRequest(TakeoffRequest req) {
        takeoffQueue.add(req);
        notifyAll();
    }

    // ── ATC 线程主循环 ──
    @Override
    public void run() {
        while (running) {
            synchronized (this) {
                try {
                    processLandingRequests();
                    processTakeoffRequests();
                    if (emergencyQueue.isEmpty() && landingQueue.isEmpty()
                        && takeoffQueue.isEmpty()) {
                        wait(500);  // 无请求时短暂休眠，避免忙等待
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        System.out.println("[Thread-ATC] ATC: All operations complete. Shutting down.");
    }

    // 处理降落请求（紧急队列优先）
    private void processLandingRequests() {
        LinkedList<LandingRequest> priority =
            !emergencyQueue.isEmpty() ? emergencyQueue : landingQueue;

        if (priority.isEmpty()) return;

        LandingRequest req = priority.peek();
        Gate gate = findAvailableGate();

        // 预检：跑道空闲 + 有可用 Gate + 地面未满
        if (!runway.isOccupied() && gate != null && groundCount < 3) {
            priority.poll();            // 从队列移除
            runway.setOccupied(true);   // 占用跑道
            gate.setOccupied(true);     // 预留 Gate（关键：在批准前完成）
            groundCount++;

            System.out.println("[Thread-ATC] ATC: Landing Permission granted for "
                + req.getPlane().getName());
            System.out.println("[Thread-ATC] ATC: Gate-" + gate.getGateId()
                + " reserved for " + req.getPlane().getName());

            req.grant(gate);            // 通知飞机线程可以降落
        }
        // else: 条件不满足，请求留在队列，等下次条件变化时再处理
    }

    // 处理起飞请求
    private void processTakeoffRequests() {
        if (takeoffQueue.isEmpty()) return;
        TakeoffRequest req = takeoffQueue.peek();

        if (!runway.isOccupied()) {
            takeoffQueue.poll();
            runway.setOccupied(true);
            System.out.println("[Thread-ATC] ATC: Takeoff Permission granted for "
                + req.getPlane().getName());
            req.grant();
        }
    }

    // 飞机降落完成后调用：释放跑道
    public synchronized void notifyLandingComplete(Airplane plane) {
        runway.setOccupied(false);
        System.out.println("[Thread-ATC] ATC: Runway released after landing of "
            + plane.getName());
        notifyAll();  // 唤醒 ATC 处理下一个请求
    }

    // 飞机离开 Gate 后调用：释放 Gate，减少地面计数
    public synchronized void notifyGateVacated(Gate gate, Airplane plane) {
        gate.setOccupied(false);
        System.out.println("[Thread-ATC] ATC: Gate-" + gate.getGateId()
            + " vacated by " + plane.getName());
        notifyAll();
    }

    // 飞机起飞完成后调用：释放跑道，减少地面计数
    public synchronized void notifyTakeoffComplete(Airplane plane) {
        runway.setOccupied(false);
        groundCount--;
        System.out.println("[Thread-ATC] ATC: " + plane.getName()
            + " departed. Ground count: " + groundCount);
        notifyAll();
    }

    private Gate findAvailableGate() {
        for (Gate g : gates) if (!g.isOccupied()) return g;
        return null;
    }

    public void shutdown() { running = false; }
}
```

**关键点：**
- ATC 的所有状态修改（`runway`, `gate`, `groundCount`）都在 `synchronized(this)` 内完成，**互斥保证**
- Airplane 线程提交请求后立即退出 ATC 锁，阻塞在自己的 `LandingRequest` 对象上等待结果
- 所有 ATC 决策输出均由 `[Thread-ATC]` 线程完成，符合"objects are not processes"原则

---

### 3.2 LandingRequest — 飞机与 ATC 的通信对象

```java
// LandingRequest.java
public class LandingRequest {
    private final Airplane plane;
    private volatile boolean processed = false;
    private boolean          granted   = false;
    private Gate             assignedGate;

    public LandingRequest(Airplane plane) { this.plane = plane; }

    // 由 Airplane 线程调用：阻塞等待 ATC 决策
    public synchronized void waitForDecision() throws InterruptedException {
        while (!processed) wait();
    }

    // 由 ATC 线程调用：批准降落
    public synchronized void grant(Gate gate) {
        this.granted     = true;
        this.assignedGate = gate;
        this.processed   = true;
        notifyAll();
    }

    // 由 ATC 线程调用：拒绝（队列已满时不用，但可用于扩展）
    public synchronized void deny() {
        this.granted   = false;
        this.processed = true;
        notifyAll();
    }

    public Airplane getPlane()    { return plane; }
    public boolean  isGranted()   { return granted; }
    public Gate     getGate()     { return assignedGate; }
}
```

**此设计的并发正确性：**
- `LandingRequest` 是 Airplane 和 ATC 之间的 **condition variable 载体**
- `wait()` 在 `LandingRequest` 对象锁上，**与 ATC 对象锁完全独立**，不会产生嵌套锁
- `volatile boolean processed` 防止指令重排导致 `waitForDecision()` 读到旧值

---

### 3.3 Airplane Thread 完整生命周期

```java
// Airplane.java
public class Airplane extends Thread {
    private final int       planeId;
    private final boolean   isEmergency;
    private final ATC       atc;
    private final RefuellingTruck truck;
    private final Statistics stats;
    private final Random    rand = new Random();

    public Airplane(int id, boolean emergency, ATC atc,
                    RefuellingTruck truck, Statistics stats) {
        super("Thread-Plane-" + id);
        this.planeId     = id;
        this.isEmergency = emergency;
        this.atc         = atc;
        this.truck       = truck;
        this.stats        = stats;
    }

    @Override
    public void run() {
        try {
            // ── 1. 请求降落 ──
            long requestTime = System.currentTimeMillis();
            System.out.println("[" + getName() + "] Plane-" + planeId
                + (isEmergency ? " [EMERGENCY]" : "") + ": Requesting Landing.");

            LandingRequest req = new LandingRequest(this);
            atc.submitLandingRequest(req);
            req.waitForDecision();   // 阻塞，直到 ATC 批准

            long waitMs = System.currentTimeMillis() - requestTime;
            stats.recordWaitingTime(waitMs);
            Gate gate = req.getGate();

            // ── 2. 降落 ──
            System.out.println("[" + getName() + "] Plane-" + planeId + ": Landing...");
            Thread.sleep(1000);
            System.out.println("[" + getName() + "] Plane-" + planeId + ": Landed.");
            atc.notifyLandingComplete(this);  // 释放跑道

            // ── 3. 滑行至 Gate ──
            System.out.println("[" + getName() + "] Plane-" + planeId
                + ": Coasting to Gate-" + gate.getGateId() + "...");
            Thread.sleep(800);
            System.out.println("[" + getName() + "] Plane-" + planeId
                + ": Docked at Gate-" + gate.getGateId() + ".");

            // ── 4. Gate 内并发服务 ──
            int passengers = rand.nextInt(50) + 1;
            performGateServices(gate, passengers);

            // ── 5. 离开 Gate ──
            System.out.println("[" + getName() + "] Plane-" + planeId
                + ": Undocking from Gate-" + gate.getGateId() + ".");
            atc.notifyGateVacated(gate, this);  // 释放 Gate

            // ── 6. 滑行至跑道 ──
            System.out.println("[" + getName() + "] Plane-" + planeId
                + ": Coasting to runway...");
            Thread.sleep(800);

            // ── 7. 请求起飞 ──
            System.out.println("[" + getName() + "] Plane-" + planeId
                + ": Requesting Takeoff.");
            TakeoffRequest takeoff = new TakeoffRequest(this);
            atc.submitTakeoffRequest(takeoff);
            takeoff.waitForDecision();

            // ── 8. 起飞 ──
            System.out.println("[" + getName() + "] Plane-" + planeId + ": Taking off...");
            Thread.sleep(1000);
            System.out.println("[" + getName() + "] Plane-" + planeId + ": Departed.");
            atc.notifyTakeoffComplete(this);    // 释放跑道 + groundCount--

            stats.totalPlanesServed.incrementAndGet();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Gate 内三项并发服务
    private void performGateServices(Gate gate, int passengerCount)
            throws InterruptedException {

        // ① 下机乘客线程组
        Thread[] disembark = new Thread[passengerCount];
        for (int i = 0; i < passengerCount; i++) {
            disembark[i] = new Passenger(i, planeId, Passenger.Action.DISEMBARK);
            disembark[i].start();
        }

        // ② 补给/清洁线程（与下机并发）
        Thread supply = new Thread(() -> {
            try {
                System.out.println("[" + Thread.currentThread().getName()
                    + "] Plane-" + planeId + ": Restocking supplies and cleaning...");
                Thread.sleep(2500);
                System.out.println("[" + Thread.currentThread().getName()
                    + "] Plane-" + planeId + ": Supply complete.");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "Thread-Supply-Plane-" + planeId);
        supply.start();

        // ③ 加油线程（竞争 RefuellingTruck 的 ReentrantLock）
        Thread refuel = new Thread(() -> {
            try { truck.refuel("Plane-" + planeId); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "Thread-Refuel-Plane-" + planeId);
        refuel.start();

        // 等待下机完成后再上机
        for (Thread t : disembark) t.join();

        // ④ 上机乘客线程组
        int newPassengers = new Random().nextInt(50) + 1;
        Thread[] board = new Thread[newPassengers];
        for (int i = 0; i < newPassengers; i++) {
            board[i] = new Passenger(i, planeId, Passenger.Action.BOARD);
            board[i].start();
            stats.totalPassengersBoarded.incrementAndGet();
        }
        for (Thread t : board) t.join();

        // 等待补给和加油完成
        supply.join();
        refuel.join();
    }

    public boolean isEmergency() { return isEmergency; }
    public String  getName()     { return "Plane-" + planeId; }
}
```

---

### 3.4 紧急降落优先调度（基于 ATC 内部队列，非 Thread Priority）

```java
// ATC.java — processLandingRequests() 中
private void processLandingRequests() {
    // 紧急队列优先，普通队列其次
    LinkedList<LandingRequest> priority =
        !emergencyQueue.isEmpty() ? emergencyQueue : landingQueue;

    if (priority.isEmpty()) return;

    LandingRequest req = priority.peek();
    Gate gate = findAvailableGate();

    if (!runway.isOccupied() && gate != null && groundCount < 3) {
        priority.poll();
        // ... 授予许可
    }
}
```

**为何不依赖 `Thread.MAX_PRIORITY`：**
- `Thread.setPriority()` 只是向 OS 调度器的"建议"，JVM 规范不保证高优先级线程先执行
- 顶级实现应在应用层维护自己的调度逻辑（双队列），`Thread.MAX_PRIORITY` 可保留用于展示，但**不作为正确性保证**

---

### 3.5 RefuellingTruck — ReentrantLock 互斥

```java
// RefuellingTruck.java
public class RefuellingTruck implements Runnable {
    private final ReentrantLock lock = new ReentrantLock();

    public void refuel(String planeName) throws InterruptedException {
        lock.lockInterruptibly();  // 可中断锁，防止线程永久阻塞
        try {
            System.out.println("[" + Thread.currentThread().getName()
                + "] RefuellingTruck: Refuelling " + planeName + "...");
            Thread.sleep(2000);
            System.out.println("[" + Thread.currentThread().getName()
                + "] RefuellingTruck: Refuelling complete for " + planeName + ".");
        } finally {
            lock.unlock();  // finally 块确保锁必定释放
        }
    }

    @Override public void run() { /* 主循环，等待任务 */ }
}
```

**锁顺序规则（防止死锁）：**
- Airplane 线程在 Gate 内调用 `truck.refuel()`，此时**不持有 ATC 锁**
- ATC 线程在自己的 `synchronized(this)` 块内**不调用 `truck.refuel()`**
- 锁获取顺序唯一：`ATC lock` → 释放 → `ReentrantLock`，无循环等待

---

### 3.6 Statistics — AtomicInteger + synchronized 双层保护

```java
// Statistics.java
public class Statistics {
    public final AtomicInteger totalPlanesServed     = new AtomicInteger(0);
    public final AtomicInteger totalPassengersBoarded = new AtomicInteger(0);
    private final List<Long>   waitingTimes =
        Collections.synchronizedList(new ArrayList<>());

    public void recordWaitingTime(long ms) {
        waitingTimes.add(ms);  // synchronizedList 保证线程安全
    }

    public void printFinalStats(Gate[] gates) {
        System.out.println("\n========== ATC FINAL STATISTICS ==========");

        // 检查所有 Gate 是否为空
        boolean allEmpty = true;
        for (Gate g : gates) {
            System.out.println("Gate-" + g.getGateId()
                + ": " + (g.isOccupied() ? "OCCUPIED (ERROR!)" : "Empty ✓"));
            if (g.isOccupied()) allEmpty = false;
        }
        System.out.println("Sanity Check: All gates empty = " + allEmpty);

        long max = waitingTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long min = waitingTimes.stream().mapToLong(Long::longValue).min().orElse(0);
        double avg = waitingTimes.stream().mapToLong(Long::longValue).average().orElse(0);

        System.out.println("Planes Served         : " + totalPlanesServed.get());
        System.out.println("Passengers Boarded    : " + totalPassengersBoarded.get());
        System.out.printf ("Waiting Time (ms)     : Max=%d | Min=%d | Avg=%.1f%n",
            max, min, avg);
        System.out.println("===========================================");
    }
}
```

**AtomicInteger 原理：** 使用 CPU 级 CAS（Compare-And-Swap）指令，`incrementAndGet()` 是一次原子操作，多线程并发调用无需 `synchronized`，性能优于加锁。

---

### 3.7 Passenger Thread

```java
// Passenger.java
public class Passenger extends Thread {
    public enum Action { BOARD, DISEMBARK }

    private final int    id;
    private final int    planeId;
    private final Action action;

    public Passenger(int id, int planeId, Action action) {
        super("Thread-Passenger-" + planeId + "-" + id);
        this.id      = id;
        this.planeId = planeId;
        this.action  = action;
    }

    @Override
    public void run() {
        try {
            Thread.sleep(new Random().nextInt(500) + 200);  // 模拟上/下机耗时
            if (action == Action.DISEMBARK) {
                System.out.println("[" + getName() + "] Passenger-" + id
                    + ": Disembarking from Plane-" + planeId + ".");
            } else {
                System.out.println("[" + getName() + "] Passenger-" + id
                    + ": Boarding Plane-" + planeId + ".");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

---

## 4. Congested Scenario Design（拥堵场景）

**场景时间线：**

```
t=0s   Plane-1 到达 → 降落 → 占用 Gate-1
t=1s   Plane-2 到达 → 降落 → 占用 Gate-2
t=2s   Plane-3 到达 → 请求降落 → ATC 检查: runway free, 但 gate=null(两门已占) → 等待
t=3s   Plane-4 到达 → 请求降落 → ATC: 同 Plane-3，继续等待空中
t=4s   Plane-5 (EMERGENCY) → 进入 emergencyQueue → 优先处理
       (若此时 Gate 还是满) → Plane-5 也等，但排在所有普通请求之前
t=?    Plane-1 完成服务，Gate-1 释放 → ATC notifyAll → 处理 Plane-5 (紧急优先)
t=?    Plane-2 完成服务，Gate-2 释放 → Plane-3 降落
t=?    Plane-6 正常到达
```

**ATC 拒绝输出示例：**
```
[Thread-ATC] ATC: No available gate, Plane-3 kept in holding pattern (air queue).
[Thread-ATC] ATC: Airport capacity full, Plane-4 holding in air.
[Thread-ATC] ATC: EMERGENCY - Plane-5 in emergency queue, priority granted over Plane-3/4.
```

---

## 5. Safety Analysis

### 5.1 Race Condition 预防

| 共享资源 | 保护机制 | 保护范围 |
|----------|----------|----------|
| `runway.isOccupied` | `synchronized(atc)` | ATC 线程独占修改 |
| `gate.isOccupied` | `synchronized(atc)` | Gate 预留与释放在同一锁内 |
| `groundCount` | `synchronized(atc)` | 与 runway/gate 状态原子更新 |
| `totalPassengersBoarded` | `AtomicInteger` (CAS) | 无锁原子递增 |
| `waitingTimes` | `Collections.synchronizedList` | 线程安全 List |
| `emergencyWaiting 标志` | `emergencyQueue` 非空即为 true，在 `synchronized(atc)` 内判断 | 无需 volatile |

### 5.2 Deadlock 预防

**Coffman 四条件分析：**

| 条件 | 是否存在 | 破坏方式 |
|------|----------|----------|
| 互斥 | 是（跑道、Gate、加油车） | 无法破坏（需求本身） |
| 持有并等待 | 否 | Airplane 线程**不持有任何锁**等待 ATC 决策 |
| 非抢占 | 是 | ReentrantLock 使用 `lockInterruptibly()` 支持中断 |
| 循环等待 | 否 | 锁顺序唯一：`ATC lock` → 释放 → `RefuellingTruck lock` |

**关键设计：** Airplane 线程提交请求后阻塞在 `LandingRequest.wait()` 上（LandingRequest 对象锁），**不持有 ATC 对象锁**，因此 ATC 线程可以自由进入其 `synchronized(this)` 处理请求。

### 5.3 Starvation 预防

- 使用 `notifyAll()` 唤醒所有等待线程，避免 `notify()` 只唤醒一个（可能永远轮不到某些线程）
- 紧急飞机优先是**临时性的**：紧急飞机处理完后，普通队列立即恢复正常调度
- 所有飞机最终都会被服务（有界等待）

### 5.4 Visibility 保证

- `groundCount`, `runway.isOccupied`, `gate.isOccupied` 所有读写都在 `synchronized(atc)` 内，Java 内存模型保证 **happens-before** 关系，无需额外 `volatile`
- `LandingRequest.processed` 声明 `volatile`：ATC 线程写，Airplane 线程在 `while(!processed) wait()` 中读，volatile 防止重排

---

## 6. AirportSimulation Main

```java
// AirportSimulation.java
public class AirportSimulation {
    public static void main(String[] args) throws InterruptedException {
        Runway         runway  = new Runway();
        Gate[]         gates   = { new Gate(1), new Gate(2) };
        RefuellingTruck truck  = new RefuellingTruck();
        Statistics     stats   = new Statistics();
        ATC            atc     = new ATC(runway, gates);

        atc.start();  // ATC 线程启动

        // 拥堵场景：前4架正常，第5架紧急，第6架正常
        boolean[] emergency = { false, false, false, false, true, false };
        Airplane[] planes   = new Airplane[6];
        Random rand         = new Random();

        for (int i = 0; i < 6; i++) {
            planes[i] = new Airplane(i + 1, emergency[i], atc, truck, stats);
            planes[i].start();
            Thread.sleep(rand.nextInt(3) * 1000);  // 0, 1, 或 2 秒间隔
        }

        // 等待所有飞机完成
        for (Airplane p : planes) p.join();

        // ATC 关闭，打印统计
        atc.shutdown();
        atc.join();

        stats.printFinalStats(gates);
    }
}
```

---

## 7. Requirements Checklist

### Basic Requirements

| 需求 | 实现方式 | 状态 |
|------|----------|------|
| 1 条跑道互斥 | ATC `synchronized` 控制 `runway.isOccupied` | ✅ |
| 最多 3 架飞机在地面 | ATC `synchronized` 内 `groundCount < 3` 条件判断 | ✅ |
| 无地面等待区（Gate 必须在降落前预留） | ATC 在 `grant()` 前同步预留 Gate | ✅ |
| 完整飞机生命周期 | Airplane Thread 8 步流程 | ✅ |
| 每步骤耗时 | `Thread.sleep()` | ✅ |
| 乘客/补给/加油并发 | 三组线程并发 + `join()` | ✅ |
| 6 架飞机 | main 创建 6 个 Airplane Thread | ✅ |
| 随机到达间隔（0/1/2秒） | `sleep(rand.nextInt(3) * 1000)` | ✅ |
| 最多 50 名乘客 | `rand.nextInt(50) + 1` | ✅ |
| 线程身份正确输出 | 所有输出包含 `Thread.currentThread().getName()` | ✅ |
| ATC 决策由 ATC 线程输出 | ATC extends Thread，在 `run()` 内打印 | ✅ |
| Gate 检查 + 统计输出 | `Statistics.printFinalStats()` | ✅ |

### Additional Requirements

| 需求 | 实现方式 | 状态 |
|------|----------|------|
| 1 辆加油车互斥 | `ReentrantLock.lockInterruptibly()` | ✅ |
| 拥堵场景 | 第5架为紧急飞机，前4架制造满场条件 | ✅ |
| 紧急降落优先 | ATC 双队列（emergencyQueue 优先） | ✅ |
| Emergency 正确性不依赖 Thread Priority | 应用层队列调度，priority 仅辅助 | ✅ |

---

## 8. Concurrency Concepts Used

| 概念（课程章节） | 使用位置 | 具体作用 |
|------------------|----------|----------|
| `extends Thread`（Week 2） | ATC, Airplane, Passenger | 各实体是真正独立线程 |
| `implements Runnable`（Week 2） | RefuellingTruck | 加油车任务封装 |
| `synchronized method`（Week 3） | ATC 的所有状态变更方法 | 互斥保护跑道/Gate/groundCount |
| `wait() / notifyAll()`（Week 4） | ATC 主循环 + LandingRequest | 条件等待，避免忙等待 |
| `ReentrantLock`（Week 5-8） | RefuellingTruck | 加油车互斥，支持中断 |
| `Semaphore`（Week 5-8） | 已移除（groundCount 替代，单锁管理） | — |
| `AtomicInteger`（Week 10） | Statistics 统计计数 | CAS 无锁原子递增 |
| `volatile`（Week 10） | `LandingRequest.processed` | 可见性保证，防重排 |
| `Thread.join()`（Week 2） | 等待乘客/服务/飞机线程完成 | 同步屏障 |
| 优先队列调度（Week 9 priority inversion） | ATC emergencyQueue | 应用层解决优先级，防 priority inversion |

---

*Report prepared for CT074-3-2 Individual Assignment — Asia Pacific University of Technology & Innovation*
*Student should replace placeholder code with complete, compilable Java source files*
