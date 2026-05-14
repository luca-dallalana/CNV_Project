package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.ArrayList;
import java.util.List;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;

public final class Ec2WorkerDiscovery implements WorkerDiscovery {
    private final LbConfig config;
    private final Ec2Client ec2Client;

    public Ec2WorkerDiscovery(LbConfig config) {
        this.config = config;
        this.ec2Client = Ec2Client.builder()
                .region(config.getAwsRegion())
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .build();
    }

    public void close() {
        if (ec2Client != null) {
            ec2Client.close();
        }
    }

    @Override
    public List<WorkerNode> discoverWorkers() {
        List<WorkerNode> nodes = new ArrayList<>();
        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .filters(
                        Filter.builder().name("instance-state-name").values("pending", "running").build(),
                        Filter.builder().name("tag:" + config.getWorkerTagKey()).values(config.getWorkerTagValue()).build())
                .build();

        for (Reservation reservation : ec2Client.describeInstancesPaginator(request).reservations()) {
            for (Instance instance : reservation.instances()) {
                String host = hostFor(instance);
                if (host == null || host.isBlank()) {
                    continue;
                }
                nodes.add(new WorkerNode(instance.instanceId(), host, config.getWorkerPort()));
            }
        }
        return nodes;
    }

    private String hostFor(Instance instance) { // FIXME: Maybe get only one of these
        if (instance.privateIpAddress() != null && !instance.privateIpAddress().isBlank()) {
            return instance.privateIpAddress();
        }
        if (instance.publicDnsName() != null && !instance.publicDnsName().isBlank()) {
            return instance.publicDnsName();
        }
        if (instance.publicIpAddress() != null && !instance.publicIpAddress().isBlank()) {
            return instance.publicIpAddress();
        }
        return null;
    }

    public void scaleOutOne() { // launches 1 instance from launch template
        if (!config.hasLaunchTemplate()) { 
            System.out.println("[AS] Launch template not configured. Skipping scale-out.");
            return;
        }
        ec2Client.runInstances(r -> r
                .launchTemplate(lt -> lt.launchTemplateId(config.getWorkerLaunchTemplateId())
                        .version(config.getWorkerLaunchTemplateVersion()))
                .minCount(1)
                .maxCount(1));
        System.out.println("[AS] Triggered scale-out by 1 instance.");
    }

    public void scaleInOne(String instanceId) { //terminates specified instance
        ec2Client.terminateInstances(r -> r.instanceIds(instanceId));
        System.out.println("[AS] Triggered scale-in for instance " + instanceId + ".");
    }

    public boolean instanceHasWorkerTag(Instance instance) {
        for (Tag tag : instance.tags()) {
            if (config.getWorkerTagKey().equals(tag.key()) && config.getWorkerTagValue().equals(tag.value())) {
                return true;
            }
        }
        return false;
    }
}
