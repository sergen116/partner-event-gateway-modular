# Virtual Threads, Pinning, Locking — Summary

Defends concurrency choices in `PgmqWorker.java`.

## `Thread.sleep` on virtual threads — safe
Sleep on a VT **unmounts** from carrier. Same for `Semaphore.acquire`, `CompletableFuture.join`, `awaitTermination`, `Thread.sleep` — all park-with-deadline, all unmount cleanly. **What pins (Java 21–23)**: `synchronized` blocks holding monitor across blocking call, JNI, `Object.wait` inside `synchronized`.

## Is pgjdbc VT-safe? Yes — since 42.7.2
Old pgjdbc used `synchronized` on JDBC entry points → pinned every query. Modern pgjdbc uses **`ResourceLock`** (a try-with-resources `ReentrantLock` wrapper) → `LockSupport.park` → unmounts cleanly.
- **Also check HikariCP 5.1.0+** — it also moved off `synchronized`.
- **Why the `Semaphore` in PgmqWorker**: caps DB-connection demand to Hikari pool size (VTs are cheap, connections aren't). NOT for protecting carriers.

## `synchronized` vs `ReentrantLock`
| Aspect | `synchronized` | `ReentrantLock` |
|---|---|---|
| Layer | JVM monitor bytecode | `java.util.concurrent.locks` |
| Acquire | Implicit (block scope) | Explicit `lock()`/`unlock()` |
| Interruptible / timeout / try-acquire | No | Yes |
| Multiple conditions | One implicit | Many via `newCondition()` |
| Pre-Java 24 VT | **Pins** carrier | **Does NOT pin** (uses `LockSupport.park`) |

**Rule**: lock around I/O critical sections on Java 21–23; `synchronized` ok for short, in-memory sections. pgjdbc swapped because their critical sections wrap network I/O — pinning would defeat the whole VT model for DB-bound workloads.

## Pinning — the core problem
- VTs run on small carrier pool (default = CPU cores).
- Blocking ops normally **unmount** VT, freeing carrier.
- Some blocking (e.g. `synchronized` + I/O) prevent unmount → VT pinned to carrier → carrier wasted.
- With N pins simultaneously (N = carriers), **every VT stalls** — even non-blocking ones. The "millions of cheap threads" abstraction collapses.
- **Detect**: `-Djdk.tracePinnedThreads=full` flag, or JFR `jdk.VirtualThreadPinned` event.
- **Fixed by JEP 491 (Java 24)**: monitors tracked off-heap, `synchronized` no longer pins.

## Is `ReentrantLock` non-blocking?
**No — it blocks the VT.** But via `LockSupport.park` → carrier is freed.
- "Lock-free / non-blocking" = no thread ever waits, uses CAS (e.g. `AtomicInteger`).
- "Blocking but VT-friendly" = thread waits, carrier freed (`ReentrantLock`, `Semaphore`).
- Only `tryLock()` (no timeout) is truly non-blocking.

```java
lock.lock();              // BLOCKS if contended (VT unmounts)
lock.tryLock();           // NON-BLOCKING — true/false immediately
lock.tryLock(1, SECONDS); // BLOCKS up to 1s
lock.lockInterruptibly(); // BLOCKS, throws on Thread.interrupt()
```

## Java 21 → 24/25 upgrade?
- **Skip 24** — non-LTS, support ended Sep 2025.
- **Java 25 LTS** is the real target (includes JEP 491 + JEP 483 AOT class loading + compact headers + gen ZGC default).
- For `PgmqWorker` specifically — **no urgency**: pgjdbc (`ResourceLock`), Hikari 5.1.0+, Semaphore-based code = already park, not pin.
- **Decision rule**: baseline with `-Djdk.tracePinnedThreads=full` first. Empty log → upgrade is "nice to have" not "must have". Java 21 supported through 2031.

## 30-sec defence one-liners
| Q | A |
|---|---|
| `Thread.sleep` in VT? | Unmounts, no pin. |
| pgjdbc pinning? | `ResourceLock` since 42.7.2 → park, not pin. |
| Why `Semaphore`? | Caps DB-connection demand to Hikari pool size. |
| `synchronized` vs Lock? | Lock around I/O on 21–23 to avoid pinning; `synchronized` for short in-memory. |
| What is pinning? | VT stuck on carrier inside `synchronized` + blocking call → wastes platform thread. |
| Upgrade to 24? | No — non-LTS. Java 25 LTS is the target if we move. |
