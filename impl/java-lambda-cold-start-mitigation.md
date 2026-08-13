# Java Lambda cold-start mitigation (SnapStart + AWS SDK clients)

## Summary

Enabling Lambda SnapStart on a Java function is not, by itself, enough to make a cold request to
an AWS SDK-backed dependency (DynamoDB, S3, Cognito, SQS — any service client) fast. SnapStart
removes JVM startup and class-loading cost for whatever ran *before* the snapshot was taken, but
a lazily-built SDK client typically hasn't made its first real call by then, so the expensive
part — loading and interpreting the SDK's marshallers, HTTP client internals, and credential/
signing chain for the first time — simply moves to wherever that first call actually happens. Get
that placement wrong and SnapStart can look like it does nothing at all.

This document describes the mitigation implemented in this project (see
[DynamoDbClientProvider.java](src/main/java/com/mootmaker/dynamo/DynamoDbClientProvider.java) for
the concrete code), generalised to any AWS SDK for Java v2 client, along with the measurements
that motivated each change and a general SnapStart performance-tuning checklist.

## Two different costs, easy to conflate

A cold Lambda's first real call to an AWS service pays for two genuinely separate things:

1. **Class loading / JIT, at interpreter speed.** The first time a code path is touched — the
   first `Scan`, the first `PutObject`, whatever — the JVM has to load classes it hasn't loaded
   yet (request/response models, the operation's generated marshaller/unmarshaller, retry logic,
   the SigV4 signer, the HTTP client's internals) and run them uncompiled, before the JIT has had
   a chance to compile any of it. For a dependency as large as an AWS SDK client, this is
   routinely the *dominant* cost of a cold Lambda invocation — often several seconds, an order of
   magnitude more than the actual network work involved.
2. **Network connection establishment.** DNS resolution, a TCP handshake, and a TLS handshake to
   the service endpoint, plus resolving credentials if they aren't already cached. Within an AWS
   region this is genuinely cheap — commonly tens of milliseconds, not seconds.

It's easy to see a slow first request and blame "the network," but in a Java Lambda the class
loading cost usually dwarfs it. The mitigation strategy below treats these as two separate
problems because SnapStart's snapshot mechanism treats them completely differently.

## What a SnapStart snapshot does and doesn't capture

SnapStart takes its snapshot **once**, immediately after your function's INIT phase completes —
this happens at `publish-version` time (i.e. at deploy time), not on a customer request. Every
later "cold start" against that published version is actually a **restore** from that frozen
snapshot, not a fresh JVM boot.

- **Captured, and reused for free on every restore:** loaded classes, JIT-compiled code, and any
  other JVM heap/metaspace state that existed at the moment of the snapshot. If your first real
  SDK call happened *before* checkpoint, its class-loading and JIT cost is baked in — every
  restored environment starts already warm.
- **Not captured — must be redone on every restore:** open sockets, TLS session state, and
  anything else tied to the specific network environment the snapshot was taken in. AWS states
  this directly: *"The state of connections that your function establishes during the
  initialization phase isn't guaranteed when Lambda resumes your function from a snapshot."*
  (see [Networking best practices](https://docs.aws.amazon.com/lambda/latest/dg/snapstart-best-practices.html#snapstart-networking)).
  A restored execution environment gets a new network identity, so any connection object your
  client held at snapshot time is dead, even though it still looks "open" from the JVM's
  perspective.

This is the whole story: **whatever isn't exercised before checkpoint gets exercised, at
interpreter speed, sometime after restore** — either inside your handler on the customer's first
request (worst case), or in an `afterRestore` hook ahead of it (better, but still after restore).
The fix is to exercise it *before* checkpoint instead, so only the cheap connection-reestablishment
part is left for restore time.

## The mitigation: split priming across two points in the client's lifecycle

The pattern, generalised from `DynamoDbClientProvider` to any lazily-built AWS SDK v2 client:

```java
public final class SomeAwsClientProvider {

    private static volatile SomeAwsClient client;
    private static Resource afterRestorePriming; // strong reference - see note below

    public static SomeAwsClient client() {
        if (client == null) {
            synchronized (SomeAwsClientProvider.class) {
                if (client == null) {
                    client = SomeAwsClient.builder().build();

                    // Runs here, in the same INIT-phase code path the Lambda Java runtime uses to
                    // construct your handler - which is unconditionally *before* SnapStart takes
                    // its snapshot. No CRaC hook is needed for this half: ordinary eager code in
                    // a static/lazily-initialised context already lands before checkpoint.
                    primeConnection(client);

                    afterRestorePriming = new AfterRestorePriming(client);
                    org.crac.Core.getGlobalContext().register(afterRestorePriming);
                }
            }
        }
        return client;
    }

    // A single, real, side-effect-free, cheap call representative of what this client actually
    // does - e.g. a metadata/describe-style read rather than a mutating operation - exercised
    // twice: once eagerly above (baked into the snapshot), once more from afterRestore below
    // (since the connection itself doesn't survive the snapshot regardless of what's primed).
    private static void primeConnection(SomeAwsClient client) {
        client.someCheapReadOnlyCall(...);
    }

    // Nothing re-runs static/lazy-init code on restore - a snapshot is thawed with its state
    // exactly as frozen. Only a registered CRaC afterRestore hook fires again on every restore,
    // which is the only way to redo the connection establishment that the snapshot couldn't keep.
    private record AfterRestorePriming(SomeAwsClient client) implements org.crac.Resource {
        @Override
        public void beforeCheckpoint(org.crac.Context<? extends Resource> context) {
        }

        @Override
        public void afterRestore(org.crac.Context<? extends Resource> context) {
            primeConnection(client);
        }
    }
}
```

Key mechanics of `org.crac` worth knowing:

- **`Context` only holds a `WeakReference` to a registered `Resource`.** If nothing else
  references it, it can be garbage collected before Lambda ever calls `afterRestore()` — hence
  the `static` field holding a strong reference in the example above. This is documented (with the
  exact failure mode) in AWS's
  [Lambda SnapStart runtime hooks for Java](https://docs.aws.amazon.com/lambda/latest/dg/snapstart-runtime-hooks-java.html)
  page.
- **Hook ordering**: `beforeCheckpoint()` runs in the reverse order resources were registered;
  `afterRestore()` runs in registration order.
- **Time limits**: `beforeCheckpoint()` counts toward the INIT time budget (130 seconds, or the
  function's configured timeout if longer). `afterRestore()` must complete within 10 seconds, or
  the restore fails with `SnapStartTimeoutException`.
- **The priming call must be idempotent/side-effect-free.** `beforeCheckpoint()` runs once per
  `publish-version`, not once per request, but it still shouldn't mutate real data — a metadata
  read (e.g. DynamoDB's `DescribeTable`, S3's `HeadBucket`, an SQS `GetQueueAttributes`) is a safe
  choice for any service, since it exercises the same client/marshaller/signing machinery as a
  real request without side effects.
- **Least-privilege IAM**: the priming call needs its own IAM grant like any other API call — in
  this project, `dynamodb:DescribeTable` was added to the Lambda execution role specifically for
  this purpose (scoped to the same table resources already granted). Whatever no-op-equivalent
  call you choose for another service will need the equivalent narrow grant.

## Why the after-restore reconnection step still matters, and what it costs

Even with everything primed before checkpoint, `afterRestore()` (or equivalent handler-body logic)
is still required, because the TCP/TLS connection and any cached credentials are invalid the
moment a snapshot restores — independent of whether the code that would use them has been
warmed. Skipping this and just relying on the (now class-loaded, JIT-warm) client to lazily
reconnect on the customer's first real request still puts a real, synchronous reconnect on that
request's critical path — just a much cheaper one than before, since it's now "only" a fresh
TCP + TLS handshake and credential resolution, not also a cold class load.

In this project's own before/after measurements (`test-mootmaker-list-rooms`, forced-cold
invocations, 3 samples each — see the project's PR history / conversation log for full detail):

| Configuration | Init/Restore Duration | Handler `Duration` (first real DynamoDB call) | Total wall clock | Billed |
|---|---|---|---|---|
| No SnapStart | ~1.35s (JVM boot only) | ~3.3s (class load + connect, at interpreter speed) | ~4.65s | ~4.65s |
| SnapStart, priming only in `afterRestore` | ~3.94s (class load + connect now happens here) | ~0.40s | ~4.34s | ~3.65s |
| SnapStart, priming both eagerly (pre-checkpoint) **and** in `afterRestore` | ~1.0s (connection only) | ~1.1s | **~2.1s** | **~1.4s** |

Splitting the two costs across both hook points cut total cold-request latency by roughly 55% and
billed compute by roughly 70% compared to no SnapStart at all — and meaningfully beat priming in
`afterRestore` alone, because that configuration was still paying the full class-loading cost, just
moved from the customer-visible `Duration` into `Restore Duration` (which still blocks the response
for an isolated cold request — see the next section).

One open question from this project's own measurements: the third configuration's `Duration`
(~1.1s) was *higher* than the second's (~0.40s), despite classes being at least as warm. The
working hypothesis, not yet confirmed, is that the JDK's own HTTP keep-alive connection cache
(relevant if using `url-connection-client`, which is built on `java.net.HttpURLConnection`) may
retain a reference to the connection opened during the pre-checkpoint priming call, get that
stale reference frozen into the snapshot, and pay a silent failed-reuse-then-reconnect cycle on
the first real post-restore request. A related, though not identical, restore-phase slowdown from
CRaC-primed connections has been reported for the SDK's async (Netty) client — see
[aws/aws-sdk-java-v2#3801](https://github.com/aws/aws-sdk-java-v2/issues/3801). If pursuing this
further, explicitly discarding/closing the connection in `beforeCheckpoint()` rather than leaving
it open across the checkpoint boundary would be the next thing to try.

## When this mitigation matters most (and when it doesn't)

This whole exercise is about the **first invocation of a freshly-restored execution environment**.
It has no effect on warm invocations, which were already fast (the client and connection are
already live, nothing to prime). It matters most for **intermittent, bursty traffic**, where a
large fraction of real requests land on a fresh restore rather than a warm environment — under
sustained, steady traffic, most requests hit already-warm environments and this cost is amortised
away regardless. AWS makes the same point directly: *"SnapStart works best when used with function
invocations at scale. Functions that are invoked infrequently might not experience the same
performance improvements."* — which, somewhat counter-intuitively, is exactly the traffic pattern
where the priming strategy above earns its keep, since it's the only lever left once you can't
rely on scale to amortise the cost away.

## General SnapStart performance-tuning checklist

- **SnapStart only ever applies to a published version, never `$LATEST`.** Set
  `snap_start.apply_on = "PublishedVersions"`, publish a version on every deploy, and point real
  traffic (API Gateway/AppSync integrations, Cognito triggers, etc.) at an alias tracking that
  version rather than the function directly — otherwise SnapStart is configured but silently
  inactive.
- **Prefer "invoke priming"** (exercising real code paths, including real dependency calls, before
  checkpoint) **over "class priming"** (merely referencing classes to force loading without
  running them) where possible — invoke priming also captures JIT compilation, not just class
  loading. See AWS's [Performance tuning](https://docs.aws.amazon.com/lambda/latest/dg/snapstart-best-practices.html#snapstart-tuning)
  guidance and its worked example (constructing a fake request and driving it through the real
  handler before checkpoint).
- **Re-establish network connections after restore** — in an `afterRestore` hook or the first
  lines of the handler — for every external connection your function holds, not just AWS SDK
  clients: any other TCP/TLS connection your INIT code opened has exactly the same problem.
- **Don't use `hostname` as a unique execution-environment identifier.** Every environment
  restored from the same snapshot reports the same hostname; generate a fresh unique ID in
  `afterRestore()` or the handler if you need one.
- **Avoid a second DNS cache on top of Lambda's own.** If something on the classpath enables the
  JVM's DNS cache (the AWS docs specifically call out `java.util.logging.Logger` as an indirect
  trigger), set `networkaddress.cache.ttl` to `0` before it's initialised, and set
  `AWS_LAMBDA_JAVA_NETWORKADDRESS_CACHE_NEGATIVE_TTL=0` on Java 11 (unnecessary on Java 17+).
- **Don't bind connections to a fixed source port** — they're re-established on restore and a
  fixed-port binding can fail.
- **Expect credentials resolved before checkpoint to be stale by the time of restore** (which can
  be minutes, hours, or days later). AWS SDK v2's standard credential providers check expiry
  before returning a cached value, so this is generally self-healing — but it's worth explicitly
  verifying against your actual credential provider chain rather than assuming it, particularly
  if anything custom is layered on top of it.
- **Newer Java runtimes changed the JIT trade-off.** As of the Java 25 Lambda runtime, tiered
  compilation is no longer capped at C1 for SnapStart (or Provisioned Concurrency) — earlier
  runtimes deliberately capped JIT tier during snapshot preparation, meaning invoke-priming's JIT
  benefit was more limited pre-Java 25. See the
  [AWS Lambda Java 25 announcement](https://aws.amazon.com/blogs/compute/aws-lambda-now-supports-java-25/).
- **Keep the shaded/deployment artifact small.** Every class on the classpath is a class that can
  end up loaded during INIT; this project separately excludes unused AWS SDK HTTP client
  implementations (`apache5-client`, `netty-nio-client`) from the shaded jar for exactly this
  reason (see the top-level project README's cold-start notes).
- **Measure with forced-cold, concurrent invocations**, not sequential ones — after the first
  invoke against a freshly-published version, subsequent invokes typically reuse the same
  already-restored (now warm) execution environment. Firing several `aws lambda invoke` calls
  concurrently against a just-published version's alias reliably forces distinct fresh restores,
  and `--log-type Tail` returns the `REPORT`/`RESTORE_REPORT` log lines directly in the CLI
  response without a CloudWatch Logs query.

## References

**AWS Lambda SnapStart**
- [Improving startup performance with Lambda SnapStart](https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html) — overview
- [Maximize Lambda SnapStart performance](https://docs.aws.amazon.com/lambda/latest/dg/snapstart-best-practices.html) — performance tuning and networking best practices (source for the DNS cache, hostname, and fixed-source-port guidance above)
- [Implement code before or after Lambda function snapshots](https://docs.aws.amazon.com/lambda/latest/dg/snapstart-runtime-hooks.html) — runtime hooks overview
- [Lambda SnapStart runtime hooks for Java](https://docs.aws.amazon.com/lambda/latest/dg/snapstart-runtime-hooks-java.html) — `org.crac` usage, the `WeakReference`/strong-reference gotcha, hook ordering, and timing limits
- [Activating and managing Lambda SnapStart](https://docs.aws.amazon.com/lambda/latest/dg/snapstart-activate.html)
- [AWS Lambda now supports Java 25](https://aws.amazon.com/blogs/compute/aws-lambda-now-supports-java-25/) — the tiered-compilation/SnapStart change

**CRaC (Coordinated Restore at Checkpoint)**
- [CRaC project — OpenJDK wiki](https://wiki.openjdk.org/display/crac)
- [CRaC step-by-step guide](https://github.com/CRaC/docs/blob/master/STEP-BY-STEP.md)
- [`org.crac` Javadoc](https://javadoc.io/doc/io.github.crac/org-crac/latest/index.html) — `Resource`, `Context`, `Core`
- [`org.crac:crac` on Maven Central](https://search.maven.org/artifact/org.crac/crac) — the dependency this project uses (`impl/pom.xml`)

**Known related issue**
- [aws/aws-sdk-java-v2#3801 — Auto priming support](https://github.com/aws/aws-sdk-java-v2/issues/3801) — feature request for the SDK to handle priming automatically; also documents a reported restore-phase slowdown from CRaC-primed connections on the async client, relevant to the open question above
