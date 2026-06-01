package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkerRegistry {
    private final Map<String, WorkerNode> nodes = new ConcurrentHashMap<>();

    public synchronized void refresh(List<WorkerNode> discoveredNodes) {
        Map<String, WorkerNode> next = new ConcurrentHashMap<>();
        for (WorkerNode discovered : discoveredNodes) {
            WorkerNode existing = nodes.get(discovered.getInstanceId());
            if (existing != null) {
                next.put(discovered.getInstanceId(), existing);
                continue;
            }
            next.put(discovered.getInstanceId(), discovered);
        }
        nodes.clear();
        nodes.putAll(next);
    }

    public List<WorkerNode> allNodes() {
        return new ArrayList<>(nodes.values());
    }

    public long totalQueuedWork() {
        long total = 0L;
        for (WorkerNode node : nodes.values()) {
            total += node.getEstimatedQueuedWork();
        }
        return total;
    }

    public int workerCount() {
        return nodes.size();
    }

    public int activeWorkerCount() {
        int count = 0;
        for (WorkerNode node : nodes.values()) {
            if (!node.isDraining()) count++;
        }
        return count;
    }

    public WorkerNode chooseIdleTerminationCandidate() {
        for (WorkerNode node : nodes.values()) {
            if (node.isDraining()) continue;
            if (node.getInflightRequests() > 0 || node.getEstimatedQueuedWork() > 0) continue;
            return node;
        }
        return null;
    }
}
