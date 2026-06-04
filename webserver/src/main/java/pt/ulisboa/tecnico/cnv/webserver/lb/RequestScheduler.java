package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RequestScheduler {
    private final WorkerRegistry workerRegistry;
    private final LbConfig config;

    public RequestScheduler(WorkerRegistry workerRegistry, LbConfig config) {
        this.workerRegistry = workerRegistry;
        this.config = config;
    }

    public WorkerNode selectWorker(Set<String> excludedWorkers, long complexity) {
        long spreadThreshold = config.getScaleOutPressure();
        long hardCeiling = config.getHardCeiling();
        long packCeiling = config.getScaleOutPressure();

        int activeCount = Math.max(1, workerRegistry.activeWorkerCount());
        long pressure = workerRegistry.totalQueuedWork() / activeCount;

        List<WorkerNode> candidates = new ArrayList<>();
        for (WorkerNode worker : workerRegistry.allNodes()) {
            if (excludedWorkers.contains(worker.getInstanceId())) continue;
            if (worker.isDraining()) continue;
            if (worker.getEstimatedQueuedWork() >= hardCeiling) continue;
            candidates.add(worker);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        if (pressure >= spreadThreshold) {
            return minScore(candidates, complexity);
        }

        List<WorkerNode> packCandidates = new ArrayList<>();
        for (WorkerNode worker : candidates) {
            if (score(worker, complexity) < packCeiling) {
                packCandidates.add(worker);
            }
        }

        if (!packCandidates.isEmpty()) {
            return maxScore(packCandidates, complexity);
        }

        return minScore(candidates, complexity);
    }

    public WorkerNode selectWorker(long predictedComplexity) {
        return selectWorker(new HashSet<>(), predictedComplexity);
    }

    public boolean shouldUseLambda(long complexity) {
        if (!config.isLambdaEnabled()) return false;
        if (complexity > config.getLambdaComplexityThreshold()) return false;
        int activeCount = workerRegistry.activeWorkerCount();
        if (activeCount == 0) return true;
        long pressure = workerRegistry.totalQueuedWork() / activeCount;
        return pressure >= config.getLambdaPressureThreshold();
    }

    private static long score(WorkerNode worker, long complexity) {
        return worker.getEstimatedQueuedWork() + complexity;
    }

    private static WorkerNode minScore(List<WorkerNode> candidates, long complexity) {
        WorkerNode best = null;
        long bestScore = Long.MAX_VALUE;
        for (WorkerNode worker : candidates) {
            long s = score(worker, complexity);
            if (s < bestScore) {
                bestScore = s;
                best = worker;
            }
        }
        return best;
    }

    private static WorkerNode maxScore(List<WorkerNode> candidates, long complexity) {
        WorkerNode best = null;
        long bestScore = Long.MIN_VALUE;
        for (WorkerNode worker : candidates) {
            long s = score(worker, complexity);
            if (s > bestScore) {
                bestScore = s;
                best = worker;
            }
        }
        return best;
    }
}
