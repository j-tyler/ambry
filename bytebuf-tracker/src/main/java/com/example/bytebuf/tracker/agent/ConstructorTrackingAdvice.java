package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.ObjectTrackerHandler;
import com.example.bytebuf.tracker.ObjectTrackerRegistry;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy advice for tracking object flow through constructors.
 *
 * This is separate from ByteBufTrackingAdvice because constructors have special constraints:
 * - Cannot use onThrowable in @Advice.OnMethodExit (Java requires super() to be first)
 * - Exception handlers cannot exist before super() call completes
 * - JVM bytecode verifier rejects try/catch wrapping around super() calls
 *
 * This advice sacrifices exception tracking to enable constructor instrumentation.
 */
public class ConstructorTrackingAdvice {

    // Re-entrance guard to prevent infinite recursion when tracking code
    // triggers other instrumented constructors/methods
    // PUBLIC: Must be accessible from instrumented classes in different packages
    public static final ThreadLocal<Boolean> IS_TRACKING =
        ThreadLocal.withInitial(() -> false);

    /**
     * Constructor entry advice - tracks objects in parameters
     */
    @Advice.OnMethodEnter
    public static void onConstructorEnter(
            @Advice.Origin Class<?> clazz,
            @Advice.AllArguments Object[] arguments) {

        // Prevent re-entrant calls
        if (IS_TRACKING.get()) {
            return;
        }

        if (arguments == null || arguments.length == 0) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            for (Object arg : arguments) {
                if (handler.shouldTrack(arg)) {
                    int metric = handler.getMetric(arg);
                    tracker.recordMethodCall(
                        arg,
                        clazz.getSimpleName(),
                        "<init>",  // Constructor name in bytecode
                        metric
                    );
                }
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }

    /**
     * Constructor exit advice - tracks final state of objects in parameters
     *
     * NOTE: Does NOT use onThrowable or @Advice.Thrown because:
     * - Java constructors cannot have exception handlers before super() call
     * - ByteBuddy cannot inject try/catch around super() initialization
     * - This is a fundamental JVM bytecode constraint
     *
     * Trade-off: We don't track exceptions during construction, but we still
     * track parameter state before and after construction completes successfully.
     */
    @Advice.OnMethodExit  // No onThrowable parameter!
    public static void onConstructorExit(
            @Advice.Origin Class<?> clazz,
            @Advice.AllArguments Object[] arguments,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue) {  // No @Advice.Thrown parameter!

        // Prevent re-entrant calls
        if (IS_TRACKING.get()) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            // Check if any tracked objects in parameters have changed state
            if (arguments != null) {
                for (Object arg : arguments) {
                    if (handler.shouldTrack(arg)) {
                        int metric = handler.getMetric(arg);
                        // Record the exit state (e.g., ByteBuf refCount after constructor completes)
                        tracker.recordMethodCall(
                            arg,
                            clazz.getSimpleName(),
                            "<init>_exit",
                            metric
                        );
                    }
                }
            }

            // Track the newly constructed object if it's trackable
            // (e.g., if the constructor created a ByteBuf wrapper)
            if (handler.shouldTrack(returnValue)) {
                int metric = handler.getMetric(returnValue);
                tracker.recordMethodCall(
                    returnValue,
                    clazz.getSimpleName(),
                    "<init>_return",
                    metric
                );
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }
}
