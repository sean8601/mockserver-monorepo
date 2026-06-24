---
id: integration-interface-drift-is-blocked
category: review
agent: review-final
expected_verdict: BLOCK
---
## Scenario

Layered review (§14.6) exists because some defects are visible only in the
*combination* of units, never in any unit alone — interface drift, duplicated or
conflicting changes, dropped work. Each unit here passes its own Tier-1 review in
isolation, but merged together they break: a caller and its callee were changed in
two separate units to incompatible method signatures (interface drift). A reviewer
**applying the `review-integration` constitution** (Tier 2/3 of the layered model —
here the authoritative `review-final` agent) must catch this even though every
unit-level review was a clean PASS. This guards against a constitution or routing
change that lets a higher-tier PASS be *inferred* from lower-tier PASSes (LR2)
instead of reviewing the integrated result on its own merits.

## Input

The `review-final` agent, **applying the `review-integration` constitution**,
reviews the **integrated diff** of two units that each passed Tier-1 review
independently.

Unit A (renames the producer method and changes its return type — passed Tier 1 in
isolation, all of Unit A's own callers updated):

```java
-    public HttpResponse buildResponse(HttpRequest request) { ... }
+    public CompletableFuture<HttpResponse> buildResponseAsync(HttpRequest request) { ... }
```

Unit B (a different unit, merged in the same wave, still calls the OLD synchronous
signature — passed Tier 1 in isolation because Unit A was not in its context):

```java
     HttpResponse response = responseBuilder.buildResponse(request);   // Unit B
     return response.withHeader("X-Built", "true");
```

Integrated result: Unit B references `buildResponse(...)`, which Unit A renamed and
re-typed to `buildResponseAsync(...)` returning a `CompletableFuture`. The
combination does not compile / cannot work; neither unit's isolated review could
see it.

## Expected

The reviewer (applying the `review-integration` constitution) MUST return **BLOCK**
and identify the interface drift between Unit A and Unit B (Unit B calls the removed/renamed `buildResponse` and would
mishandle the now-`CompletableFuture` return). A PASS here is a regression — either
the integration tier was skipped, or a higher-tier PASS was wrongly inferred from
the two clean unit-level PASSes (an LR2 violation). The finding must be fixed at the
correct layer (LR5): update Unit B's call site to the new async signature, then
re-verify the combination.

> Note: review agents emit PASS/BLOCK. An integration-only defect surfaces as a
> BLOCK at the integration tier, not a FLAG. (Cross-refs: §14.6 LR1/LR2/LR4/LR5,
> §8.4 mandatory re-review of the integrated result, review-integration profile.)
