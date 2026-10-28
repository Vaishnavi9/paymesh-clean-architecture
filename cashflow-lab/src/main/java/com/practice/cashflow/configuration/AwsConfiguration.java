package com.practice.cashflow.configuration;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.StsClient;

@Configuration
@Profile("aws")
public class AwsConfiguration {
    @Bean(destroyMethod = "close")
    DefaultCredentialsProvider awsCredentials() {
        return DefaultCredentialsProvider.builder().build();
    }

    private ClientOverrideConfiguration timeouts() {
        return ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(Duration.ofSeconds(5))
                .apiCallTimeout(Duration.ofSeconds(10)).build();
    }

    @Bean(destroyMethod = "close")
    StsClient stsClient(DefaultCredentialsProvider credentials,
                        @Value("${app.aws.region}") String region) {
        return StsClient.builder().region(Region.of(region)).credentialsProvider(credentials)
                .overrideConfiguration(timeouts()).build();
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(DefaultCredentialsProvider credentials,
                      @Value("${app.aws.region}") String region) {
        return S3Client.builder().region(Region.of(region)).credentialsProvider(credentials)
                .overrideConfiguration(timeouts()).build();
    }

    @Bean(destroyMethod="close")
    SqsClient sqsClient (
            DefaultCredentialsProvider credentials,
            @Value("${app.aws.region}") String region
    ) {
        return SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .overrideConfiguration(
                        ClientOverrideConfiguration.builder()
                                .apiCallAttemptTimeout(Duration.ofSeconds(30))
                                .apiCallTimeout(Duration.ofSeconds(70))
                                .build()
                ).build();
    }
}
