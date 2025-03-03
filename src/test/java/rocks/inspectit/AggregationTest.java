package rocks.inspectit;

import com.google.protobuf.InvalidProtocolBufferException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.protocol.AbstractUnaryGrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.opencensus.stats.*;
import io.opencensus.tags.TagContext;
import io.opencensus.tags.TagKey;
import io.opencensus.tags.TagValue;
import io.opencensus.tags.Tags;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.Testcontainers.exposeHostPorts;

/**
 * This tests exports OpenCensus metrics via OTLP to an OTel collector, which forwards the metrics to an
 * GRPC server for validation.
 * <br>
 * The test will fail, since the second exported value is 3 instead of the expected 2.
 */
public class AggregationTest {

    static final String COLLECTOR_IMAGE = "otel/opentelemetry-collector-contrib:0.100.0";

    static final Integer COLLECTOR_OTLP_HTTP_PORT = 4318;

    static final Integer COLLECTOR_HEALTH_CHECK_PORT = 13133;

    static OtlpGrpcServer grpcServer;

    static GenericContainer<?> collector;

    static StatsRecorder statsRecorder;

    static OpenTelemetry openTelemetry;

    @BeforeAll
    static void setUp() {
        // set up GRPC server to validate metrics
        grpcServer = new OtlpGrpcServer();
        grpcServer.start();

        exposeHostPorts(grpcServer.httpPort());

        // set up collector to receive & export metrics
        collector = new GenericContainer<>(DockerImageName.parse(COLLECTOR_IMAGE)).withEnv("LOGGING_EXPORTER_LOG_LEVEL", "INFO")
                .withEnv("OTLP_EXPORTER_ENDPOINT", "host.testcontainers.internal:" + grpcServer.httpPort())
                .withClasspathResourceMapping("otel-config.yaml", "/otel-config.yaml", BindMode.READ_ONLY)
                .withCommand("--config", "/otel-config.yaml")
                .withExposedPorts(COLLECTOR_OTLP_HTTP_PORT, COLLECTOR_HEALTH_CHECK_PORT)
                .waitingFor(Wait.forHttp("/").forPort(COLLECTOR_HEALTH_CHECK_PORT));

        collector.start();

        // Configure local OTel
        String endpoint = getEndpoint(COLLECTOR_OTLP_HTTP_PORT);
        openTelemetry = OpenTelemetryConfiguration.openTelemetry(endpoint);

        statsRecorder = Stats.getStatsRecorder();
    }

    @AfterAll
    static void clear() {
        collector.stop();
        grpcServer.stop();
    }

    @BeforeEach
    void reset() {
        grpcServer.reset();
    }

    @Test
    void testAggregationTemporality() {
        String name = "my-counter";
        String tagKey = "my-key";
        String tagValue = "my-value";
        Measure.MeasureLong measure = registerMeasure(name, tagKey);

        TagContext tagContext = Tags.getTagger().emptyBuilder()
                .putLocal(TagKey.create(tagKey), TagValue.create(tagValue))
                .build();

        statsRecorder.newMeasureMap().put(measure, 1).record(tagContext);

        // The sum of the measurement should be 1 -> delta value 1
        awaitMetricsExported(name, 1);

        statsRecorder.newMeasureMap().put(measure, 2).record(tagContext);

        // The sum of the measurement should be 3 -> delta value 2 --> WILL FAIL
        awaitMetricsExported(name, 2);
    }

    private Measure.MeasureLong registerMeasure(String name, String tagKey) {
        Measure.MeasureLong measure = Measure.MeasureLong.create(name, "desc", "1");

        View view = View.create(View.Name.create(name), "desc", measure, Aggregation.Sum.create(),
                Collections.singletonList(TagKey.create(tagKey)));
        Stats.getViewManager().registerView(view);

        return measure;
    }

    /**
     * Verifies that the metric with the given value has been exported to and received
     * by the {@link #grpcServer}
     *
     * @param measureName the name of the measure
     * @param value  the value of the measure
     */
    private void awaitMetricsExported(String measureName, int value) {
        await().atMost(30, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(grpcServer.metricRequests.stream())
                        .anyMatch(mReq -> mReq.getResourceMetricsList().stream()
                                .anyMatch(rm ->  rm.getScopeMetrics(0)
                                        .getMetricsList().stream()
                                        .filter(metric -> metric.getName().equals(measureName))
                                        // check for the specific attribute and value
                                        .anyMatch(metric -> metric.getSum()
                                                .getDataPointsList()
                                                .stream()
                                                .anyMatch(d -> d.getAsInt() == value)))));
    }

    private static String getEndpoint(Integer originalPort) {
        return String.format("http://%s:%d/v1/metrics", collector.getHost(), collector.getMappedPort(originalPort));
    }

    public static class OtlpGrpcServer extends ServerExtension {

        final List<ExportMetricsServiceRequest> metricRequests = new ArrayList<>();

        void reset() {
            metricRequests.clear();
        }

        private void debug(ExportMetricsServiceRequest request) {
            Metric m = request.getResourceMetrics(0)
                    .getScopeMetrics(0)
                    .getMetrics(0);

            System.out.println(m.getName() + ": " +
                    m.getSum().getDataPointsList().size() + " -> " +
                    m.getSum().getDataPointsList().stream()
                            .map(NumberDataPoint::getAsInt)
                            .reduce(Long::sum));
        }


        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/opentelemetry.proto.collector.metrics.v1.MetricsService/Export", new AbstractUnaryGrpcService() {
                @Override
                protected CompletionStage<byte[]> handleMessage(ServiceRequestContext ctx, byte[] message) {
                    try {
                        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.parseFrom(message);
                        metricRequests.add(request);
                        debug(request);
                    } catch (InvalidProtocolBufferException e) {
                        throw new UncheckedIOException(e);
                    }
                    return completedFuture(ExportMetricsServiceResponse.getDefaultInstance().toByteArray());
                }
            });
            sb.http(0);
        }
    }
}
