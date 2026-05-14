package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.List;

public interface WorkerDiscovery {
    List<WorkerNode> discoverWorkers();
}
