package rocks.inspectit;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.opencensusshim.OpenCensusMetricProducer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ServiceAttributes;

import java.time.Duration;

public class OpenTelemetryConfiguration {

    public static OpenTelemetry openTelemetry(String endpoint) {
        Resource resource = Resource.builder()
                .put(ServiceAttributes.SERVICE_NAME, "showcase")
                .build();

        MetricExporter metricExporter = OtlpHttpMetricExporter.builder()
                // use DELTA aggregation
                .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
                .setEndpoint(endpoint)
                .build();

        MetricReader metricReader = PeriodicMetricReader.builder(metricExporter)
                .setInterval(Duration.ofMillis(500))
                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .addResource(resource)
                .registerMetricReader(metricReader)
                .registerMetricProducer(OpenCensusMetricProducer.create())
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .buildAndRegisterGlobal();

        return sdk;
    }
}
