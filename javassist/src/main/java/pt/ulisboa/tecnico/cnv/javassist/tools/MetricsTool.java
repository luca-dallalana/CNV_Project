package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.CtClass;

public class MetricsTool extends AbstractJavassistTool {

    // private static final ThreadLocal<Long> basicBlocks = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> instructions = ThreadLocal.withInitial(() -> 0L);
    // private static final ThreadLocal<Long> loopIterations = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> branches = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> methodCalls = ThreadLocal.withInitial(() -> 0L);

    public MetricsTool(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    public static void reset() {
        // basicBlocks.set(0L);
        instructions.set(0L);
        // loopIterations.set(0L);
        branches.set(0L);
        methodCalls.set(0L);
    }

    public static Map<String, Long> getMetrics() {
        Map<String, Long> metrics = new HashMap<>();
        // metrics.put("basicBlocks", basicBlocks.get());
        metrics.put("instructions", instructions.get());
        // metrics.put("loopIterations", loopIterations.get());
        metrics.put("branches", branches.get());
        metrics.put("methodCalls", methodCalls.get());
        return metrics;
    }

    public static void cleanup() {
        // basicBlocks.remove();
        instructions.remove();
        // loopIterations.remove();
        branches.remove();
        methodCalls.remove();
    }

    // public static void incBasicBlock(int length) {
    //     basicBlocks.set(basicBlocks.get() + 1L);
    //     instructions.set(instructions.get() + length);
    // }
    // public static void incLoopIterations() {
    //     loopIterations.set(loopIterations.get() + 1L);
    // }

    public static void incBlock(int length, boolean isBranch) {
        instructions.set(instructions.get() + length);
        if (isBranch) {
            branches.set(branches.get() + 1L);
        }
    }

    public static void incMethodCall() {
        methodCalls.set(methodCalls.get() + 1L);
    }

    @Override
    protected void transform(CtClass clazz) throws Exception {
        System.out.println(String.format("[%s] Instrumenting class: %s", MetricsTool.class.getSimpleName(), clazz.getName()));
        super.transform(clazz);
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        if (!shouldInstrumentMethod(behavior)) {
            return;
        }

        behavior.insertBefore(String.format("%s.incMethodCall();",
            MetricsTool.class.getName()));

        System.out.println(String.format("[%s] Instrumented method: %s",
            MetricsTool.class.getSimpleName(), behavior.getLongName()));

        super.transform(behavior);
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        block.behavior.insertAt(block.line, String.format("%s.incBlock(%d, %b);",
            MetricsTool.class.getName(), block.length, isBranchTarget(block)));
    }

    private boolean isBranchTarget(BasicBlock block) {
        return block.entrances.length > 1;
    }

    // private boolean isLoopHeader(BasicBlock block) {
    //     for (int incomingPosition : block.entrances) {
    //         if (incomingPosition >= block.position) {
    //             return true;
    //         }
    //     }
    //     return false;
    // }

    private static boolean shouldInstrumentMethod(CtBehavior behavior) throws Exception {
        String name = behavior.getName();
        int paramCount = behavior.getParameterTypes().length;

        if (name.equals("<init>") || name.equals("<clinit>")) {
            return false;
        }

        if (name.equals("toString") && paramCount == 0) {
            return false;
        }

        if (name.equals("hashCode") && paramCount == 0) {
            return false;
        }

        if (name.startsWith("get") && paramCount == 0) {
            return false;
        }

        if (name.startsWith("is") && paramCount == 0) {
            return false;
        }

        if (name.startsWith("set") && paramCount == 1) {
            return false;
        }

        return true;
    }

    public static void logMetrics() {
        Map<String, Long> metrics = getMetrics();
        System.out.println(String.format("[Metrics] Thread=%s, Instructions=%d, Branches=%d, Methods=%d",
                Thread.currentThread().getId(),
                metrics.get("instructions"),
                metrics.get("branches"),
                metrics.get("methodCalls")));
    }
}
