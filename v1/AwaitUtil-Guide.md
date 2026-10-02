# AwaitUtil Guide

Helpers for waiting on **backend and service state**: APIs, databases, queues, files and background jobs. It is built on [Awaitility](http://www.awaitility.org/).

> **Rule of thumb:** if you are waiting for something that is **not on a web page**, use `AwaitUtil`.
> For browser elements, text, URLs or titles, use [`WaitUtil`](WaitUtil-Guide.md).

---

## 1. Why use it

Backend systems are *eventually consistent*. You trigger something and the result shows up a moment later:

- a payment moves from `PENDING` to `COMPLETE`
- a row appears in the database
- a message lands on a queue
- an export file is written to disk

Checking once is flaky, and `Thread.sleep(10000)` is slow and still not reliable. `AwaitUtil` checks repeatedly and continues as soon as the condition is true. **Never use `Thread.sleep` in tests.**

Defaults: wait up to **30 seconds**, check every **1 second**. The first check happens immediately, so it is fast when the system is already ready.

---

## 2. How it behaves

| Topic | What to know |
|---|---|
| On timeout | Throws `org.awaitility.core.ConditionTimeoutException` |
| Errors inside your condition | **Ignored and retried.** A connection refused or "not found yet" counts as "not true yet" |
| Why it timed out | The **last** error your condition threw is attached to the timeout as a *suppressed exception*, so the real cause is not lost |
| Which thread runs your code | Awaitility runs it on its own thread, so `ThreadLocal` values (some test contexts, per-thread clients) are **not visible** inside your lambda |
| Bad arguments | Zero or negative durations throw `IllegalArgumentException`. Nulls throw `NullPointerException` |

To see the hidden cause of a timeout:

```java
try {
    AwaitUtil.waitUntil("order visible", () -> orderApi.exists(orderId));
} catch (ConditionTimeoutException e) {
    for (Throwable t : e.getSuppressed()) {
        t.printStackTrace();   // e.g. 401 Unauthorized, connection refused
    }
    throw e;
}
```

> Tip: if a wait always times out in about 30 s, look at the suppressed exception first. A wrong URL or expired token looks exactly like "slow system" otherwise.

---

## 3. Quick reference

| Method | Use it to... |
|---|---|
| `waitUntil(description, condition)` | Wait until something becomes `true` |
| `waitUntil(description, condition, timeout, poll)` | Same, with your own times |
| `waitForValue(description, supplier, predicate)` | Wait until a value looks right, and **get that value back** |
| `waitForValue(description, supplier, predicate, timeout, poll)` | Same, with your own times |
| `waitUntilConsistentlyTrue(description, condition, hold)` | Wait until something is true **and stays true** |
| `waitUntilConsistentlyTrue(description, condition, timeout, poll, hold)` | Same, with your own times |
| `untilAsserted(description, assertion)` | Wait until a block of assertions passes |
| `untilAsserted(description, assertion, timeout, poll)` | Same, with your own times |
| `waitUntilQuietly(description, condition, timeout, poll)` | Wait, but return `false` instead of throwing |

Every method takes a **description** first. Write what you are waiting for (`"payment is COMPLETE"`), because it appears in the failure message.

---

## 4. The methods

### `waitUntil(description, condition)`

**Solves:** "wait until this is true". The condition is a lambda that returns `Boolean`.

**Use it:** for simple yes/no checks such as a record existing or a file being present.

```java
// A row appears in the database
AwaitUtil.waitUntil("customer row exists",
        () -> customerRepository.findByEmail(email).isPresent());

// A file is written
AwaitUtil.waitUntil("export file exists",
        () -> Files.exists(Path.of("build/exports/report.csv")));
```

With your own times:

```java
import java.time.Duration;

AwaitUtil.waitUntil("message on queue",
        () -> queue.size() > 0,
        Duration.ofSeconds(10),      // give up after 10 s
        Duration.ofMillis(250));     // check every 250 ms
```

### `waitForValue(description, supplier, predicate)`

**Solves:** you need the **value**, not just a yes/no. It keeps fetching until your test says the value is acceptable, then returns it so you can keep using it.

**Use it:** when you want to assert on or use the result afterwards.

```java
String status = AwaitUtil.waitForValue(
        "payment status is COMPLETE",
        () -> paymentApi.getStatus(paymentId),     // how to fetch the value
        s -> "COMPLETE".equals(s));                // when is it good enough?

System.out.println("Final status: " + status);
```

```java
// Use the returned object afterwards
Order order = AwaitUtil.waitForValue(
        "order is shipped",
        () -> orderApi.get(orderId),
        o -> o.getStatus() == Status.SHIPPED);

Assert.assertNotNull(order.getTrackingNumber());
```

> If the supplier returns `null`, your predicate receives `null`. Handle it, for example `s -> s != null && s.isReady()`.

### `waitUntilConsistentlyTrue(description, condition, hold)`

**Solves:** conditions that **flicker** while a system settles. A value can be true for a moment and then change back. This waits until it is true **and stays true** for the `hold` time.

**Use it:** when "true once" is not enough, for example search indexes, caches, replicated data.

```java
// Count must be 3 and stay 3 for 5 seconds
AwaitUtil.waitUntilConsistentlyTrue(
        "index has 3 documents",
        () -> searchIndex.count() == 3,
        Duration.ofSeconds(5));
```

Full version:

```java
AwaitUtil.waitUntilConsistentlyTrue(
        "index has 3 documents",
        () -> searchIndex.count() == 3,
        Duration.ofSeconds(30),   // total timeout
        Duration.ofMillis(500),   // poll
        Duration.ofSeconds(5));   // must stay true this long
```

Rules:
- `hold` must be positive and **shorter than** the timeout (otherwise `IllegalArgumentException`).
- The timeout covers **both** the time to become true **and** the hold time. Leave room: with `hold = 5s`, a timeout of `6s` is almost impossible to pass.
- Exactly how a "flip back to false" is handled (restart the hold timer, or fail straight away) depends on your Awaitility version. If it matters to your test, write a small test to confirm.

### `untilAsserted(description, assertion)`

**Solves:** several checks that must all pass together. The block is retried until **nothing inside it throws**.

**Use it:** when you would otherwise write many separate waits, or when you want the normal assertion messages.

```java
AwaitUtil.untilAsserted("customer projection updated", () -> {
    Customer c = customerApi.get(customerId);
    Assert.assertEquals(c.getStatus(), "ACTIVE");
    Assert.assertEquals(c.getVersion(), expectedVersion);
});
```

With your own times:

```java
AwaitUtil.untilAsserted("totals match",
        () -> Assert.assertEquals(report.total(), 150),
        Duration.ofSeconds(15),
        Duration.ofMillis(500));
```

> Put the **fetch inside the block** so it is refreshed on every attempt. Fetching once outside the block and asserting inside will never change.

### `waitUntilQuietly(description, condition, timeout, pollInterval)`

**Solves:** an **optional** wait where timing out is not a failure, such as "if a welcome email shows up, great, otherwise carry on".

**Returns:** `true` if the condition became true in time, `false` on timeout. It does not throw for a timeout.

```java
boolean emailArrived = AwaitUtil.waitUntilQuietly(
        "welcome email received",
        () -> mailbox.hasMessage(subject),
        Duration.ofSeconds(5),
        Duration.ofMillis(500));

if (emailArrived) {
    // verify the content
}
```

> **Do not use this for things that must happen.** If the test needs the result, use `waitUntil` so a failure is loud. The quiet version hides the reason for the timeout.

---

## 5. Which method should I use?

| I need to... | Use |
|---|---|
| Wait for a simple true/false | `waitUntil` |
| Wait, then use the value | `waitForValue` |
| Be sure it stays true, not just flashes true | `waitUntilConsistentlyTrue` |
| Check several things together with normal assertions | `untilAsserted` |
| Wait optionally without failing | `waitUntilQuietly` |
| Wait for something on a web page | `WaitUtil`, not `AwaitUtil` |

---

## 6. Common mistakes

| Mistake | Do this instead |
|---|---|
| `Thread.sleep(...)` | Use a wait method |
| Vague description like `"wait"` | Say what you wait for: `"invoice status is PAID"` |
| Fetching data once, then waiting on that same object | Fetch **inside** the lambda so it refreshes each poll |
| Using `waitUntilQuietly` for required results | Use `waitUntil` so failures are visible |
| Very long timeouts "just in case" | Use the time the system actually needs. Long timeouts make failures slow to report |
| Reading `ThreadLocal` data inside the lambda | Read it before the wait and pass it in as a normal variable |
| Using `AwaitUtil` for browser elements | Use `WaitUtil` |

---

## 7. Reading failures

A timeout looks like:

```
org.awaitility.core.ConditionTimeoutException:
Condition with alias 'payment status is COMPLETE' didn't complete within 30 seconds ...
```

1. The **alias** is your description. This is why a good one matters.
2. Check `getSuppressed()` for the last error your condition hit (see section 2).
3. If there is no suppressed error, the condition simply stayed `false`: the system never reached the state. Look at the system under test, not the wait.
