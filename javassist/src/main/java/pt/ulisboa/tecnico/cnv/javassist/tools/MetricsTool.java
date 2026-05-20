package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.CtClass;

public class MetricsTool extends AbstractJavassistTool {

    private static final ThreadLocal<Long> basicBlocks = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> instructions = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> loopIterations = ThreadLocal.withInitial(() -> 0L);

    public MetricsTool(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    public static void reset() {
        basicBlocks.set(0L);
        instructions.set(0L);
        loopIterations.set(0L);
    }

    public static Map<String, Long> getMetrics() {
        Map<String, Long> metrics = new HashMap<>();
        metrics.put("basicBlocks", basicBlocks.get());
        metrics.put("instructions", instructions.get());
        metrics.put("loopIterations", loopIterations.get());
        return metrics;
    }

    public static void cleanup() {
        basicBlocks.remove();
        instructions.remove();
        loopIterations.remove();
    }

    public static void incBasicBlock(int length) {
        basicBlocks.set(basicBlocks.get() + 1L);
        instructions.set(instructions.get() + length);
    }

    public static void incLoopIterations() {
        loopIterations.set(loopIterations.get() + 1L);
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

        System.out.println(String.format("[%s] Instrumented method: %s",
            MetricsTool.class.getSimpleName(), behavior.getLongName()));
        super.transform(behavior);
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        boolean isLoopBlock = isLoopHeader(block);

        String instrumentationCode = String.format("%s.incBasicBlock(%s);",
            MetricsTool.class.getName(), block.getLength());

        if (isLoopBlock) {
            instrumentationCode += String.format("%s.incLoopIterations();",
                MetricsTool.class.getName());
        }

        block.behavior.insertAt(block.line, instrumentationCode);
    }

    private boolean isLoopHeader(BasicBlock block) {
        for (int incomingPosition : block.entrances) {
            if (incomingPosition >= block.position) {
                return true;
            }
        }
        return false;
    }

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

        if (name.equals("equals") && paramCount == 1) {
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
        System.out.println(String.format("[Metrics] Thread=%s, Blocks=%d, Insts=%d, Loops=%d",
                Thread.currentThread().getId(),
                metrics.get("basicBlocks"),
                metrics.get("instructions"),
                metrics.get("loopIterations")));
    }
}
