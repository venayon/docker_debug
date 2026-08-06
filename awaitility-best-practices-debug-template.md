# Awaitility — Best Practices & Failure Debug Template

**Stack:** Selenium + Java 21 + Cucumber (BOM 7.34) + Awaitility
**Purpose:** (1) Reference for correct Awaitility usage, (2) fill-in template to work through when a `ConditionTimeoutException` fires

---

## Part A — Awaitility Best Practices Reference

### A1. Always set these five things on every `await()`

```java
Awaitility.await("clear plain-English description of the condition")   // 1. alias
    .pollInterval(Duration.ofMillis(500))                              // 2. poll interval
    .pollDelay(Duration.ofMillis(200))                                 // 3. poll delay
    .atMost(Duration.ofSeconds(15))                                    // 4. timeout
    .ignoreExceptionsInstanceOf(StaleElementReferenceException.class)  // 5. exception policy
    .conditionEvaluationListener(condition ->
        log.debug("{}: {} (elapsed {}ms, remaining {}ms)",
            condition.getDescription(), condition.getValue(),
            condition.getElapsedTimeInMS(), condition.getRemainingTimeInMS()))
    .until(() -> /* condition, re-located via By, not a cached WebElement */);
```

| Setting | Why it matters | Common mistake |
|---|---|---|
| `.alias(...)` | Shows in the exception message — turns "timeout after 15s" into "cart total reflects added item: timeout after 15s" | Omitted; failures become generic and hard to triage |
| `.pollInterval(...)` | How often the condition is re-checked | Too fine = DOM/API hammering; too coarse = missed short-lived state |
| `.pollDelay(...)` | Delay before the *first* check | Left at default (= pollInterval), so first check fires before async work starts |
| `.atMost(...)` | Hard timeout | One flat value for local + CI; CI is almost always slower |
| `.ignoreExceptionsInstanceOf(...)` / `.ignoreExceptions()` | Transient exceptions (e.g. stale element) don't kill the wait on poll #1 | Omitted entirely — single flaky poll fails the whole wait |
| `.conditionEvaluationListener(...)` | Logs the last-seen value at each poll | Omitted — failure gives no clue what value was actually seen |

### A2. Condition-writing rules

- **Re-locate elements by `By` inside the lambda** — never capture a `WebElement` from outside and reuse it across polls (guarantees `StaleElementReferenceException` after any re-render).
- **One condition = one concern.** Don't combine "spinner gone" AND "value updated" in a single `.until()` — split into two sequential waits so failures pinpoint which stage broke.
- **Prefer polling the API/backend over the DOM** when the state is backend-driven (order status, job completion) — removes frontend re-render timing as a variable.
- **Keep the lambda side-effect-free** — it may run many times; don't mutate shared state inside it.

### A3. Environment-aware configuration (set once, globally)

```java
Awaitility.setDefaultPollInterval(Duration.ofMillis(300));
Awaitility.setDefaultPollDelay(Duration.ofMillis(100));
Awaitility.setDefaultTimeout(Duration.ofSeconds(isCi() ? 20 : 10));
```

Set in a `@BeforeAll` / Cucumber global hook — don't duplicate timeout constants across step defs.

### A4. Things to avoid

| Anti-pattern | Why |
|---|---|
| `.await().until(() -> driver.findElement(...).isDisplayed())` with no exception handling | First `NoSuchElementException` or stale element kills the wait immediately |
| Nesting `Awaitility.await()` inside another `.until()` lambda | Multiplies timeouts, makes failures untraceable |
| Using `Awaitility` to wait on something that will *never* become true in a broken state (e.g. waiting for a success toast when app shows an error instead) | Wastes full `atMost` duration before failing; add a fail-fast condition instead (see A5) |
| Copy-pasted timeout literals across dozens of step defs | Impossible to tune globally; drifts over time |

### A5. Fail-fast pattern (don't wait out the full timeout on a known failure state)

```java
Awaitility.await("order reaches CONFIRMED or fails fast on ERROR")
    .atMost(Duration.ofSeconds(15))
    .until(() -> {
        OrderStatus status = orderApiClient.getStatus(orderId);
        if (status == OrderStatus.ERROR) {
            throw new AssertionError("Order entered ERROR state — failing fast instead of waiting out timeout");
        }
        return status == OrderStatus.CONFIRMED;
    });
```

---

## Part B — Debug Template (fill in when a test fails with `ConditionTimeoutException`)

Copy this block into the ticket/PR/Slack thread when triaging a failure.

```markdown
### Awaitility Failure Debug — [Scenario/Feature name]

**Date/Run:**
**Environment:** Local / CI
**Feature file / step:**
**Awaitility alias (from exception message):**

---

#### 1. Raw failure
```
<paste ConditionTimeoutException stack trace / message here>
```

#### 2. Last polled value (from conditionEvaluationListener log, if present)
```
<paste last few condition evaluation log lines here>
```
- [ ] Listener logging was present — value is known
- [ ] Listener logging was absent — add it before re-running (see A1)

#### 3. What was the condition waiting for?
(plain English, e.g. "cart-total text equals expected total")


#### 4. Bucket classification
- [ ] 1 — Timeout too short (env slower than local; legitimate variance)
- [ ] 2 — Wrong wait condition (waiting on presence vs visibility/clickability/staleness)
- [ ] 3 — Stale element / exception not ignored
- [ ] 4 — Shared/static state race (parallel Cucumber execution)
- [ ] 5 — Poll interval mis-tuned (too coarse/too fine)
- [ ] 6 — Genuine app/service defect (not a test issue)

#### 5. Reproduction
- [ ] Reproduces locally
- [ ] Reproduces only in CI
- [ ] Reproduces intermittently (record pass/fail ratio over N runs): ___/___
- Command/run used to reproduce:

#### 6. Evidence gathered
- [ ] Screenshot at failure point attached
- [ ] Page source at failure point attached
- [ ] Browser console logs attached (if UI-related)
- [ ] Network/API logs attached (if backend-driven condition)
- [ ] Confirmed whether `cucumber.execution.parallel.enabled` is on for this run

#### 7. Root cause
(one sentence once identified)


#### 8. Fix applied
- [ ] Adjusted `.atMost()` / `.pollInterval()` / `.pollDelay()`
- [ ] Added `.ignoreExceptionsInstanceOf(...)`
- [ ] Re-located element via `By` instead of cached `WebElement`
- [ ] Split combined wait into two sequential waits
- [ ] Switched from DOM polling to API polling
- [ ] Fixed shared/static state (moved to `ThreadLocal`/DI)
- [ ] Filed bug against app/service (Bucket 6) — ticket link:
- [ ] Other:

#### 9. Verification
- Re-run count after fix: ___
- Pass rate after fix: ___/___
- [ ] Confirmed fix under parallel execution (if applicable)
- [ ] Confirmed fix in CI, not just local

#### 10. Follow-up
- [ ] Applied same fix pattern to other step defs with the same anti-pattern (list files):
- [ ] Added to best-practices doc / PR checklist if new pattern discovered
```

---

## Part C — Quick Diagnostic Flowchart

```
ConditionTimeoutException thrown
        │
        ▼
Was conditionEvaluationListener logging last value?
   │ No  → Add it, re-run, come back here
   ▼ Yes
Is the last logged value close to expected (timing issue)
or completely wrong/unexpected (logic issue)?
   │
   ├─ Close/right-shape → Bucket 1 or 5 (tune atMost/pollInterval)
   │
   ├─ Wrong/error state present → Bucket 6 (real app defect) or
   │                              add fail-fast condition (A5)
   │
   └─ Exception thrown mid-poll (stale element, no such element)
        → Bucket 3 (add ignoreExceptionsInstanceOf, re-locate via By)

Does it only fail in CI / under parallel execution?
   │ Yes → Bucket 4 (audit static/shared WebDriver + test data)
   ▼ No
Does it fail on a condition combining multiple states
(e.g. spinner + value)?
   │ Yes → Bucket 2 (split into sequential waits)
```
