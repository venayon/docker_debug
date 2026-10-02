import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.awaitility.core.ThrowingRunnable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Polling helpers built on Awaitility for "eventually true" conditions
 * that are NOT WebElement/DOM related.
 *
 * <p>
 * Examples:
 * <ul>
 *     <li>Backend jobs finishing</li>
 *     <li>REST APIs reflecting a state change</li>
 *     <li>DB rows appearing</li>
 *     <li>Queue messages landing</li>
 *     <li>Files being written</li>
 * </ul>
 *
 * <p>
 * This class is intentionally separate from {@link WaitUtil}.
 *
 * <p>
 * {@code WaitUtil} is responsible for Selenium/WebElement conditions.
 * {@code AwaitUtil} is responsible for asynchronous backend/service state.
 *
 * <p>
 * {@code AwaitUtil} is intentionally fail-fast on timeout:
 * a timeout throws {@link ConditionTimeoutException}. Exceptions thrown by
 * the polled condition are treated as "not yet true"; the last one is
 * attached to the timeout as a suppressed exception so the root cause is
 * not lost.
 *
 * <p>
 * Polling starts immediately (no initial poll delay), and runs on an
 * Awaitility polling thread, so {@code ThreadLocal} state is not visible
 * to the condition.
 */
public final class AwaitUtil {

    private static final Duration DEFAULT_TIMEOUT =
            Duration.ofSeconds(30);

    private static final Duration DEFAULT_POLL_INTERVAL =
            Duration.ofSeconds(1);

    private AwaitUtil() {
        // Utility class - no instances.
    }

    // =================================================================
    // Core waits
    // =================================================================

    /**
     * Polls {@code condition} until it returns {@code true}.
     *
     * <p>
     * Transient exceptions thrown by the condition are ignored and
     * treated as "not yet true".
     *
     * @param description human-readable description of the condition
     * @param condition condition to poll
     *
     * @throws ConditionTimeoutException if condition does not become
     *                                   true within the timeout
     */
    public static void waitUntil(
            String description,
            Callable<Boolean> condition) {

        waitUntil(
                description,
                condition,
                DEFAULT_TIMEOUT,
                DEFAULT_POLL_INTERVAL
        );
    }

    /**
     * Polls {@code condition} until it returns {@code true}.
     *
     * @param description human-readable description
     * @param condition condition to poll
     * @param timeout maximum amount of time to wait
     * @param pollInterval interval between polling attempts
     */
    public static void waitUntil(
            String description,
            Callable<Boolean> condition,
            Duration timeout,
            Duration pollInterval) {

        requireArgs(
                description,
                condition,
                timeout,
                pollInterval
        );

        AtomicReference<Throwable> last = new AtomicReference<>();
        try {
            Awaitility.await(description)
                    .atMost(timeout)
                    .pollDelay(Duration.ZERO)
                    .pollInterval(pollInterval)
                    .ignoreExceptions()
                    .until(tracking(condition, last));
        } catch (ConditionTimeoutException e) {
            throw attachLast(e, last);
        }
    }

    // =================================================================
    // Wait for value - Pure Java Predicate
    // =================================================================

    /**
     * Polls {@code supplier} until the returned value satisfies
     * the supplied Java {@link Predicate}.
     *
     * <p>
     * A {@code null} value from the supplier is passed to the predicate as-is.
     *
     * <p>
     * Example:
     *
     * <pre>
     * AwaitUtil.waitForValue(
     *         "payment status",
     *         () -> paymentApiClient.getStatus(paymentId),
     *         status -> "COMPLETE".equals(status)
     * );
     * </pre>
     *
     * @param description human-readable description
     * @param supplier value supplier
     * @param condition Java Predicate used to validate the value
     * @param <T> value type
     *
     * @return the value returned by the supplier when the condition
     *         becomes true
     *
     * @throws ConditionTimeoutException if the condition never succeeds
     */
    public static <T> T waitForValue(
            String description,
            Callable<T> supplier,
            Predicate<? super T> condition) {

        return waitForValue(
                description,
                supplier,
                condition,
                DEFAULT_TIMEOUT,
                DEFAULT_POLL_INTERVAL
        );
    }

    /**
     * Polls {@code supplier} until the returned value satisfies
     * the supplied Java {@link Predicate}.
     *
     * @param description human-readable description
     * @param supplier value supplier
     * @param condition Java Predicate used to validate the value
     * @param timeout maximum amount of time to wait
     * @param pollInterval interval between polling attempts
     * @param <T> value type
     *
     * @return the value that satisfied the condition
     */
    public static <T> T waitForValue(
            String description,
            Callable<T> supplier,
            Predicate<? super T> condition,
            Duration timeout,
            Duration pollInterval) {

        requireArgs(
                description,
                supplier,
                timeout,
                pollInterval
        );

        Objects.requireNonNull(
                condition,
                "condition must not be null"
        );

        AtomicReference<Throwable> last = new AtomicReference<>();
        try {
            return Awaitility.await(description)
                    .atMost(timeout)
                    .pollDelay(Duration.ZERO)
                    .pollInterval(pollInterval)
                    .ignoreExceptions()
                    .until(tracking(supplier, last), tracking(condition, last)::test);
        } catch (ConditionTimeoutException e) {
            throw attachLast(e, last);
        }
    }

    // =================================================================
    // Consistently true
    // =================================================================

    /**
     * Polls the condition until it becomes true and remains true
     * for the specified hold duration.
     *
     * <p>
     * Useful for protecting against eventual-consistency flapping.
     */
    public static void waitUntilConsistentlyTrue(
            String description,
            Callable<Boolean> condition,
            Duration holdDuration) {

        waitUntilConsistentlyTrue(
                description,
                condition,
                DEFAULT_TIMEOUT,
                DEFAULT_POLL_INTERVAL,
                holdDuration
        );
    }

    /**
     * Polls the condition until it becomes true and remains true
     * for the specified hold duration.
     */
    public static void waitUntilConsistentlyTrue(
            String description,
            Callable<Boolean> condition,
            Duration timeout,
            Duration pollInterval,
            Duration holdDuration) {

        requireArgs(
                description,
                condition,
                timeout,
                pollInterval
        );

        Objects.requireNonNull(
                holdDuration,
                "holdDuration must not be null"
        );

        if (holdDuration.isNegative()
                || holdDuration.isZero()) {

            throw new IllegalArgumentException(
                    "holdDuration must be positive, was "
                            + holdDuration
            );
        }

        if (holdDuration.compareTo(timeout) >= 0) {

            throw new IllegalArgumentException(
                    "holdDuration ("
                            + holdDuration
                            + ") must be shorter than timeout ("
                            + timeout
                            + ")"
            );
        }

        AtomicReference<Throwable> last = new AtomicReference<>();
        try {
            Awaitility.await(description)
                    .atMost(timeout)
                    .pollDelay(Duration.ZERO)
                    .pollInterval(pollInterval)
                    .ignoreExceptions()
                    .during(holdDuration)
                    .until(tracking(condition, last));
        } catch (ConditionTimeoutException e) {
            throw attachLast(e, last);
        }
    }

    // =================================================================
    // Assertion based wait
    // =================================================================

    /**
     * Polls an assertion until it completes without throwing.
     *
     * <p>
     * Useful when multiple values need to be validated together.
     *
     * <pre>
     * AwaitUtil.untilAsserted(
     *         "customer projection",
     *         () -> {
     *             assertEquals("ACTIVE", customer.getStatus());
     *             assertEquals(expectedVersion, customer.getVersion());
     *         }
     * );
     * </pre>
     */
    public static void untilAsserted(
            String description,
            ThrowingRunnable assertion) {

        untilAsserted(
                description,
                assertion,
                DEFAULT_TIMEOUT,
                DEFAULT_POLL_INTERVAL
        );
    }

    /**
     * Polls an assertion until it completes without throwing.
     *
     * @param description human-readable description
     * @param assertion assertion block
     * @param timeout maximum amount of time to wait
     * @param pollInterval interval between polling attempts
     */
    public static void untilAsserted(
            String description,
            ThrowingRunnable assertion,
            Duration timeout,
            Duration pollInterval) {

        requireArgs(
                description,
                assertion,
                timeout,
                pollInterval
        );

        AtomicReference<Throwable> last = new AtomicReference<>();
        ThrowingRunnable tracked = () -> {
            try {
                assertion.run();
            } catch (Throwable t) {
                last.set(t);
                throw t;
            }
        };

        try {
            Awaitility.await(description)
                    .atMost(timeout)
                    .pollDelay(Duration.ZERO)
                    .pollInterval(pollInterval)
                    .ignoreExceptions()
                    .untilAsserted(tracked);
        } catch (ConditionTimeoutException e) {
            throw attachLast(e, last);
        }
    }

    // =================================================================
    // Non-throwing variant
    // =================================================================

    /**
     * Polls a condition until it becomes true.
     *
     * <p>
     * Unlike {@link #waitUntil(String, Callable)}, this method returns
     * false instead of throwing when the timeout is reached.
     *
     * @return true if condition succeeds before timeout;
     *         false otherwise
     */
    public static boolean waitUntilQuietly(
            String description,
            Callable<Boolean> condition,
            Duration timeout,
            Duration pollInterval) {

        try {

            waitUntil(
                    description,
                    condition,
                    timeout,
                    pollInterval
            );

            return true;

        } catch (ConditionTimeoutException e) {

            return false;
        }
    }

    // =================================================================
    // Internal validation
    // =================================================================

    private static void requireArgs(
            String description,
            Object callable,
            Duration timeout,
            Duration pollInterval) {

        Objects.requireNonNull(
                description,
                "description must not be null"
        );

        Objects.requireNonNull(
                callable,
                "condition/supplier/assertion must not be null"
        );

        Objects.requireNonNull(
                timeout,
                "timeout must not be null"
        );

        Objects.requireNonNull(
                pollInterval,
                "pollInterval must not be null"
        );

        validateDuration(timeout, "timeout");
        validateDuration(pollInterval, "pollInterval");
    }

    /**
     * Wraps a callable so the last exception it threw is remembered. Because
     * {@code ignoreExceptions()} swallows them, this is the only way to tell
     * the caller WHY a wait timed out (auth error, bad query, NPE, ...).
     */
    private static <T> Callable<T> tracking(
            Callable<T> delegate,
            AtomicReference<Throwable> last) {

        return () -> {
            try {
                return delegate.call();
            } catch (Exception e) {
                last.set(e);
                throw e;
            }
        };
    }

    private static <T> Predicate<T> tracking(
            Predicate<? super T> delegate,
            AtomicReference<Throwable> last) {

        return value -> {
            try {
                return delegate.test(value);
            } catch (RuntimeException e) {
                last.set(e);
                throw e;
            }
        };
    }

    /** Adds the last swallowed exception (if any) to the timeout as a suppressed exception. */
    private static ConditionTimeoutException attachLast(
            ConditionTimeoutException timeout,
            AtomicReference<Throwable> last) {

        Throwable cause = last.get();
        if (cause != null) {
            timeout.addSuppressed(cause);
        }
        return timeout;
    }

    private static void validateDuration(
            Duration duration,
            String name) {

        if (duration.isNegative()
                || duration.isZero()) {

            throw new IllegalArgumentException(
                    name
                            + " must be positive, was "
                            + duration
            );
        }
    }
}