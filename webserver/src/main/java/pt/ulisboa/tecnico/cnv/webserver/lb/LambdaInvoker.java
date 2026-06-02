package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public final class LambdaInvoker {
    private final LambdaClient lambdaClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LambdaInvoker(LbConfig config) {
        this.lambdaClient = LambdaClient.builder()
                .region(config.getAwsRegion())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(config.getForwardTimeout())
                        .build())
                .build();
    }

    public String invoke(String functionName, Map<String, String> params) throws IOException {
        try {
            String payloadJson = objectMapper.writeValueAsString(params);
            InvokeRequest request = InvokeRequest.builder()
                    .functionName(functionName)
                    .invocationType(InvocationType.REQUEST_RESPONSE)
                    .payload(SdkBytes.fromUtf8String(payloadJson))
                    .build();
            InvokeResponse response = lambdaClient.invoke(request);
            if (response.functionError() != null) {
                throw new IOException("Lambda function error: " + response.functionError()
                        + " — " + response.payload().asUtf8String());
            }
            return objectMapper.readValue(response.payload().asUtf8String(), String.class);
        } catch (SdkException e) {
            throw new IOException("Lambda SDK error: " + e.getMessage(), e);
        }
    }

    public void close() {
        lambdaClient.close();
    }
}
