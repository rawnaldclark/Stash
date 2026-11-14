package com.stash.data.download.lossless.addon

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marks a lookup that must ask no add-on: put it in the coroutine context of a call to
 * `LosslessSourceRegistry.resolve` and every [AddonLosslessSource] answers null without a request.
 *
 * For callers that would fetch the answer's URL on a client without the add-on base rules
 * (review-882 B1): the search preview plays its match through a plain cached HTTP source, so an
 * add-on's URL must never reach it, and a preview is no reason to spend a `/download` call on a
 * server Stash doesn't run. A context element, like `LosslessDownloadPurpose`, so the registry's
 * own walk needs no parameter for it.
 */
object NoAddonSources : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<NoAddonSources>
}
