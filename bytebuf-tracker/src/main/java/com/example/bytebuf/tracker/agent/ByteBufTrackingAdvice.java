package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.ObjectTrackerHandler;
import com.example.bytebuf.tracker.ObjectTrackerRegistry;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for tracking object flow through methods.
 * Originally designed for ByteBuf, but now supports any object via ObjectTrackerHandler.
 */
public class ByteBufTrackingAdvice {

    // Re-entrance guard to prevent infinite recursion when tracking code
    // triggers other instrumented methods
    // PUBLIC: Must be accessible from instrumented classes in different packages
    public static final ThreadLocal<Boolean> IS_TRACKING =
        ThreadLocal.withInitial(() -> false);

    /**
     * Method entry advice - tracks objects in parameters
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin Class<?> clazz,
            @Advice.Origin("#m") String methodName,
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
                        methodName,
                        metric
                    );
                }
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }

    /**
     * Method exit advice - tracks final state of objects in parameters
     * Note: Return value tracking removed for ByteBuddy 1.10.x compatibility (void method issues)
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.Origin Class<?> clazz,
            @Advice.Origin("#m") String methodName,
            @Advice.AllArguments Object[] arguments,
            @Advice.Thrown Throwable thrown) {

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
                        // Record the exit state (e.g., ByteBuf refCount after method completes)
                        tracker.recordMethodCall(
                            arg,
                            clazz.getSimpleName(),
                            methodName + "_exit",
                            metric
                        );
                    }
                }
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }
}
