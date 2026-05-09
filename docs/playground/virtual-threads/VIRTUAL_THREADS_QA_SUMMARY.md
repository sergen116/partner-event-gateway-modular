# Virtual Threads QA — Quick Summary

Cheat-sheet companion to `VIRTUAL_THREADS_QA.md`. Section headers + the
one issue/answer to remember per section.

---

## 1. `Thread.sleep` on virtual threads
- **Issue:** Does sleeping pin the carrier?
- **Answer:** No — VT unmounts via park-with-deadline. Same for
  `Semaphore.acquire`, `CompletableFuture.join`, `awaitTermination`.
- **Real pinning causes:** `synchronized` around blocking I/O, JNI,
  `Object.wait` inside `synchronized`.

## 2. Is pgjdbc virtual-thread-safe?
- **Issue:** Old pgjdbc used `synchronized` on JDBC entry points → pinned
  carriers during every query.
- **Answer:** pgjdbc **42.7.2+** uses `ResourceLock` (a try-with-resources
  `ReentrantLock` wrapper) → `LockSupport.park` → unmounts cleanly.
- **Also check:** HikariCP **5.1.0+** (also moved off `synchronized`).
- **Why the `Semaphore`:** caps DB-connection demand to Hikari pool size;
  not for protecting carriers.

## 3. `synchronized` vs `ReentrantLock`
- **Issue:** Which to choose for VT-heavy code on Java 21–23?
- **Answer:**
  - `synchronized` → pins on 21–23, fine on 24+.
  - `ReentrantLock` → never pins, plus timeout / interruptible /
    `tryLock` / fairness / multiple conditions.
- **Rule:** lock around I/O critical sections; `synchronized` ok for
  short, in-memory sections.

## 4. What is pinning?
- **Issue:** A VT can't unmount → carrier wasted → with N carriers,
  N pins stalls *everything*.
- **Causes (Java 21–23):** `synchronized` + blocking, JNI, `Object.wait`.
- **Fixed by:** **JEP 491 (Java 24)** — monitors tracked off-heap.
- **Detect:** `-Djdk.tracePinnedThreads=full` or JFR
  `jdk.VirtualThreadPinned`.

## 5. Is `ReentrantLock` non-blocking?
- **Issue:** Confusion between "lock-free" and "VT-friendly".
- **Answer:** It **blocks** the VT — but parks via `LockSupport.park`,
  so the **carrier** is freed. Only `tryLock()` (no timeout) is truly
  non-blocking.
- **Mental model:** VT pauses, platform thread keeps working.

## 6. Upgrade Java 21 → 24/25?
- **Issue:** Is there a real need to move off Java 21 LTS?
- **Answer:**
  - **Skip 24** — non-LTS, support ended Sep 2025.
  - **Java 25 LTS** is the real target (includes JEP 491, 483, compact
    headers, gen ZGC default).
  - For `PgmqWorker` specifically: **no urgency** — pgjdbc + Hikari +
    `Semaphore` already park, not pin.
- **Decision rule:** baseline with `-Djdk.tracePinnedThreads=full` first;
  empty log = upgrade is "nice to have."

---

## 30-second defence one-liners

| Q | A |
|---|---|
| `Thread.sleep` in VT? | Unmounts, no pin. |
| pgjdbc pinning? | `ResourceLock` since 42.7.2 → park, not pin. |
| Why `Semaphore`? | Caps DB-connection demand to Hikari pool. |
| `synchronized` vs Lock? | Lock around I/O on 21–23; `synchronized` for short in-memory. |
| Pinning? | VT stuck on carrier inside `synchronized` + blocking call. |
| Upgrade to 24? | No — non-LTS. 25 LTS is the target if we move. |
