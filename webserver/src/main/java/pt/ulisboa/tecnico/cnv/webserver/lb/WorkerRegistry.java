package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkerRegistry {
    private final Map<String, WorkerNode> nodes = new ConcurrentHashMap<>();

    public synchronized void refresh(List<WorkerNode> discoveredNodes) {
        Set<String> newIds = new HashSet<>();
        for (WorkerNode discovered : discoveredNodes) {
            newIds.add(discovered.getInstanceId());
            nodes.putIfAbsent(discovered.getInstanceId(), discovered);
        }
        nodes.keySet().retainAll(newIds);
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
