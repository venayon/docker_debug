package uk.gov.dwp.gysp.acceptancetests.utils;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.awaitility.core.ThrowingRunnable;
import org.hamcrest.Matcher;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Polling helpers built on <a href="https://github.com/awaitility/awaitility">Awaitility</a>
 * for "eventually true" conditions that are NOT WebElement/DOM related -
 * backend jobs finishing, REST APIs reflecting a state change, DB rows
 * appearing, queue messages landing, files being written, etc.
 *
 * <p><b>This class is intentionally separate from {@link WaitUtil}.</b>
 * {@code WaitUtil} wraps Selenium's own {@code WebDriverWait}/{@code FluentWait}
 * for WebElement conditions (click, visible text) and is fail-safe by design -
 * it swallows timeouts and returns {@code false}/{@code Optional.empty()}.
 * {@code AwaitUtil} is the opposite on purpose: it is meant to back
 * {@code Then} assertions on backend/async state, so a timeout SHOULD fail
 * the scenario - methods here throw {@link ConditionTimeoutException} rather
 * than swallowing it. Do not nest an {@code AwaitUtil} poll around a
 * {@code WaitUtil} call (or vice versa) - each already retries internally,
 * and nesting just compounds timeouts when something is genuinely broken.
 *
 * <h2>Usage examples</h2>
 *
 * <p><b>1. Simple boolean condition</b> - wait for an async job to finish:
 * <pre>{@code
 * @Then("the batch job eventually completes")
 * public void batchJobEventuallyCompletes() {
 *     AwaitUtil.waitUntil("batch job completion for run " + runId,
 *             () -> batchJobClient.getStatus(runId) == JobStatus.COMPLETE);
 * }
 * }</pre>
 *
 * <p><b>2. Wait for a value and assert on it in one step</b> - wait for a
 * REST API field to reach an expected value:
 * <pre>{@code
 * @Then("the payment status eventually shows as {string}")
 * public void paymentStatusEventuallyShows(String expectedStatus) {
 *     AwaitUtil.waitForValue(
 *             "payment status for " + paymentId,
 *             () -> paymentApiClient.getStatus(paymentId),
 *             equalTo(expectedStatus));
 * }
 * }</pre>
 *
 * <p><b>3. Wait for a collection to reach an expected size</b> - e.g. an
 * async message has arrived on a queue/topic:
 * <pre>{@code
 * @Then("exactly {int} notification(s) have been sent")
 * public void notificationsEventuallySent(int expectedCount) {
 *     AwaitUtil.waitForValue(
 *             "notification count for " + customerId,
 *             () -> notificationRepository.findByCustomerId(customerId),
 *             hasSize(expectedCount));
 * }
 * }</pre>
 *
 * <p><b>4. Overriding the default timeout/poll interval</b> for a
 * particularly slow or particularly fast condition:
 * <pre>{@code
 * AwaitUtil.waitUntil("nightly reconciliation file appears",
 *         () -> Files.exists(reconciliationFilePath),
 *         Duration.ofMinutes(5), Duration.ofSeconds(5));
 * }</pre>
 *
 * <p><b>5. DB row appearing after an async write</b>:
 * <pre>{@code
 * AwaitUtil.waitUntil("audit record persisted for " + eventId,
 *         () -> auditRepository.findByEventId(eventId).isPresent());
 * }</pre>
 *
 * <h2>Eventual-consistency specific patterns</h2>
 *
 * <p><b>6. Guarding against flapping / non-monotonic convergence.</b>
 * Read replicas or multi-node caches can briefly LOOK correct on one poll
 * then revert before truly settling. {@link #waitUntil}/{@link #waitForValue}
 * accept the first {@code true} result, which can be a false positive in
 * that scenario. Use {@link #waitUntilConsistentlyTrue} when you need the
 * condition to hold continuously for a period, not just once:
 * <pre>{@code
 * // Must report COMPLETE on every poll for a full 5s window, not just once,
 * // before we trust the replica has actually settled.
 * AwaitUtil.waitUntilConsistentlyTrue(
 *         "order status settled for " + orderId,
 *         () -> orderReadReplica.getStatus(orderId) == OrderStatus.COMPLETE,
 *         Duration.ofSeconds(5));
 * }</pre>
 *
 * <p><b>7. Multiple independent sources must agree</b> (e.g. DB write,
 * search-index update, and cache invalidation are all async and may
 * complete at different times). Combine them into a single {@code Callable}
 * with {@code &&} - no new API needed, {@link #waitUntil} already handles it,
 * and every source is re-checked together on every poll:
 * <pre>{@code
 * AwaitUtil.waitUntil("customer record consistent across DB, index and cache for " + customerId,
 *         () -> dbRepository.exists(customerId)
 *                 && searchIndexClient.exists(customerId)
 *                 && cacheClient.get(customerId) != null);
 * }</pre>
 *
 * <p><b>8. Rich failure messages for multi-field eventual-consistency
 * assertions.</b> {@link #waitForValue}'s Hamcrest matcher message is fine
 * for single values, but for "these 4 fields must all eventually match"
 * checks, {@link #untilAsserted} gives you a normal JUnit/AssertJ assertion
 * failure (which field, expected vs actual) instead of one generic matcher
 * mismatch:
 * <pre>{@code
 * @Then("the projection eventually reflects the update")
 * public void projectionEventuallyReflectsUpdate() {
 *     AwaitUtil.untilAsserted("read-model projection for " + aggregateId, () -> {
 *         var projection = projectionRepository.find(aggregateId);
 *         assertThat(projection.getStatus()).isEqualTo("UPDATED");
 *         assertThat(projection.getVersion()).isEqualTo(expectedVersion);
 *         assertThat(projection.getLastModifiedBy()).isEqualTo(expectedUser);
 *     });
 * }
 * }</pre>
 */
public final class AwaitUtil {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

    private AwaitUtil() {
        // Utility class - no instances.
    }

    /**
     * Polls {@code condition} until it returns {@code true}, using the
     * default timeout/poll interval. Transient exceptions thrown by
     * {@code condition} (e.g. a connection reset before a record exists)
     * are ignored and treated as "not yet true" - only a full timeout with
     * no successful evaluation propagates.
     *
     * @param description short human-readable label shown in the failure
     *                     message and test report if the condition times out
     * @param condition    the condition to poll; should be side-effect-free
     *                     and cheap to call repeatedly
     * @throws ConditionTimeoutException if the condition never becomes true
     *                                   within the default timeout
     */
    public static void waitUntil(String description, Callable<Boolean> condition) {
        waitUntil(description, condition, DEFAULT_TIMEOUT, DEFAULT_POLL_INTERVAL);
    }

    /**
     * Same as {@link #waitUntil(String, Callable)} but with an explicit
     * timeout/poll interval, for conditions that are known to be
     * particularly slow (e.g. nightly batch jobs) or need tighter polling.
     */
    public static void waitUntil(String description, Callable<Boolean> condition,
                                  Duration timeout, Duration pollInterval) {
        requireArgs(description, condition, timeout, pollInterval);
        Awaitility.await(description)
                .atMost(timeout)
                .pollInterval(pollInterval)
                .ignoreExceptions()
                .until(condition);
    }

    /**
     * Polls {@code supplier} until its return value satisfies
     * {@code matcher}, then returns that value - useful when the step needs
     * both the wait AND the resulting value (e.g. to log it or use it in a
     * follow-up call), using the default timeout/poll interval.
     *
     * @throws ConditionTimeoutException if the matcher is never satisfied
     *                                   within the default timeout; the
     *                                   exception message includes the last
     *                                   observed value courtesy of Awaitility
     */
    public static <T> T waitForValue(String description, Callable<T> supplier, Matcher<? super T> matcher) {
        return waitForValue(description, supplier, matcher, DEFAULT_TIMEOUT, DEFAULT_POLL_INTERVAL);
    }

    /**
     * Same as {@link #waitForValue(String, Callable, Matcher)} but with an
     * explicit timeout/poll interval.
     */
    public static <T> T waitForValue(String description, Callable<T> supplier, Matcher<? super T> matcher,
                                      Duration timeout, Duration pollInterval) {
        requireArgs(description, supplier, timeout, pollInterval);
        Objects.requireNonNull(matcher, "matcher must not be null");
        return Awaitility.await(description)
                .atMost(timeout)
                .pollInterval(pollInterval)
                .ignoreExceptions()
                .until(supplier, matcher);
    }

    /**
     * Polls {@code condition} until it becomes {@code true} AND then
     * continues polling to confirm it REMAINS {@code true} for the full
     * {@code holdDuration} - guards against eventual-consistency flapping,
     * where a read can briefly appear correct (e.g. hit a replica that has
     * caught up) then revert (e.g. next poll load-balances to a replica
     * that hasn't), before finally settling for good.
     *
     * <p>Uses the default timeout/poll interval; the overall wait (reaching
     * {@code true} for the first time, plus holding it) must still complete
     * within {@link #DEFAULT_TIMEOUT}.
     *
     * @throws ConditionTimeoutException if the condition never becomes true,
     *                                    or becomes true but doesn't hold for
     *                                    the full {@code holdDuration}, within
     *                                    the timeout
     */
    public static void waitUntilConsistentlyTrue(String description, Callable<Boolean> condition, Duration holdDuration) {
        waitUntilConsistentlyTrue(description, condition, DEFAULT_TIMEOUT, DEFAULT_POLL_INTERVAL, holdDuration);
    }

    /**
     * Same as {@link #waitUntilConsistentlyTrue(String, Callable, Duration)}
     * but with an explicit timeout/poll interval. Note {@code timeout} must
     * comfortably exceed {@code holdDuration}, since the hold window is
     * spent entirely after the condition first becomes true.
     */
    public static void waitUntilConsistentlyTrue(String description, Callable<Boolean> condition,
                                                   Duration timeout, Duration pollInterval, Duration holdDuration) {
        requireArgs(description, condition, timeout, pollInterval);
        Objects.requireNonNull(holdDuration, "holdDuration must not be null");
        if (holdDuration.isNegative() || holdDuration.isZero()) {
            throw new IllegalArgumentException("holdDuration must be positive, was " + holdDuration);
        }
        if (holdDuration.compareTo(timeout) >= 0) {
            throw new IllegalArgumentException(
                    "holdDuration (" + holdDuration + ") must be shorter than timeout (" + timeout + ")");
        }
        Awaitility.await(description)
                .atMost(timeout)
                .pollInterval(pollInterval)
                .ignoreExceptions()
                .during(holdDuration)
                .until(condition);
    }

    /**
     * Polls {@code assertion} until it runs without throwing, using the
     * default timeout/poll interval. Prefer this over
     * {@link #waitForValue(String, Callable, Matcher)} when you need to
     * check several fields/conditions together and want normal
     * assertion-library failure messages (expected vs actual per field)
     * rather than one generic matcher mismatch.
     *
     * <p>An {@link AssertionError} thrown by {@code assertion} is treated
     * exactly like "not yet true" and retried; other runtime exceptions are
     * also ignored while polling (consistent with the rest of this class),
     * so a transient lookup failure won't abort the wait early.
     *
     * @throws ConditionTimeoutException if the assertion never passes within
     *                                    the default timeout; Awaitility
     *                                    includes the last assertion failure
     *                                    in the thrown exception's message
     */
    public static void untilAsserted(String description, ThrowingRunnable assertion) {
        untilAsserted(description, assertion, DEFAULT_TIMEOUT, DEFAULT_POLL_INTERVAL);
    }

    /**
     * Same as {@link #untilAsserted(String, ThrowingRunnable)} but with an
     * explicit timeout/poll interval.
     */
    public static void untilAsserted(String description, ThrowingRunnable assertion,
                                      Duration timeout, Duration pollInterval) {
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(assertion, "assertion must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        Awaitility.await(description)
                .atMost(timeout)
                .pollInterval(pollInterval)
                .ignoreExceptions()
                .untilAsserted(assertion);
    }

    /**
     * Polls {@code condition} like {@link #waitUntil(String, Callable)}, but
     * returns {@code true}/{@code false} instead of throwing on timeout.
     * Use sparingly - prefer the throwing variants for {@code Then} steps so
     * a timeout correctly fails the scenario. This is for the rare case
     * where "did it happen in time" is itself the assertion (e.g. verifying
     * something did NOT happen within a window), not a precondition for
     * further steps.
     */
    public static boolean waitUntilQuietly(String description, Callable<Boolean> condition,
                                            Duration timeout, Duration pollInterval) {
        try {
            waitUntil(description, condition, timeout, pollInterval);
            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }

    private static void requireArgs(String description, Callable<?> callable, Duration timeout, Duration pollInterval) {
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(callable, "condition/supplier must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, was " + timeout);
        }
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive, was " + pollInterval);
        }
    }
}
