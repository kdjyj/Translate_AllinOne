package com.cedarxuesong.translate_allinone.utils.cache;

import java.util.Collection;
import java.util.Map;

final class CacheLoadSupport {
    private CacheLoadSupport() {
    }

    static void resetStateForLoad(
            Map<?, ?> templateCache,
            Collection<?> pendingQueue,
            Collection<?> inProgress,
            Collection<?> batchWorkQueue,
            Collection<?> allQueuedOrInProgressKeys,
            Map<?, ?> errorCache
    ) {
        templateCache.clear();
        pendingQueue.clear();
        inProgress.clear();
        batchWorkQueue.clear();
        allQueuedOrInProgressKeys.clear();
        errorCache.clear();
    }
}
