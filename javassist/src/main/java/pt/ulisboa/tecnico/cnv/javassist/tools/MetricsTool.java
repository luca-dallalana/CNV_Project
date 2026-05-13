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
    private static final ThreadLocal<Long> methods = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> startTimeNanos = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> loopIterations = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> pixelUpdates = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> matrixUpdates = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> dnaComparisons = ThreadLocal.withInitial(() -> 0L);

    public MetricsTool(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    public static void reset() {
        basicBlocks.set(0L);
        instructions.set(0L);
        methods.set(0L);
        startTimeNanos.set(System.nanoTime());
        loopIterations.set(0L);
        pixelUpdates.set(0L);
        matrixUpdates.set(0L);
        dnaComparisons.set(0L);
    }

    public static Map<String, Long> getMetrics() {
        Map<String, Long> metrics = new HashMap<>();
        metrics.put("basicBlocks", basicBlocks.get());
        metrics.put("instructions", instructions.get());
        metrics.put("methods", methods.get());
        metrics.put("executionTimeNanos", System.nanoTime() - startTimeNanos.get());
        metrics.put("loopIterations", loopIterations.get());
        metrics.put("pixelUpdates", pixelUpdates.get());
        metrics.put("matrixUpdates", matrixUpdates.get());
        metrics.put("dnaComparisons", dnaComparisons.get());
        return metrics;
    }

    public static void cleanup() {
        basicBlocks.remove();
        instructions.remove();
        methods.remove();
        startTimeNanos.remove();
        loopIterations.remove();
        pixelUpdates.remove();
        matrixUpdates.remove();
        dnaComparisons.remove();
    }

    public static void incBasicBlock(int length) {
        basicBlocks.set(basicBlocks.get() + 1L);
        instructions.set(instructions.get() + length);
    }

    public static void incMethod() {
        methods.set(methods.get() + 1L);
    }

    public static void incLoopIterations() {
        loopIterations.set(loopIterations.get() + 1L);
    }

    public static void incPixelUpdates() {
        pixelUpdates.set(pixelUpdates.get() + 1L);
    }

    public static void incMatrixUpdates() {
        matrixUpdates.set(matrixUpdates.get() + 1L);
    }

    public static void incDnaComparisons() {
        dnaComparisons.set(dnaComparisons.get() + 1L);
    }

    @Override
    protected void transform(CtClass clazz) throws Exception {
        System.out.println(String.format("[%s] Instrumenting class: %s", MetricsTool.class.getSimpleName(), clazz.getName()));
        super.transform(clazz);
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        String className = behavior.getDeclaringClass().getName();
        String workload = workloadForClass(className);
        if (workload != null && (isHttpHandlerMethod(behavior) || isLambdaHandlerMethod(behavior))) {
            behavior.insertBefore(String.format("%s.reset();%s.incMethod();", MetricsTool.class.getName(), MetricsTool.class.getName()));
            behavior.insertAfter(buildRequestExitCode(workload, isHttpHandlerMethod(behavior)), true);
            super.transform(behavior);
            return;
        }

        if (!shouldInstrumentMethod(behavior)) {
            return;
        }

        behavior.insertBefore(String.format("%s.incMethod();", MetricsTool.class.getName()));
        System.out.println(String.format("[%s] Instrumented method: %s", MetricsTool.class.getSimpleName(), behavior.getLongName()));
        super.transform(behavior);
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        block.behavior.insertAt(block.line, String.format("%s.incBasicBlock(%s);", MetricsTool.class.getName(), block.getLength()));
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

    private static boolean isHttpHandlerMethod(CtBehavior behavior) throws Exception {
        if (!"handle".equals(behavior.getName())) {
            return false;
        }
        CtClass[] params = behavior.getParameterTypes();
        return params.length == 1 && "com.sun.net.httpserver.HttpExchange".equals(params[0].getName());
    }

    private static boolean isLambdaHandlerMethod(CtBehavior behavior) throws Exception {
        if (!"handleRequest".equals(behavior.getName())) {
            return false;
        }
        CtClass[] params = behavior.getParameterTypes();
        return params.length == 2 && "java.util.Map".equals(params[0].getName());
    }

    private static String workloadForClass(String className) {
        if ("pt.ulisboa.tecnico.cnv.dna.DnaHandler".equals(className)) {
            return "dna";
        }
        if ("pt.ulisboa.tecnico.cnv.fractals.FractalsHandler".equals(className)) {
            return "fractals";
        }
        if ("pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler".equals(className)) {
            return "grayscott";
        }
        return null;
    }

    private static String buildRequestExitCode(String workload, boolean isHttpHandler) {
        String paramsExpr = isHttpHandler
                ? "this.queryToMap(((com.sun.net.httpserver.HttpExchange)$1).getRequestURI().getRawQuery())"
                : "(java.util.Map)$1";

        StringBuilder code = new StringBuilder();
        code.append("java.util.Map params = ").append(paramsExpr).append(";");
        code.append(MetricsTool.class.getName()).append(".onRequestExit(\"").append(workload).append("\", params);");
        return code.toString();
    }

    public static void onRequestExit(String workload, Map<String, String> params) {
        Map<String, Long> metrics = getMetrics();
        System.out.println(String.format("[Metrics] Thread=%s, Blocks=%d, Insts=%d, Methods=%d, TimeNs=%d, Loops=%d, Pixels=%d, Matrix=%d, DnaComp=%d",
                Thread.currentThread().getId(),
                metrics.get("basicBlocks"),
                metrics.get("instructions"),
                metrics.get("methods"),
                metrics.get("executionTimeNanos"),
                metrics.get("loopIterations"),
                metrics.get("pixelUpdates"),
                metrics.get("matrixUpdates"),
                metrics.get("dnaComparisons")));
        pt.ulisboa.tecnico.cnv.mss.DynamoDbMetricsStore.store(workload, params, metrics);
        cleanup();
    }
}
