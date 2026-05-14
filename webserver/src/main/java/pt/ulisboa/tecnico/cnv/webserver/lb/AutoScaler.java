package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AutoScaler {
    private final LbConfig config;
    private final WorkerDiscovery workerDiscovery;
    private final WorkerRegistry workerRegistry;
    private final Ec2WorkerDiscovery ec2Discovery;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile long lastScalingActionAt = 0L;

    public AutoScaler(
            LbConfig config,
            WorkerDiscovery workerDiscovery,
            WorkerRegistry workerRegistry,
            Ec2WorkerDiscovery ec2Discovery) {
        this.config = config;
        this.workerDiscovery = workerDiscovery;
        this.workerRegistry = workerRegistry;
        this.ec2Discovery = ec2Discovery;
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
            workerRegistry.refresh(discoveredWorkers);

            if (config.usesStaticWorkers() || ec2Discovery == null) {
                return;
            }

            int healthyWorkers = workerRegistry.workerCount();
            if (healthyWorkers <= 0) {
                return;
            }

            long pressure = workerRegistry.totalQueuedWork() / healthyWorkers;
            long now = System.currentTimeMillis();
            if ((now - lastScalingActionAt) < config.getScalerCooldown().toMillis()) {
                return;
            }

            if (pressure > config.getScaleOutPressure() && healthyWorkers < config.getMaxWorkers()) {
                ec2Discovery.scaleOutOne();
                lastScalingActionAt = now;
                return;
            }

            if (pressure < config.getScaleInPressure() && healthyWorkers > config.getMinWorkers()) {
                WorkerNode candidate = workerRegistry.chooseIdleTerminationCandidate();
                if (candidate != null && !candidate.getInstanceId().startsWith("static-")) {
                    ec2Discovery.scaleInOne(candidate.getInstanceId());
                    lastScalingActionAt = now;
                }
            }
        } catch (Exception e) {
            System.out.println("[AS] Tick failed: " + e.getMessage());
        }
    }
}
