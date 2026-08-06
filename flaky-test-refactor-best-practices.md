# Flaky Test Refactor — Best Practices Guide

**Stack:** Selenium + Java 21 + Cucumber (BOM 7.34) + Awaitility
**Purpose:** Reference checklist + code patterns for identifying and refactoring flaky acceptance tests

---

## 1. How to Find What Needs Refactoring

Search the codebase for these anti-patterns first — each is a near-guaranteed flake source.

| Search for | Why it's a smell |
|---|---|
| `Thread.sleep(` | Fixed wait, either too short (flaky) or too long (slow suite) |
| `findElement(` cached into a local `WebElement` reused across lines | Risk of `StaleElementReferenceException` after any DOM re-render |
| `catch (Exception e) {}` / empty catch blocks | Swallows real failures, masks flakiness as "pass" |
| `public static WebDriver driver` | Shared mutable state — breaks under parallel execution |
| `public static` fields in `Hooks` / `BaseTest` holding test data | Cross-scenario state leakage |
| `.atMost(` with no `.pollDelay()` set | First poll fires before async work starts |
| `.until(` without `.ignoreExceptions()` | Transient exception kills the wait on poll #1 |
| Hardcoded `sleep`/timeout values duplicated across step defs | No central tuning point; inconsistent behavior |
| Assertions inside `@Before`/`@After` hooks | Failures reported against the wrong scenario, hard to trace |
| Cucumber `@Order` used to force scenario sequencing | Signals real Bucket-4 shared-state coupling, not just wait tuning |

```bash
# quick grep sweep
grep -rn "Thread.sleep" src/test
grep -rn "static WebDriver" src/test
grep -rn "catch (Exception" src/test
grep -rn "atMost" src/test | grep -L "pollDelay"
```

---

## 2. Refactor Patterns

### 2.1 Replace fixed sleeps with condition-based waits

```java
// ❌ Before
Thread.sleep(5000);
String total = driver.findElement(By.id("cart-total")).getText();
assertEquals(expectedTotal, total);

// ✅ After
Awaitility.await("cart total reflects added item")
    .pollInterval(Duration.ofMillis(500))
    .pollDelay(Duration.ofMillis(200))
    .atMost(Duration.ofSeconds(15))
    .ignoreExceptionsInstanceOf(StaleElementReferenceException.class)
    .until(() -> driver.findElement(By.id("cart-total")).getText().equals(expectedTotal));
```

### 2.2 Never cache a `WebElement` across a wait — re-locate via `By`

```java
// ❌ Before — cached element goes stale after re-render
WebElement submit = driver.findElement(By.id("submit"));
Awaitility.await().until(submit::isDisplayed);

// ✅ After — re-queried on every poll
Awaitility.await()
    .ignoreExceptionsInstanceOf(StaleElementReferenceException.class)
    .until(() -> ExpectedConditions
        .visibilityOfElementLocated(By.id("submit"))
        .apply(driver) != null);
```

### 2.3 Split "UI settled" waits from "business condition" waits

```java
// ❌ Before — one wait doing two jobs, hard to diagnose which part failed
Awaitility.await().atMost(Duration.ofSeconds(20))
    .until(() -> !isSpinnerVisible() && orderStatusText().equals("CONFIRMED"));

// ✅ After — two short, independently diagnosable waits
Awaitility.await("loading spinner disappears")
    .atMost(Duration.ofSeconds(5))
    .until(() -> !isSpinnerVisible());

Awaitility.await("order status reaches CONFIRMED")
    .atMost(Duration.ofSeconds(15))
    .until(() -> orderStatusText().equals("CONFIRMED"));
```

### 2.4 Poll the API instead of the DOM for backend-driven state

```java
// ❌ Before — depends on frontend re-render cycle, adds noise
Awaitility.await().until(() -> orderStatusText().equals("SHIPPED"));

// ✅ After — poll the source of truth directly
Awaitility.await("order API reports SHIPPED")
    .atMost(Duration.ofSeconds(15))
    .until(() -> orderApiClient.getStatus(orderId) == OrderStatus.SHIPPED);
```

### 2.5 Eliminate shared/static `WebDriver` — use `ThreadLocal`

```java
// ❌ Before — breaks under cucumber.execution.parallel.enabled=true
public class Hooks {
    public static WebDriver driver;
}

// ✅ After
public class DriverFactory {
    private static final ThreadLocal<WebDriver> DRIVER = new ThreadLocal<>();

    public static WebDriver get() {
        if (DRIVER.get() == null) {
            DRIVER.set(new ChromeDriver());
        }
        return DRIVER.get();
    }

    public static void quit() {
        if (DRIVER.get() != null) {
            DRIVER.get().quit();
            DRIVER.remove();
        }
    }
}
```

### 2.6 Centralize Awaitility defaults instead of scattering magic numbers

```java
// ✅ One-time setup, e.g. in a JUnit/Cucumber @BeforeAll hook
Awaitility.setDefaultPollInterval(Duration.ofMillis(300));
Awaitility.setDefaultPollDelay(Duration.ofMillis(100));
Awaitility.setDefaultTimeout(Duration.ofSeconds(isCi() ? 20 : 10));
```

### 2.7 Always instrument waits — alias + listener, not silent

```java
Awaitility.await("<what you're waiting for, in plain English>")
    .conditionEvaluationListener(condition ->
        log.debug("{}: {} (elapsed {}ms, remaining {}ms)",
            condition.getDescription(), condition.getValue(),
            condition.getElapsedTimeInMS(), condition.getRemainingTimeInMS()))
    .until(() -> /* condition */);
```

### 2.8 Isolate scenario state — no cross-scenario leakage

```java
// ❌ Before
public class BaseTest {
    public static String lastCreatedOrderId;
}

// ✅ After — inject fresh per-scenario via Cucumber PicoContainer/Spring context
public class TestContext {
    private String lastCreatedOrderId;
    // getters/setters, one instance per scenario via DI
}
```

---

## 3. Awaitility Configuration Checklist

Apply to **every** `.await()` call, or set as global defaults:

- [ ] `.pollDelay(...)` explicitly set (don't rely on default = first check is immediate)
- [ ] `.pollInterval(...)` tuned to the operation (UI: 300–500ms; API polling: can go lower)
- [ ] `.atMost(...)` set per-environment (CI timeout ≥ local timeout)
- [ ] `.ignoreExceptionsInstanceOf(StaleElementReferenceException.class)` (or `.ignoreExceptions()`) present
- [ ] `.alias("...")` describes the condition in plain English
- [ ] `.conditionEvaluationListener(...)` logs elapsed/remaining time for diagnosis
- [ ] Condition re-locates elements via `By`, never reuses a cached `WebElement`

---

## 4. Cucumber-Specific Checklist

- [ ] No `public static` fields in `Hooks`, `BaseTest`, or step definition classes
- [ ] `WebDriver` is `ThreadLocal` or instantiated per-scenario via DI container
- [ ] Confirm `cucumber.execution.parallel.enabled` matches actual test isolation (don't enable parallel with shared state)
- [ ] No assertions inside `@Before`/`@After` — only setup/teardown logic
- [ ] `@Order`/`@Suite` sequencing dependencies documented or removed — scenarios should be independently runnable
- [ ] Each scenario tears down its own data (no reliance on execution order for cleanup)

---

## 5. Code Review Gate (add to PR checklist)

Before approving any PR touching test step definitions:

- [ ] No new `Thread.sleep(` calls
- [ ] No new `catch (Exception e) {}` / broad empty catches
- [ ] No new `static` mutable fields introduced in test infra
- [ ] Every new `Awaitility.await()` has `.alias()`, `.pollDelay()`, and exception handling
- [ ] Elements queried inside a wait are re-located via `By`, not a pre-fetched `WebElement`

---

## 6. Suggested Refactor Order (for existing suite)

1. Grep sweep (Section 1) → produce inventory of anti-pattern hits, file + line count
2. Apply global Awaitility defaults (Section 2.6) — no per-test changes needed
3. Fix stale-element handling suite-wide (Section 2.2) — highest yield, low risk
4. Add instrumentation (Section 2.7) to remaining custom waits
5. Migrate `static WebDriver`/state to `ThreadLocal`/DI (Section 2.5, 2.8) — highest effort, do last unless parallel execution is already enabled and actively causing failures
6. Re-run suite 3–5x, compare failure rate before/after
