# Virtual Threads, Pinning & Locking — Defence Q&A

A focused reference for defending the concurrency choices in `PgmqWorker.java`.
Covers: `Thread.sleep` semantics on virtual threads, pgjdbc's `ResourceLock`,
`synchronized` vs `ReentrantLock`, the pinning issue, blocking semantics, and
the Java 21 → 25 upgrade question.

> **Code under discussion:** `src/main/java/com/example/peg/delivery/PgmqWorker.java`
> The worker runs its poll loop on a virtual thread, fans out per-message
> handlers across more virtual threads, and bounds concurrency with a
> `Semaphore` sized to the Hikari pool.

---

## 1. Is `Thread.sleep` a problem on virtual threads?

**No.** `Thread.sleep` is one of the JDK methods that was specifically
retrofitted to be virtual-thread-friendly.

When `Thread.sleep(ms)` is called from a virtual thread (e.g. line 135 in
`PgmqWorker.runLoop`), the VT **unmounts** from its carrier platform thread.
The carrier is freed to run other VTs during the sleep. No platform thread
is blocked.

The same applies to other primitives used in `PgmqWorker`:

| Call site | Primitive | VT behaviour |
|---|---|---|
| `Semaphore.acquire()` (line 185) | `LockSupport.park` | Unmounts cleanly |
| `CompletableFuture.join()` (line 164) | `LockSupport.park` | Unmounts cleanly |
| `awaitTermination()` (line 95) | `LockSupport.park` | Unmounts cleanly |
| `Thread.sleep(...)` (line 135) | Park with deadline | Unmounts cleanly |

**What would actually pin a carrier:**

1. `synchronized` blocks holding a monitor across a blocking call
   (fixed in JEP 491 / Java 24, still pins on Java 21–23).
2. JNI / native calls that block.
3. `Object.wait` inside `synchronized` (same root cause as #1).

The relevant risk in `PgmqWorker` was never `Thread.sleep` — it was the
JDBC layer.

---

## 2. Is the pgjdbc driver virtual-thread-safe?

**Yes — modern pgjdbc uses `ResourceLock`, not `synchronized`.**

Inspecting `PgPreparedStatement.executeQuery()`:

```java
public ResultSet executeQuery() throws SQLException {
    ResourceLock ignore = this.lock.obtain();   // ReentrantLock under the hood
    ResultSet var2;
    try {
        if (!this.executeWithFlags(0)) { ... }
        var2 = this.getSingleResultSet();
    } catch (Throwable var5) {
        if (ignore != null) ignore.close();
        throw var5;
    }
    if (ignore != null) ignore.close();
    return var2;
}
```

`ResourceLock` is a try-with-resources wrapper around `ReentrantLock`,
introduced around pgjdbc 42.7.2 specifically to remove the old
`synchronized` blocks.

**Mechanics:**

- `this.lock.obtain()` → `ReentrantLock.lock()` → `LockSupport.park()` if contended.
- `LockSupport.park()` unmounts the virtual thread from its carrier.
- The carrier is reused for other VTs while the SQL is in flight on the wire.

**Pre-2024 driver, for contrast:**

```java
public synchronized ResultSet executeQuery() { ... }
```

That `synchronized` would pin the carrier on Java 21–23 — every JDBC call
effectively burned a platform thread for the query duration.

**Implication for `PgmqWorker`:**

- `jdbc.query(...)` (line 199) → `executeQuery()` → `ResourceLock` → no pin.
- Network read of the result set → `Socket.getInputStream().read()` → unmounts cleanly.
- Full path is virtual-thread-clean.

The `concurrencyLimiter` semaphore is doing its real job: bounding
**Hikari connection demand**, not protecting carriers. With `concurrency`
virtual threads each potentially holding a connection, you don't want that
count to exceed the pool size, or you'd serialize on `getConnection()`.

> **Also worth checking:** HikariCP itself used `synchronized` on older
> versions. Make sure you're on **HikariCP 5.1.0+** (which switched to
> `ReentrantLock`).

---

## 3. `synchronized` vs `ReentrantLock`

| Aspect | `synchronized` | `ReentrantLock` |
|---|---|---|
| Layer | JVM monitor (`monitorenter`/`monitorexit` bytecode) | `java.util.concurrent.locks` class |
| Acquire / release | Implicit (block scope or method modifier) | Explicit `lock()` / `unlock()` (try/finally) |
| Interruptible wait | No | Yes (`lockInterruptibly()`) |
| Timeout | No | Yes (`tryLock(timeout)`) |
| Try-acquire | No | Yes (`tryLock()`) |
| Fairness | No | Optional (FIFO via constructor) |
| Condition variables | One implicit (`wait`/`notify`) | Many (`newCondition()`) |
| Pre-Java 24 VT behaviour | **Pins** the carrier | **Does not pin** (uses `LockSupport.park`) |

**When to pick which:**

- `synchronized` — simple, short critical sections; on Java 24+ the
  difference largely disappears.
- `ReentrantLock` — when you need timeout, interruptibility, multiple
  conditions, fairness, or VT-friendliness on Java 21–23.

**Why pgjdbc swapped to `ReentrantLock`:** their critical sections wrap
**network I/O**. Pinning a carrier for the duration of a query on Java
21–23 would defeat the whole point of using virtual threads for DB-bound
workloads — exactly the `PgmqWorker` scenario.

---

## 4. What is virtual-thread pinning?

### The model

Virtual threads (VTs) run on a small pool of **carrier** platform threads
(default: number of CPU cores). When a VT hits a blocking operation, it
normally **unmounts** from its carrier — the VT is parked, the carrier is
freed to run another VT. This is what makes VTs cheap.

### Pinning

Certain blocking operations prevent the unmount. The VT stays glued
("pinned") to its carrier, and the carrier sits idle waiting. A platform
thread is wasted.

### Causes (Java 21–23)

1. **`synchronized` blocks** — if the VT blocks *inside* a `synchronized`
   region (network read, `wait()`, lock acquire), the carrier is pinned.
2. **Native frames (JNI)** — blocking inside native code pins.
3. **`Object.wait()` inside `synchronized`** — same root cause as #1.

### Why it matters

With only ~N carriers (N = CPU cores), if N virtual threads pin
simultaneously, **every other virtual thread stalls** — even ones doing
non-blocking work. The "millions of cheap threads" abstraction collapses
into a tiny thread pool.

Symptoms: throughput drops, tail latency spikes, threads queue up. Hard
to spot without:
- `-Djdk.tracePinnedThreads=full` startup flag, or
- JFR's `jdk.VirtualThreadPinned` event.

### What's been fixed

- **JEP 491 (Java 24)** — `synchronized` no longer pins. Monitors are
  tracked off-heap and the VT can unmount.
- Major libraries (pgjdbc 42.7.2+, HikariCP 5.1.0+, Netty, etc.)
  replaced `synchronized` with `ReentrantLock` so users on 21–23 don't pin.

### TL;DR

Pinning = a virtual thread can't get off its carrier, so the carrier is
wasted. On Java 21–23, watch for `synchronized` around blocking I/O.
On Java 24+, mostly a non-issue.

---

## 5. Is `ReentrantLock` non-blocking?

**No — `ReentrantLock` is blocking.** If the lock is held, `lock()` makes
the calling thread wait.

The confusion is usually between two different meanings of "non-blocking":

| Term | Meaning | Example |
|---|---|---|
| **Lock-free / non-blocking** | No thread ever waits; uses CAS | `AtomicInteger`, `ConcurrentHashMap` ops |
| **Blocking but VT-friendly** | Thread waits, but carrier is freed | `ReentrantLock`, `Semaphore`, `Thread.sleep` |

`ReentrantLock` is the second category. The thread genuinely blocks — but
it blocks via `LockSupport.park()`, which **unmounts** a virtual thread
from its carrier. The platform thread is reused; the VT just sits parked
until signaled.

### Quick reference

```java
lock.lock();                    // BLOCKS if contended (but VT unmounts)
lock.tryLock();                 // NON-BLOCKING — returns true/false immediately
lock.tryLock(1, SECONDS);       // BLOCKS up to 1s, then gives up
lock.lockInterruptibly();       // BLOCKS, but throws on Thread.interrupt()
```

Only `tryLock()` (no timeout) is truly non-blocking.

### Why people say "VT-friendly" instead of "non-blocking"

- The **carrier thread** isn't blocked — it goes off to run other VTs.
- The **virtual thread** *is* blocked — it's waiting for the lock.

From application logic, behaviour is identical to `synchronized`: your
code pauses until the lock is acquired. The difference is invisible to
your logic but huge for scalability.

---

## 6. Should the project upgrade Java 21 → 24?

**Short answer:** skip 24, consider **Java 25 LTS** — but no rush.

### Why not 24

Java 24 is a 6-month **non-LTS** release. Production systems on Java 21
LTS shouldn't switch to a non-LTS unless there's a feature critically
needed. Support ended September 2025.

### Java 25 LTS is the real target

Released September 2025, supported through ~2033. It includes everything
from 22/23/24, notably:

- **JEP 491** — `synchronized` no longer pins virtual threads.
- **JEP 483** — Ahead-of-Time class loading (faster startup).
- Compact object headers, generational ZGC default, etc.

### Should *this* codebase upgrade?

For `PgmqWorker` specifically — **no urgency**. Pinning exposure is
already low:

- pgjdbc uses `ResourceLock`.
- Modern Hikari (5.1.0+) uses `ReentrantLock`.
- Worker code uses `Semaphore`, not `synchronized`.

The marginal benefit of JEP 491 is small here. Upgrading is more about
general posture — newer LTS, better GC defaults, longer support runway —
than fixing a real bug.

### Decision framework

- **Upgrade now to 25** if: free capacity to test, want longer LTS
  support, or *other* libraries (older Netty, custom code, legacy
  drivers) still use `synchronized` around I/O.
- **Stay on 21** if: stable, no specific 22–25 feature needed, team
  time better spent elsewhere. Java 21 is supported until 2031.

### If upgrading

Run `-Djdk.tracePinnedThreads=full` on Java 21 first to baseline whether
real production pinning exists. If the log is empty, the upgrade is
"nice to have," not "must have."

---

## Defence one-liners

If asked about any of this in 30 seconds:

| Question | Answer |
|---|---|
| Why is `Thread.sleep` safe in your VT poll loop? | "Sleep unmounts the VT from its carrier — no platform thread blocked." |
| Why isn't pgjdbc pinning your carriers? | "42.7.2+ replaced `synchronized` with `ResourceLock` (a `ReentrantLock` wrapper). Park, not pin." |
| Why a `Semaphore` if VTs are cheap? | "VTs are cheap, DB connections aren't. The semaphore caps concurrent connection demand to the Hikari pool size." |
| `synchronized` or `ReentrantLock`? | "Lock for I/O-adjacent critical sections on Java 21–23 — avoids pinning. `synchronized` is fine for short, in-memory sections." |
| What is pinning? | "A VT stuck on its carrier during a blocking call inside `synchronized`. Wastes a platform thread; defeats the VT model." |
| Upgrade to Java 24? | "No — non-LTS. Java 25 LTS is the target if we move; otherwise 21 is fine through 2031." |
