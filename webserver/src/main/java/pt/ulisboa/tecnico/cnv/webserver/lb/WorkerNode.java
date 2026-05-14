package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class WorkerNode {
    private final String instanceId;
    private final String host;
    private final int port;
    private final AtomicInteger inflightRequests = new AtomicInteger(0);
    private final AtomicLong estimatedQueuedWork = new AtomicLong(0L);

    public WorkerNode(String instanceId, String host, int port) {
        this.instanceId = instanceId;
        this.host = host;
        this.port = port;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getInflightRequests() {
        return inflightRequests.get();
    }

    public long getEstimatedQueuedWork() {
        return estimatedQueuedWork.get();
    }

    public void registerScheduledRequest(long predictedComplexity) {
        inflightRequests.incrementAndGet();
        estimatedQueuedWork.addAndGet(Math.max(0L, predictedComplexity));
    }

    public void completeScheduledRequest(long predictedComplexity) {
        int remaining = inflightRequests.decrementAndGet();
        if (remaining < 0) {
            inflightRequests.set(0);
        }
        long updated = estimatedQueuedWork.addAndGet(-Math.max(0L, predictedComplexity));
        if (updated < 0L) {
            estimatedQueuedWork.set(0L);
        }
    }

    public String endpointBaseUrl(String protocol) {
        return String.format("%s://%s:%d", protocol, host, port);
    }
}
