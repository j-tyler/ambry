package com.example.bytebuf.tracker.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Java Agent for ByteBuf flow tracking.
 * Instruments methods to track ByteBuf movement through the application.
 */
public class ByteBufFlowAgent {
    
    /**
     * Premain method for Java agent
     *
     * @param arguments Agent arguments in format: include=pkg1,pkg2;exclude=pkg3,pkg4;trackConstructors=class1,class2
     * @param inst Instrumentation instance
     */
    public static void premain(String arguments, Instrumentation inst) {
        AgentConfig config = AgentConfig.parse(arguments);

        System.out.println("[ByteBufFlowAgent] Starting with config: " + config);

        // Setup JMX MBean for monitoring
        setupJmxMonitoring();

        // Setup shutdown hook for final report
        setupShutdownHook();

        // Build the agent with chained transformers
        AgentBuilder.Identified.Extendable agentBuilder = new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new AgentBuilder.Listener.StreamWriting(System.out).withTransformationsOnly())
            .ignore(
                nameStartsWith("net.bytebuddy.")
                .or(nameStartsWith("java."))
                .or(nameStartsWith("sun."))
                .or(nameStartsWith("com.sun."))
                .or(nameStartsWith("jdk."))
                .or(nameStartsWith("com.example.bytebuf.tracker.")) // Don't instrument ourselves
                .or(isSynthetic()) // Don't instrument compiler-generated classes
            )
            // Transform regular methods (non-constructors)
            // Filter out interfaces and abstract classes (can't instrument abstract methods)
            .type(config.getTypeMatcher()
                .and(not(isInterface()))
                .and(not(isAbstract())))
            .transform(new ByteBufTransformer());

        // Add constructor tracking for specified classes
        if (!config.getConstructorTrackingClasses().isEmpty()) {
            System.out.println("[ByteBufFlowAgent] Constructor tracking enabled for: " +
                config.getConstructorTrackingClasses());
            agentBuilder = agentBuilder
                .type(config.getConstructorTrackingMatcher()
                    .and(not(isInterface()))
                    .and(not(isAbstract())))
                .transform(new ConstructorTrackingTransformer());
        }

        agentBuilder.installOn(inst);

        System.out.println("[ByteBufFlowAgent] Instrumentation installed successfully");
    }
    
    /**
     * Transformer that applies advice to methods
     * Only instruments methods that have ByteBuf parameters or return values
     */
    static class ByteBufTransformer implements AgentBuilder.Transformer {
        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                ProtectionDomain protectionDomain) {

            return builder
                .method(
                    // Match methods that might handle ByteBufs (including static methods)
                    // Skip abstract methods (they have no bytecode to instrument)
                    isPublic()
                    .or(isProtected())
                    .and(not(isConstructor()))
                    .and(not(isAbstract()))
                    // CRITICAL: Only instrument methods that have ByteBuf in signature
                    .and(hasByteBufInSignature())
                )
                .intercept(Advice.to(ByteBufTrackingAdvice.class));
        }
    }

    /**
     * Matcher that checks if a method has ByteBuf in its signature
     * (parameters or return type)
     */
    private static ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> hasByteBufInSignature() {
        return new ElementMatcher.Junction.AbstractBase<net.bytebuddy.description.method.MethodDescription>() {
            @Override
            public boolean matches(net.bytebuddy.description.method.MethodDescription method) {
                // Check return type
                TypeDescription returnType = method.getReturnType().asErasure();
                if (isByteBufType(returnType)) {
                    return true;
                }

                // Check all parameters
                for (TypeDescription.Generic paramType : method.getParameters().asTypeList()) {
                    if (isByteBufType(paramType.asErasure())) {
                        return true;
                    }
                }

                return false;
            }

            private boolean isByteBufType(TypeDescription type) {
                String typeName = type.getName();
                // Check for ByteBuf or any subclass
                return typeName.equals("io.netty.buffer.ByteBuf") ||
                       typeName.startsWith("io.netty.buffer.") && type.getInterfaces().stream()
                           .anyMatch(iface -> iface.asErasure().getName().equals("io.netty.buffer.ByteBuf"));
            }
        };
    }

    /**
     * Transformer that applies advice to constructors for specified classes
     * Uses ConstructorTrackingAdvice which doesn't use onThrowable to avoid
     * "Cannot catch exception during constructor call" errors
     */
    static class ConstructorTrackingTransformer implements AgentBuilder.Transformer {
        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                ProtectionDomain protectionDomain) {

            return builder
                .constructor(
                    // Match public and protected constructors
                    isPublic().or(isProtected())
                )
                .intercept(Advice.to(ConstructorTrackingAdvice.class));
        }
    }
    
    /**
     * Setup JMX MBean for runtime monitoring
     */
    private static void setupJmxMonitoring() {
        try {
            javax.management.MBeanServer mbs = 
                java.lang.management.ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName name = 
                new javax.management.ObjectName("com.example:type=ByteBufFlowTracker");
            mbs.registerMBean(new ByteBufFlowMBean(), name);
            System.out.println("[ByteBufFlowAgent] JMX MBean registered");
        } catch (Exception e) {
            System.err.println("[ByteBufFlowAgent] Failed to register JMX MBean: " + e);
        }
    }
    
    /**
     * Setup shutdown hook to output final report
     */
    private static void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n=== ByteBuf Flow Final Report ===");
            ByteBufFlowReporter reporter = new ByteBufFlowReporter();
            System.out.println(reporter.generateReport());
        }));
    }
}

/**
 * Configuration for the agent
 */
class AgentConfig {
    private final Set<String> includePackages;
    private final Set<String> excludePackages;
    private final Set<String> constructorTrackingClasses;

    private AgentConfig(Set<String> includePackages, Set<String> excludePackages,
                        Set<String> constructorTrackingClasses) {
        this.includePackages = includePackages;
        this.excludePackages = excludePackages;
        this.constructorTrackingClasses = constructorTrackingClasses;
    }

    /**
     * Parse agent arguments
     * Format: include=com.example,com.myapp;exclude=com.example.legacy;trackConstructors=com.example.Message,com.example.Request
     */
    public static AgentConfig parse(String arguments) {
        Set<String> include = new HashSet<>();
        Set<String> exclude = new HashSet<>();
        Set<String> trackConstructors = new HashSet<>();

        if (arguments != null && !arguments.isEmpty()) {
            String[] parts = arguments.split(";");
            for (String part : parts) {
                String[] kv = part.split("=", 2); // Use limit=2 to handle class names with = in them
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim();

                    if ("include".equals(key)) {
                        include.addAll(Arrays.asList(value.split(",")));
                    } else if ("exclude".equals(key)) {
                        exclude.addAll(Arrays.asList(value.split(",")));
                    } else if ("trackConstructors".equals(key)) {
                        // Parse class names, trimming whitespace
                        for (String className : value.split(",")) {
                            trackConstructors.add(className.trim());
                        }
                    }
                }
            }
        }

        // Default to common application packages if none specified
        if (include.isEmpty()) {
            include.add("com.");
            include.add("org.");
            include.add("net.");
        }

        return new AgentConfig(include, exclude, trackConstructors);
    }

    /**
     * Get type matcher based on configuration
     */
    @SuppressWarnings("unchecked")
    public ElementMatcher.Junction<TypeDescription> getTypeMatcher() {
        ElementMatcher.Junction<TypeDescription> matcher = none();

        // Include packages
        for (String pkg : includePackages) {
            matcher = matcher.or(nameStartsWith(pkg));
        }

        // Exclude packages
        for (String pkg : excludePackages) {
            matcher = matcher.and(not(nameStartsWith(pkg)));
        }

        return matcher;
    }

    /**
     * Get matcher for classes that should have constructor tracking
     */
    @SuppressWarnings("unchecked")
    public ElementMatcher.Junction<TypeDescription> getConstructorTrackingMatcher() {
        ElementMatcher.Junction<TypeDescription> matcher = none();

        for (String className : constructorTrackingClasses) {
            // Support both exact matches and pattern matches
            if (className.endsWith("*")) {
                // Wildcard pattern: com.example.* matches all classes in package
                String prefix = className.substring(0, className.length() - 1);
                matcher = matcher.or(nameStartsWith(prefix));
            } else {
                // Exact match
                matcher = matcher.or(named(className));
            }
        }

        return matcher;
    }

    /**
     * Get the set of classes configured for constructor tracking
     */
    public Set<String> getConstructorTrackingClasses() {
        return constructorTrackingClasses;
    }

    @Override
    public String toString() {
        return "AgentConfig{include=" + includePackages +
               ", exclude=" + excludePackages +
               ", trackConstructors=" + constructorTrackingClasses + "}";
    }
}
