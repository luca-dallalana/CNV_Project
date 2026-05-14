package pt.ulisboa.tecnico.cnv.webserver;

import java.net.InetSocketAddress;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

import pt.ulisboa.tecnico.cnv.webserver.lb.AutoScaler;
import pt.ulisboa.tecnico.cnv.webserver.lb.ComplexityEstimator;
import pt.ulisboa.tecnico.cnv.webserver.lb.DynamoDbComplexityEstimator;
import pt.ulisboa.tecnico.cnv.webserver.lb.Ec2WorkerDiscovery;
import pt.ulisboa.tecnico.cnv.webserver.lb.LbConfig;
import pt.ulisboa.tecnico.cnv.webserver.lb.LoadBalancerHandler;
import pt.ulisboa.tecnico.cnv.webserver.lb.RequestScheduler;
import pt.ulisboa.tecnico.cnv.webserver.lb.StaticWorkerDiscovery;
import pt.ulisboa.tecnico.cnv.webserver.lb.WorkerDiscovery;
import pt.ulisboa.tecnico.cnv.webserver.lb.WorkerHttpClient;
import pt.ulisboa.tecnico.cnv.webserver.lb.WorkerNode;
import pt.ulisboa.tecnico.cnv.webserver.lb.WorkerRegistry;

public class WebServer {
    public static void main(String[] args) throws Exception {
        LbConfig config = LbConfig.fromEnv();
        WorkerRegistry workerRegistry = new WorkerRegistry();

        WorkerDiscovery workerDiscovery;
        Ec2WorkerDiscovery ec2Discovery = null;
        if (config.usesStaticWorkers()) {
            workerDiscovery = new StaticWorkerDiscovery(config);
        } else {
            ec2Discovery = new Ec2WorkerDiscovery(config);
            workerDiscovery = ec2Discovery;
        }

        List<WorkerNode> initialWorkers = workerDiscovery.discoverWorkers();
        workerRegistry.refresh(initialWorkers);

        final DynamoDbComplexityEstimator complexityEstimator = new DynamoDbComplexityEstimator(config);
        RequestScheduler scheduler = new RequestScheduler(workerRegistry);
        WorkerHttpClient workerHttpClient = new WorkerHttpClient(config);

        HttpServer server = HttpServer.create(new InetSocketAddress(config.getListenPort()), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new RootHandler());
        server.createContext("/fractals", new LoadBalancerHandler("fractals", scheduler, complexityEstimator, workerHttpClient, config));
        server.createContext("/dna", new LoadBalancerHandler("dna", scheduler, complexityEstimator, workerHttpClient, config));
        server.createContext("/grayscott", new LoadBalancerHandler("grayscott", scheduler, complexityEstimator, workerHttpClient, config));

        AutoScaler autoScaler = new AutoScaler(config, workerDiscovery, workerRegistry, ec2Discovery);
        autoScaler.start();

        final Ec2WorkerDiscovery finalEc2Discovery = ec2Discovery;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            autoScaler.stop();
            server.stop(0);
            complexityEstimator.close();
            if (finalEc2Discovery != null) {
                finalEc2Discovery.close();
            }
        }));

        server.start();
        System.out.println("[LB] WebServer started on port " + config.getListenPort() + " with " + workerRegistry.workerCount() + " workers.");
    }
}
