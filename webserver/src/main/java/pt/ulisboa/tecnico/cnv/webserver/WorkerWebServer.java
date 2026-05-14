package pt.ulisboa.tecnico.cnv.webserver;

import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;

import pt.ulisboa.tecnico.cnv.dna.DnaHandler;
import pt.ulisboa.tecnico.cnv.fractals.FractalsHandler;
import pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler;

public class WorkerWebServer {
    public static void main(String[] args) throws Exception {
        int port = 8000;
        String envPort = System.getenv("WORKER_PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new RootHandler());
        server.createContext("/fractals", new FractalsHandler());
        server.createContext("/dna", new DnaHandler());
        server.createContext("/grayscott", new GrayScottHandler());
        server.start();
        System.out.println("[Worker] WebServer started on port " + port + ".");
    }
}
