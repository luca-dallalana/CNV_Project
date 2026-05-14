package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.Map;

public interface ComplexityEstimator {
    long estimate(String workload, Map<String, String> params);
}
