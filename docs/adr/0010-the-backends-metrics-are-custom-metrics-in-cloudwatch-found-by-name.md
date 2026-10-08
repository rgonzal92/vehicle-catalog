# The backend's metrics are custom metrics in CloudWatch, found by name

The backend sends its metrics over OTLP to the CloudWatch agent on the host, and the agent publishes each one as a custom metric in the namespace `vehicle-catalog`. That keeps them where CloudWatch's ordinary alarms and dashboards read, and where an account is not charged for its first ten.

The agent tells each of the backend's metrics apart by four labels of its own making, besides any the backend gave it: the service's name, and the name, language, and version of the library that sent the metric. The agent's file has no setting for that, and the agent refuses a second file that changes how it publishes. So the alarm and the dashboard ask for a metric by its name, whatever labels it carries: the alarm with a Metrics Insights query, the dashboard with search expressions.

## Considered Options

- **Naming every label in the alarm and the dashboard.** One of the labels is the library's version, which changes whenever Spring Boot is updated. The alarm would then look at a metric that no longer arrives, and go off.
- **Leaving the labels out before the metrics are sent.** The library that sends them has no setting for it, so this would be code in the backend that takes apart and rewrites what the library sends.
- **CloudWatch's own OTLP endpoint, which the agent can also send to.** Metrics kept that way are queried with PromQL, and an alarm on them is written in PromQL. That path was read about and not tried.

## Consequences

- Whatever is timed is sent with its counts in buckets. The agent drops a timed metric that comes without buckets, and says so only in its own log.
- A timer with nothing to time in a minute is sent empty, and the agent writes an error line for it in its own log, every minute: `metric has a distribution with no entries`. Nothing is lost by it.
- When the library's version changes, CloudWatch takes what arrives for a new metric. A chart shows the old and the new side by side, and the alarm reads whichever arrives.
- The backend turns down every metric that is not on its list, so a new one is added in three places: the list in `Telemetry.java`, the dashboard, and the count of metrics that are paid for.
- An alarm on one of the backend's metrics is written as a query by name, as the one on its health is.
