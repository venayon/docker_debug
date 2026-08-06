# Awaitility `ConditionTimeoutException` — Analysis & Resolution Template

**Project:** Selenium + Java 21 + Cucumber (BOM 7.34)
**Prepared by:**
**Date:**
**Scope:** Investigation of `org.awaitility.core.ConditionTimeoutException` failures following migration from `Thread.sleep()` to Awaitility

---

## 1. Summary

| Metric | Value |
|---|---|
| Total test failures analyzed | |
| % attributable to `ConditionTimeoutException` | 80% |
| Date range of failures analyzed | |
| Environment(s) | Local / CI / Both |

**One-line conclusion:** *(fill in once buckets below are quantified — e.g. "62% of failures are stale-element/config issues fixable this sprint; 18% are a real parallel-execution race in `BaseHooks`.")*

---

## 2. Failure Bucket Breakdown

Classify each failure from logs/stack traces into one of these buckets before attempting fixes.

| # | Bucket | Description | Count | % of total | Fix effort |
|---|---|---|---|---|---|
| 1 | Timeout too short | CI/env slower than local; legitimate latency variance | | | Low |
| 2 | Wrong wait condition | Waiting on presence instead of visibility/clickability/staleness | | | Low–Med |
| 3 | Stale element not ignored | `StaleElementReferenceException` not handled in `.until()` | | | Low |
| 4 | Shared/static state race | Parallel Cucumber execution + static `WebDriver`/test data | | | High |
| 5 | Poll interval mis-tuned | Too coarse (misses state) or too fine (throttling) | | | Low |
| 6 | Genuine app defect | Underlying app/service actually slow or broken in that env | | | N/A (bug ticket) |

> Tip: instrument first (Section 3) before bucketing — most "unknown" timeouts become obvious once alias + listener logging is added.

---

## 3. Instrumentation (do this before tuning anything)

Add to every `Awaitility.await()` call:

```java
Awaitility.await("<clear description of what you're waiting for>")
    .pollInterval(Duration.ofMillis(500))
    .pollDelay(Duration.ofMillis(200))
    .atMost(Duration.ofSeconds(15))
    .ignoreExceptions()
    .conditionEvaluationListener(condition ->
        log.debug("{}: {} (elapsed {}ms, remaining {}ms)",
            condition.getDescription(), condition.getValue(),
            condition.getElapsedTimeInMS(), condition.getRemainingTimeInMS()))
    .until(() -> /* condition */);
```

This alone turns most "mystery timeout" failures into diagnosable ones (last polled value is visible in logs/exception message).

---

## 4. Root Cause Findings

*(One row per distinct root cause identified — link to failing step defs / PRs / tickets as evidence accumulates.)*

| Root cause | Affected step defs / features | Evidence | Bucket |
|---|---|---|---|
| | | | |
| | | | |

---

## 5. Recommended Fixes

### 5.1 Global config (apply once, centrally)

```java
Awaitility.setDefaultPollInterval(Duration.ofMillis(300));
Awaitility.setDefaultPollDelay(Duration.ofMillis(100));
Awaitility.setDefaultTimeout(Duration.ofSeconds(isCi() ? 20 : 10));
```

### 5.2 Stale element handling (fixes Bucket 3)

Re-locate elements via `By` on every poll instead of polling a cached `WebElement`:

```java
Awaitility.await()
    .ignoreExceptionsInstanceOf(StaleElementReferenceException.class)
    .ignoreExceptionsInstanceOf(NoSuchElementException.class)
    .atMost(10, TimeUnit.SECONDS)
    .until(() -> ExpectedConditions.visibilityOfElementLocated(By.id("target")).apply(driver) != null);
```

### 5.3 Split UI-settle waits from business-condition waits (fixes Bucket 2)

Don't combine "spinner gone" + "value updated" into one wait — use two sequential, shorter, more diagnosable waits.

### 5.4 Prefer polling the API over the DOM where possible

For backend-driven async state (order status, job completion, etc.), poll the status endpoint directly rather than a DOM node dependent on frontend re-render timing.

### 5.5 Parallel execution / shared state audit (fixes Bucket 4 — highest effort, often highest impact)

- [ ] Confirm `cucumber.execution.parallel.enabled` setting
- [ ] Audit `BaseTest` / `Hooks` for `static` `WebDriver` or test-data fields
- [ ] Confirm `@Before`/`@After` hook scoping is thread-safe
- [ ] Consider `ThreadLocal<WebDriver>` if not already in place

---

## 6. Two Common Awaitility Footguns (checklist)

- [ ] `.ignoreExceptions()` (or targeted `.ignoreExceptionsInstanceOf(...)`) is set on every `.until()` — otherwise a transient exception kills the wait immediately instead of retrying
- [ ] `.pollDelay()` is explicitly set — otherwise the first check fires immediately, before async work has had a chance to start

---

## 7. Rollout / Verification Plan

| Step | Owner | Status |
|---|---|---|
| 1. Add instrumentation (Section 3) to all `await()` calls | | |
| 2. Re-run full suite, collect logs | | |
| 3. Bucket failures using Section 2 | | |
| 4. Fix Bucket 3 (stale element) globally | | |
| 5. Fix Bucket 4 (parallelism/shared state) if present | | |
| 6. Tune timeouts/poll intervals per remaining buckets | | |
| 7. Re-run suite 3–5x, confirm failure rate drop | | |

**Before → After failure rate:** ____% → ____%

---

## 8. Appendix: Example Before/After

**Before (Thread.sleep, masked flakiness):**
```java
Thread.sleep(5000);
String total = driver.findElement(By.id("cart-total")).getText();
assertEquals(expectedTotal, total);
```

**After (Awaitility, instrumented, stale-safe):**
```java
Awaitility.await("cart total reflects added item")
    .pollInterval(Duration.ofMillis(500))
    .pollDelay(Duration.ofMillis(200))
    .atMost(Duration.ofSeconds(15))
    .ignoreExceptionsInstanceOf(StaleElementReferenceException.class)
    .conditionEvaluationListener(c -> log.debug("{}: {}", c.getDescription(), c.getValue()))
    .until(() -> driver.findElement(By.id("cart-total")).getText().equals(expectedTotal));
```
