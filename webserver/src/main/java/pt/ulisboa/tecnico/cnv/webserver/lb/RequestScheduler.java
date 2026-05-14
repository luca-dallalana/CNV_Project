package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RequestScheduler {
    private final WorkerRegistry workerRegistry;

    public RequestScheduler(WorkerRegistry workerRegistry) {
        this.workerRegistry = workerRegistry;
    }

    public WorkerNode selectWorker(Set<String> excludedWorkers, long predictedComplexity) {
        List<WorkerNode> workers = workerRegistry.allNodes();
        WorkerNode selected = null;
        long bestScore = Long.MAX_VALUE;

        for (WorkerNode worker : workers) {
            if (excludedWorkers.contains(worker.getInstanceId())) {
                continue;
            }
            long score = worker.getEstimatedQueuedWork()  // This score is the heuristic we need to calculate, this is provisional 
                    + (worker.getInflightRequests() * Math.max(predictedComplexity / 5L, 1L))
                    + predictedComplexity;
            if (score < bestScore) {
                bestScore = score;
                selected = worker;
            }
        }
        return selected;
    }

    public WorkerNode selectWorker(long predictedComplexity) {
        return selectWorker(new HashSet<>(), predictedComplexity);
    }
}
