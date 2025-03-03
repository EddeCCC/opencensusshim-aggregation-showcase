# span-exporter-showcase

## Origin

The project originates from [inspectit-eum-server](https://github.com/inspectIT/inspectit-ocelot-eum-server).
In summary, the server is used to receive data (metrics & traces) collected from browsers and process them as OpenTelemetry-data.
This showcase extracts only the part responsible for traces.

---

## Data flow
The showcase provides one REST endpoint: `/spans`

It expects to receive span proto data, which will be converted to OpenTelemetry SDK spans. 
After that, the SDK spans will be exported via OTLP to a collector.

## Data origin

The received span proto data is created by [opentelemetry-exporter-trace-otlp-http](https://github.com/open-telemetry/opentelemetry-js/tree/main/experimental/packages/exporter-trace-otlp-http)

More precisely, we use this plugin to generate traces:
https://github.com/NovatecConsulting/boomerang-opentelemetry-plugin

There is an example for such data in the [test resources](src/test/resources/ot-trace-array-v0.48.0.json).

## Test case

The test class [OtlpGrpcTraceExporterIntTest](src/test/java/rocks/inspectit/oce/eum/server/exporters/tracing/OtlpGrpcTraceExporterIntTest.java) 
tries to send data to the `/spans` and checks if the expected trace data will be exported by OTLP.

The test `verifyTraceWithArrayValueSent()` fails with a `java.lang.ClassCastException`.
