package io.agentguard.runtime;

import io.agentguard.core.GuardResult;
import io.agentguard.core.GuardStatus;
import io.agentguard.core.ToolCall;
import io.agentguard.core.ViolationType;
import io.agentguard.core.policy.LoopPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link LoopDetector} covering Issues #11–#14.
 *
 * <p>Tests are grouped by concern in nested classes for readability.
 */
class LoopDetectorTest {

    // ── Issue #11 — Sliding window of tool calls ──────────────────────────────

    @Nested
    class SlidingWindow {

        @Test
        void window_starts_empty() {
            LoopDetector detector = new LoopDetector(LoopPolicy.defaults());
            assertThat(detector.windowSize()).isZero();
        }

        @Test
        void each_evaluated_call_is_added_to_the_window() {
            LoopDetector detector = new LoopDetector(LoopPolicy.defaults());
            detector.evaluate(call("c1", "web_search", "q", "news"));
            detector.evaluate(call("c2", "read_file", "path", "/tmp/x"));
            assertThat(detector.windowSize()).isEqualTo(2);
        }

        @Test
        void window_is_bounded_to_policy_windowSize() {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(2)
                    .withinLastNCalls(5)
                    .build();
            LoopDetector detector = new LoopDetector(policy);

            // Push 10 distinct calls through
            for (int i = 0; i < 10; i++) {
                detector.evaluate(call("c" + i, "tool_" + i));
            }
            assertThat(detector.windowSize()).isEqualTo(5);
        }

        @Test
        void oldest_call_is_evicted_when_window_is_full() {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(3)
                    .withinLastNCalls(3)
                    .build();
            LoopDetector detector = new LoopDetector(policy);

            // Fill window with "alpha" calls
            detector.evaluate(call("c1", "alpha"));
            detector.evaluate(call("c2", "alpha"));
            detector.evaluate(call("c3", "alpha"));
            // Window is now [alpha, alpha, alpha] → 3 matches → BLOCKED

            // Now push a non-alpha call — it evicts the oldest alpha
            detector.reset();   // Start fresh for clarity
            detector.evaluate(call("c1", "other"));   // [other]
            detector.evaluate(call("c2", "alpha"));   // [other, alpha]
            detector.evaluate(call("c3", "alpha"));   // [other, alpha, alpha]
            // Push one more non-alpha — evicts "other", window = [alpha, alpha, new_other]
            detector.evaluate(call("c4", "other2"));  // [alpha, alpha, other2]
            // alpha count in window is now 2, not 3 → ALLOWED
            GuardResult result = detector.evaluate(call("c5", "other3")); // [alpha, alpha, other3, ??? — wait window=3, so [alpha, other2, other3]]
            // Let me re-check: after c4, window=[alpha,alpha,other2] (size=3, c1(other) evicted)
            // After c5, window=[alpha, other2, other3] (c2(alpha) evicted)
            // alpha count = 1 → ALLOWED
            assertThat(result.isAllowed()).isTrue();
        }

        @Test
        void reset_clears_the_window() {
            LoopDetector detector = new LoopDetector(LoopPolicy.defaults());
            detector.evaluate(call("c1", "web_search", "q", "x"));
            detector.evaluate(call("c2", "web_search", "q", "x"));
            assertThat(detector.windowSize()).isEqualTo(2);

            detector.reset();
            assertThat(detector.windowSize()).isZero();
        }
    }

    // ── Issue #12 — Exact repetition detection ────────────────────────────────

    @Nested
    class ExactRepetition {

        @Test
        void distinct_calls_are_never_blocked() {
            LoopDetector detector = new LoopDetector(LoopPolicy.defaults());
            for (int i = 0; i < 20; i++) {
                GuardResult r = detector.evaluate(call("c" + i, "web_search", "q", "query" + i));
                assertThat(r.isAllowed())
                        .as("call %d should be allowed", i)
                        .isTrue();
            }
        }

        @Test
        void exact_repeat_at_max_repeats_is_blocked() {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(3)
                    .withinLastNCalls(10)
                    .backoffBeforeInterrupt(false)
                    .build();
            LoopDetector detector = new LoopDetector(policy);

            detector.evaluate(call("c1", "web_search", "q", "weather"));
            detector.evaluate(call("c2", "web_search", "q", "weather"));
            GuardResult third = detector.evaluate(call("c3", "web_search", "q", "weather"));

            assertThat(third.wasBlocked()).isTrue();
            assertThat(third.violation()).contains(ViolationType.LOOP_DETECTED);
            assertThat(third.toolName()).contains("web_search");
            assertThat(third.blockReason().orElse(""))
                    .contains("web_search")
                    .contains("3");
        }

        @Test
        void interleaved_different_tools_do_not_trigger_each_other() {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(3).withinLastNCalls(10).backoffBeforeInterrupt(false).build();
            LoopDetector detector = new LoopDetector(policy);

            // Alternate between two tools — 2 iterations each stays below the 3-repeat threshold
            for (int i = 0; i < 2; i++) {
                assertThat(detector.evaluate(call("a" + i, "tool_a"))).matches(GuardResult::isAllowed);
                assertThat(detector.evaluate(call("b" + i, "tool_b"))).matches(GuardResult::isAllowed);
            }
        }

        @Test
        void same_tool_different_args_is_not_a_loop() {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(3).withinLastNCalls(10).backoffBeforeInterrupt(false).build();
            LoopDetector detector = new LoopDetector(policy);

            // Same tool, different queries — must all be ALLOWED
            for (int i = 0; i < 10; i++) {
                GuardResult r = detector.evaluate(call("c" + i, "web_search", "q", "city_" + i));
                assertThat(r.isAllowed()).as("different-arg call %d must be allowed", i).isTrue();
            }
        }

        @Test
        void exact_match_requires_all_args_to_be_identical() {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(3).withinLastNCalls(10).backoffBeforeInterrupt(false).build();
            LoopDetector detector = new LoopDetector(policy);

            // call1 and call2 same tool+args, call3 has an extra arg → NOT exact match
            detector.evaluate(call("c1", "search", "q", "hello"));
            detector.evaluate(call("c2", "search", "q", "hello"));
            GuardResult r = detector.evaluate(
                    ToolCall.of("c3", "search", Map.of("q", "hello", "lang", "en")));
            assertThat(r.isAllowed()).isTrue();
        }

        @Test
        void block_carries_window_size_in_reason() {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(2).withinLastNCalls(7).backoffBeforeInterrupt(false).build();
            LoopDetector detector = new LoopDetector(policy);
            detector.evaluate(call("c1", "ping"));
            GuardResult blocked = detector.evaluate(call("c2", "ping"));
            assertThat(blocked.blockReason().orElse("")).contains("7"); // windowSize in message
        }
    }

    // ── Issue #13 — Semantic repetition detection ─────────────────────────────

    @Nested
    class SemanticRepetition {

        private LoopPolicy semanticPolicy;

        @BeforeEach
        void setUp() {
            semanticPolicy = LoopPolicy.builder()
                    .maxRepeats(3)
                    .withinLastNCalls(10)
                    .backoffBeforeInterrupt(false)
                    .semanticDetection(true)
                    .build();
        }

        @Test
        void different_casing_is_detected_as_semantic_repeat() {
            LoopDetector detector = new LoopDetector(semanticPolicy);

            detector.evaluate(call("c1", "web_search", "q", "weather lisbon"));
            detector.evaluate(call("c2", "web_search", "q", "Weather Lisbon"));
            GuardResult third = detector.evaluate(call("c3", "web_search", "q", "WEATHER LISBON"));

            assertThat(third.wasBlocked()).isTrue();
            assertThat(third.violation()).contains(ViolationType.LOOP_DETECTED);
            assertThat(third.blockReason().orElse("")).contains("semantic");
        }

        @Test
        void extra_whitespace_is_normalised_in_semantic_key() {
            LoopDetector detector = new LoopDetector(semanticPolicy);

            detector.evaluate(call("c1", "web_search", "q", "weather  lisbon"));
            detector.evaluate(call("c2", "web_search", "q", "  weather lisbon  "));
            GuardResult third = detector.evaluate(call("c3", "web_search", "q", "weather lisbon"));

            assertThat(third.wasBlocked()).isTrue();
        }

        @Test
        void exact_detection_still_works_with_semantic_enabled() {
            LoopDetector detector = new LoopDetector(semanticPolicy);

            detector.evaluate(call("c1", "ping", "host", "localhost"));
            detector.evaluate(call("c2", "ping", "host", "localhost"));
            GuardResult third = detector.evaluate(call("c3", "ping", "host", "localhost"));

            assertThat(third.wasBlocked()).isTrue();
        }

        @Test
        void truly_different_queries_are_not_flagged_as_semantic_repeat() {
            LoopDetector detector = new LoopDetector(semanticPolicy);

            // Completely different content → not a loop
            detector.evaluate(call("c1", "web_search", "q", "weather lisbon"));
            detector.evaluate(call("c2", "web_search", "q", "stock prices nyc"));
            GuardResult third = detector.evaluate(call("c3", "web_search", "q", "recipe pasta"));

            assertThat(third.isAllowed()).isTrue();
        }

        @Test
        void semantic_detection_disabled_by_default() {
            // Default LoopPolicy has semanticDetectionEnabled=false
            LoopPolicy exact = LoopPolicy.builder()
                    .maxRepeats(3).withinLastNCalls(10).backoffBeforeInterrupt(false).build();
            assertThat(exact.semanticDetectionEnabled()).isFalse();

            LoopDetector detector = new LoopDetector(exact);
            detector.evaluate(call("c1", "search", "q", "hello"));
            detector.evaluate(call("c2", "search", "q", "Hello"));   // differs only in case
            GuardResult third = detector.evaluate(call("c3", "search", "q", "HELLO"));

            // Without semantic detection these are 3 different exact keys → ALLOWED
            assertThat(third.isAllowed()).isTrue();
        }
    }

    // ── Issue #14 — Backoff before interrupt ──────────────────────────────────

    @Nested
    class BackoffBehavior {

        @Test
        void at_maxRepeats_minus_1_call_is_still_allowed() {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(3)
                    .withinLastNCalls(10)
                    .backoffBeforeInterrupt(true)
                    .build();
            LoopDetector detector = new LoopDetector(policy);

            detector.evaluate(call("c1", "tool_x", "a", "1"));
            // Second call: count=2, which is maxRepeats-1 → warning, still ALLOWED
            GuardResult second = detector.evaluate(call("c2", "tool_x", "a", "1"));
            assertThat(second.isAllowed())
                    .as("Second call should be allowed (backoff warning, not block)")
                    .isTrue();
        }

        @Test
        void at_maxRepeats_call_is_blocked_regardless_of_backoff_flag() {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(3).withinLastNCalls(10).backoffBeforeInterrupt(true).build();
            LoopDetector detector = new LoopDetector(policy);

            detector.evaluate(call("c1", "tool_x", "a", "1"));
            detector.evaluate(call("c2", "tool_x", "a", "1"));  // backoff warn
            GuardResult third = detector.evaluate(call("c3", "tool_x", "a", "1")); // BLOCK

            assertThat(third.wasBlocked()).isTrue();
            assertThat(third.violation()).contains(ViolationType.LOOP_DETECTED);
        }

        @Test
        void without_backoff_block_fires_exactly_at_maxRepeats() {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(3).withinLastNCalls(10).backoffBeforeInterrupt(false).build();
            LoopDetector detector = new LoopDetector(policy);

            assertThat(detector.evaluate(call("c1", "t", "k", "v"))).matches(GuardResult::isAllowed);
            assertThat(detector.evaluate(call("c2", "t", "k", "v"))).matches(GuardResult::isAllowed);
            assertThat(detector.evaluate(call("c3", "t", "k", "v"))).matches(GuardResult::wasBlocked);
        }

        @Test
        void backoff_warning_is_only_logged_once_per_key() {
            // We can't easily capture log output, but we can verify the guard
            // doesn't change its ALLOWED decision after the warning is issued
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(4).withinLastNCalls(20).backoffBeforeInterrupt(true).build();
            LoopDetector detector = new LoopDetector(policy);

            // Fill up to warning threshold (count=3, maxRepeats-1=3)
            detector.evaluate(call("c1", "search", "q", "x"));
            detector.evaluate(call("c2", "search", "q", "x"));
            GuardResult atWarning = detector.evaluate(call("c3", "search", "q", "x"));
            assertThat(atWarning.isAllowed()).isTrue();  // warning, but allowed

            // 4th call should block (no extra grace period)
            GuardResult blocked = detector.evaluate(call("c4", "search", "q", "x"));
            assertThat(blocked.wasBlocked()).isTrue();
        }

        @Test
        void reset_clears_backoff_warn_set_so_warning_can_fire_again() {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(3).withinLastNCalls(10).backoffBeforeInterrupt(true).build();
            LoopDetector detector = new LoopDetector(policy);

            // Trigger warning, then reset
            detector.evaluate(call("c1", "t", "k", "v"));
            detector.evaluate(call("c2", "t", "k", "v")); // warning issued
            detector.reset();

            // After reset, two more calls should reach warning state again without blocking
            detector.evaluate(call("c3", "t", "k", "v"));
            GuardResult r = detector.evaluate(call("c4", "t", "k", "v")); // warning again
            assertThat(r.isAllowed()).isTrue();
        }
    }

    // ── LoopPolicy.disabled() ─────────────────────────────────────────────────

    @Nested
    class DisabledPolicy {

        @Test
        void disabled_policy_never_blocks_any_call() {
            LoopDetector detector = new LoopDetector(LoopPolicy.disabled());
            for (int i = 0; i < 100; i++) {
                GuardResult r = detector.evaluate(call("c" + i, "same_tool", "k", "same_value"));
                assertThat(r.isAllowed()).as("call %d must be allowed with disabled policy", i).isTrue();
            }
        }
    }

    // ── Thread safety ─────────────────────────────────────────────────────────

    @Nested
    class ThreadSafety {

        @Test
        void concurrent_evaluations_do_not_throw() throws InterruptedException {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(5).withinLastNCalls(20).backoffBeforeInterrupt(false).build();
            LoopDetector detector = new LoopDetector(policy);
            int threads = 8;
            int callsPerThread = 50;

            ExecutorService exec = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final int tid = t;
                exec.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < callsPerThread; i++) {
                            // Mix of identical and distinct calls
                            String query = (i % 3 == 0) ? "same" : "distinct_" + tid + "_" + i;
                            detector.evaluate(call("t" + tid + "c" + i, "search", "q", query));
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }

            start.countDown();
            exec.shutdown();
            assertThat(exec.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(errors.get()).isZero();
        }

        @Test
        void window_never_exceeds_policy_size_under_contention() throws InterruptedException {
            LoopPolicy policy = LoopPolicy.builder()
                    .maxRepeats(2).withinLastNCalls(5).backoffBeforeInterrupt(false).build();
            LoopDetector detector = new LoopDetector(policy);
            int threads = 10;

            ExecutorService exec = Executors.newFixedThreadPool(threads);
            CountDownLatch done = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                final int tid = t;
                exec.submit(() -> {
                    for (int i = 0; i < 20; i++) {
                        detector.evaluate(call("t" + tid + "i" + i, "tool_" + (i % 3)));
                    }
                    done.countDown();
                });
            }

            done.await(10, TimeUnit.SECONDS);
            assertThat(detector.windowSize())
                    .as("window must never exceed policy.windowSize()")
                    .isLessThanOrEqualTo(5);
            exec.shutdown();
        }
    }

    // ── Integration: wired via AgentGuard.builder() ───────────────────────────

    @Nested
    class Integration {

        @Test
        void loop_detector_is_active_via_builder() {
            var guard = io.agentguard.core.AgentGuard.builder()
                    .loopDetection(LoopPolicy.builder()
                            .maxRepeats(2).withinLastNCalls(5).backoffBeforeInterrupt(false).build())
                    .build();

            guard.startRun("run-loop-test");
            ToolCall repeated = call("c1", "web_search", "q", "test");

            GuardResult first = guard.evaluateToolCall(repeated);
            GuardResult second = guard.evaluateToolCall(repeated);

            assertThat(first.isAllowed()).isTrue();
            assertThat(second.wasBlocked()).isTrue();
            assertThat(second.violation()).contains(ViolationType.LOOP_DETECTED);
        }

        @Test
        void startRun_resets_loop_detector_state() {
            var guard = io.agentguard.core.AgentGuard.builder()
                    .loopDetection(LoopPolicy.builder()
                            .maxRepeats(2).withinLastNCalls(5).backoffBeforeInterrupt(false).build())
                    .build();

            // First run: trigger the block
            guard.startRun("run-1");
            guard.evaluateToolCall(call("c1", "tool_a", "k", "v"));
            GuardResult blocked = guard.evaluateToolCall(call("c2", "tool_a", "k", "v"));
            assertThat(blocked.wasBlocked()).isTrue();

            // Second run: window is cleared, same calls must be allowed again
            guard.startRun("run-2");
            GuardResult freshFirst = guard.evaluateToolCall(call("c3", "tool_a", "k", "v"));
            GuardResult freshSecond = guard.evaluateToolCall(call("c4", "tool_a", "k", "v"));
            assertThat(freshFirst.isAllowed()).isTrue();
            assertThat(freshSecond.wasBlocked()).isTrue(); // hits limit again
        }

        @Test
        void readme_example_behavior_matches_specification() {
            // From the README:
            // tool_call("web_search", "weather in Lisbon") ← call 1  OK
            // tool_call("web_search", "weather in Lisbon") ← call 2  OK
            // tool_call("web_search", "weather in Lisbon") ← call 3  INTERRUPT. Loop detected.
            var guard = io.agentguard.core.AgentGuard.builder()
                    .loopDetection(LoopPolicy.builder()
                            .maxRepeats(3).withinLastNCalls(10).backoffBeforeInterrupt(false).build())
                    .build();
            guard.startRun("readme-test");

            ToolCall c = call("id", "web_search", "query", "weather in Lisbon");
            assertThat(guard.evaluateToolCall(c).isAllowed()).isTrue();  // call 1
            assertThat(guard.evaluateToolCall(c).isAllowed()).isTrue();  // call 2
            assertThat(guard.evaluateToolCall(c).wasBlocked()).isTrue(); // call 3 → BLOCKED
        }
    }

    // ── #1 fix — Concurrent run isolation ────────────────────────────────────

    @Nested
    class ConcurrentRunIsolation {

        @Test
        void two_runs_have_independent_windows() {
            LoopDetector detector = new LoopDetector(
                    LoopPolicy.maxRepeats(2).withinLastNCalls(5).build());

            ToolCall callWithRun1 = ToolCall.builder("c1", "tool_a")
                    .context(io.agentguard.core.policy.ExecutionContext.builder()
                            .runId("run-A").build())
                    .build();
            ToolCall callWithRun2 = ToolCall.builder("c2", "tool_a")
                    .context(io.agentguard.core.policy.ExecutionContext.builder()
                            .runId("run-B").build())
                    .build();

            detector.initRun("run-A");
            detector.initRun("run-B");

            // run-A: two identical calls → blocked
            detector.evaluate(callWithRun1);
            GuardResult run1Blocked = detector.evaluate(callWithRun1);
            assertThat(run1Blocked.wasBlocked()).isTrue();

            // run-B: still only has zero calls in its own window → allowed
            GuardResult run2First = detector.evaluate(callWithRun2);
            assertThat(run2First.isAllowed()).isTrue();
        }

        @Test
        void startRun_on_guard_isolates_state_per_run_id() {
            var guard = io.agentguard.core.AgentGuard.builder()
                    .loopDetection(LoopPolicy.maxRepeats(2).withinLastNCalls(5).build())
                    .build();

            // run-1 triggers a block
            guard.startRun("run-1");
            ToolCall tc = call("c1", "search", "q", "same");
            guard.evaluateToolCall(tc);
            GuardResult blocked = guard.evaluateToolCall(call("c2", "search", "q", "same"));
            assertThat(blocked.wasBlocked()).isTrue();

            // run-2 is completely independent
            guard.startRun("run-2");
            GuardResult fresh = guard.evaluateToolCall(call("c3", "search", "q", "same"));
            assertThat(fresh.isAllowed()).isTrue();
        }

        @Test
        void concurrent_runs_do_not_cross_contaminate() throws Exception {
            LoopDetector detector = new LoopDetector(
                    LoopPolicy.maxRepeats(3).withinLastNCalls(10).build());

            int numRuns = 8;
            int callsEach = 2;  // below maxRepeats → all should be allowed
            var latch = new java.util.concurrent.CountDownLatch(numRuns);
            var blocked = new java.util.concurrent.atomic.AtomicInteger(0);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(numRuns);

            for (int r = 0; r < numRuns; r++) {
                final String runId = "run-" + r;
                detector.initRun(runId);
                executor.submit(() -> {
                    try {
                        for (int c = 0; c < callsEach; c++) {
                            ToolCall tc = ToolCall.builder("id-" + runId + "-" + c, "tool")
                                    .context(io.agentguard.core.policy.ExecutionContext.builder()
                                            .runId(runId).build())
                                    .build();
                            GuardResult r2 = detector.evaluate(tc);
                            if (r2.wasBlocked()) blocked.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            executor.shutdown();
            // No run should be blocked — each has only callsEach < maxRepeats calls
            assertThat(blocked.get()).isZero();
        }

        @Test
        void evictRun_releases_state() {
            LoopDetector detector = new LoopDetector(LoopPolicy.defaults());
            detector.initRun("ephemeral-run");

            ToolCall tc = ToolCall.builder("c1", "tool")
                    .context(io.agentguard.core.policy.ExecutionContext.builder()
                            .runId("ephemeral-run").build())
                    .build();
            detector.evaluate(tc);
            assertThat(detector.windowSnapshot("ephemeral-run")).hasSize(1);

            detector.evictRun("ephemeral-run");
            assertThat(detector.windowSnapshot("ephemeral-run")).isEmpty();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ToolCall call(String id, String toolName) {
        return ToolCall.of(id, toolName);
    }

    private static ToolCall call(String id, String toolName, String argKey, Object argVal) {
        return ToolCall.of(id, toolName, Map.of(argKey, argVal));
    }
}
