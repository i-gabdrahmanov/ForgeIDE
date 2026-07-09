package dev.forgeide.runtime.outward;

import dev.forgeide.core.port.OutwardActionException;
import dev.forgeide.core.port.OutwardActionsPort;

/**
 * One hosted PR provider's half of {@code create_pr} (T17): find an existing open PR for the
 * source branch first — the idempotency argument for a retried outward step — and only create a
 * new one if none exists.
 */
interface PullRequestClient {

    OutwardActionsPort.Outcome createOrReuse(OutwardActionsPort.CreatePrRequest request) throws OutwardActionException;
}
