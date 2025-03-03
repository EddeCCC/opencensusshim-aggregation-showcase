# opencensusshim-aggregation-showcase

## Origin

The project originates from [inspectit-ocelot](https://github.com/inspectIT/inspectit-ocelot).
In summary, it is a Java agent to collect metrics with OpenCensus and export them via OTLP.
This showcase extracts only the relevant parts to configure the AggregationTemporality & export metrics.

---

## Test case

The OpenCensus metrics are created in the application. Via the OpenCensus-shim, they are exported via OTLP to an
OTel collector. This collector exports the metrics to a GRPC server via OTLP.
The server is used to validate the metrics.

The MetricExporter is configured to always prefer DELTA AggregationTemporality.
The test will export two values. First the value 1. 
After the value was received by the GRPC server, the value 2 will be recorded.
This will increase the sum of the measurement to 3. 
However, since we want to export delta values, we expect to export the value 2.
The test fails, because the second exported value is actually 3 instead of 2.
