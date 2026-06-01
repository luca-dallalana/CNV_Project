package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class AutoScaler {
    private final LbConfig config;
    private final WorkerDiscovery workerDiscovery;
    private final WorkerRegistry workerRegistry;
    private final Ec2WorkerDiscovery ec2Discovery;
    private final CloudWatchMetricsPoller cpuPoller;
    private final WorkerHttpClient workerHttpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile long lastScalingActionAt = 0L;
    private static final int SUSTAINED_TICKS = 3;
    // Only accessed from the single scheduler thread — no synchronization needed.
    private int highPressureTicks = 0;
    private int lowPressureTicks = 0;

    public AutoScaler(
            LbConfig config,
            WorkerDiscovery workerDiscovery,
            WorkerRegistry workerRegistry,
            Ec2WorkerDiscovery ec2Discovery,
            CloudWatchMetricsPoller cpuPoller,
            WorkerHttpClient workerHttpClient) {
        this.config = config;
        this.workerDiscovery = workerDiscovery;
        this.workerRegistry = workerRegistry;
        this.ec2Discovery = ec2Discovery;
        this.cpuPoller = cpuPoller;
        this.workerHttpClient = workerHttpClient;
    }

    public void start() {
        long periodMillis = config.getScalerPeriod().toMillis();
        scheduler.scheduleAtFixedRate(this::tick, 0L, periodMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void tick() {
        try {
            List<WorkerNode> discoveredWorkers = workerDiscovery.discoverWorkers();
            List<WorkerNode> readyWorkers = discoveredWorkers.stream()
                    .filter(w -> workerHttpClient.probe(w))
                    .collect(Collectors.toList());
            workerRegistry.refresh(readyWorkers);

            if (config.usesStaticWorkers() || ec2Discovery == null) {
                return;
            }

            // Terminate any draining workers that have finished their in-flight requests.
            // Runs before the cooldown check so termination is not delayed by it.
            for (WorkerNode worker : workerRegistry.allNodes()) {
                if (worker.isDraining()
                        && worker.getInflightRequests() == 0
                        && worker.getEstimatedQueuedWork() == 0
                        && !worker.getInstanceId().startsWith("static-")) {
                    System.out.println("[AS] Terminating drained worker: " + worker.getInstanceId());
                    ec2Discovery.scaleInOne(worker.getInstanceId());
                    return;
                }
            }

            int activeWorkers = workerRegistry.activeWorkerCount();
            if (activeWorkers <= 0) {
                if (ec2Discovery != null && workerRegistry.workerCount() == 0) {
                    long now = System.currentTimeMillis();
                    if ((now - lastScalingActionAt) >= config.getScalerCooldown().toMillis()) {
                        System.out.println("[AS] No workers in registry — launching initial worker.");
                        ec2Discovery.scaleOutOne();
                        lastScalingActionAt = now;
                    }
                }
                return;
            }

            long pressure = workerRegistry.totalQueuedWork() / activeWorkers;

            List<String> activeIds = workerRegistry.allNodes().stream()
                    .filter(w -> !w.isDraining() && !w.getInstanceId().startsWith("static-"))
                    .map(WorkerNode::getInstanceId)
                    .collect(Collectors.toList());
            OptionalDouble avgCpu = (cpuPoller != null && !activeIds.isEmpty())
                    ? cpuPoller.getAverageCpuPercent(activeIds)
                    : OptionalDouble.empty();

            boolean cpuHigh = avgCpu.isPresent() && avgCpu.getAsDouble() > config.getCpuScaleOutThreshold();
            boolean cpuLow  = !avgCpu.isPresent() || avgCpu.getAsDouble() < config.getCpuScaleInThreshold();

            System.out.println(String.format("[AS] pressure=%d, avgCpu=%s",
                    pressure, avgCpu.isPresent() ? String.format("%.1f%%", avgCpu.getAsDouble()) : "n/a"));

            if (pressure > config.getScaleOutPressure() || cpuHigh) {
                highPressureTicks++;
                lowPressureTicks = 0;
            } else if (pressure < config.getScaleInPressure() && cpuLow) {
                lowPressureTicks++;
                highPressureTicks = 0;
            } else {
                highPressureTicks = 0;
                lowPressureTicks = 0;
            }

            long now = System.currentTimeMillis();
            if ((now - lastScalingActionAt) < config.getScalerCooldown().toMillis()) {
                return;
            }

            if (highPressureTicks >= SUSTAINED_TICKS && activeWorkers < config.getMaxWorkers()) {
                System.out.println("[AS] Scale-out: sustained pressure=" + pressure + " ticks=" + highPressureTicks);
                ec2Discovery.scaleOutOne();
                lastScalingActionAt = now;
                highPressureTicks = 0;
                return;
            }

            if (lowPressureTicks >= SUSTAINED_TICKS && activeWorkers > config.getMinWorkers()) {
                WorkerNode candidate = workerRegistry.chooseIdleTerminationCandidate();
                if (candidate != null && !candidate.getInstanceId().startsWith("static-")) {
                    System.out.println("[AS] Draining worker: " + candidate.getInstanceId());
                    candidate.setDraining(true);
                    lastScalingActionAt = now;
                    lowPressureTicks = 0;
                }
            }
        } catch (Exception e) {
            System.out.println("[AS] Tick failed: " + e.getMessage());
        }
    }
}
