package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StaticWorkerDiscovery implements WorkerDiscovery {
    private final LbConfig config;
    private final AtomicBoolean logged = new AtomicBoolean(false);

    public StaticWorkerDiscovery(LbConfig config) {
        this.config = config;
    }

    @Override
    public List<WorkerNode> discoverWorkers() {
        List<WorkerNode> nodes = new ArrayList<>();
        List<String> staticWorkers = config.getStaticWorkers();
        for (int i = 0; i < staticWorkers.size(); i++) {
            String raw = staticWorkers.get(i);
            String[] parts = raw.split(":");
            String host = parts[0].trim();
            int port = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : config.getWorkerPort();
            nodes.add(new WorkerNode("static-" + i, host, port));
        }
        if (logged.compareAndSet(false, true)) {
            System.out.println("[LB] Static worker mode enabled with " + nodes.size() + " workers.");
        }
        return nodes;
    }
}
