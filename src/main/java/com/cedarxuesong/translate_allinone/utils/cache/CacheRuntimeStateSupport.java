package com.cedarxuesong.translate_allinone.utils.cache;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class CacheRuntimeStateSupport<K, B> {
    enum LookupStatus {
        TRANSLATED,
        IN_PROGRESS,
        PENDING,
        ERROR,
        NOT_CACHED
    }

    record LookupState(LookupStatus status, String translation, String errorMessage) {
    }

    private final Map<K, String> templateCache;
    private final CacheKeyQueueSupport<K, B> keyQueueSupport;

    CacheRuntimeStateSupport(Map<K, String> templateCache, CacheKeyQueueSupport<K, B> keyQueueSupport) {
        this.templateCache = templateCache;
        this.keyQueueSupport = keyQueueSupport;
    }

    void resetForLoad() {
        templateCache.clear();
        keyQueueSupport.resetForLoad();
    }

    void putLoadedEntries(Map<K, String> entries) {
        templateCache.putAll(entries);
    }

    Map<K, String> templateCache() {
        return templateCache;
    }

    LookupState peek(K key) {
        String translation = templateCache.get(key);
        if (translation != null && !translation.isEmpty()) {
            return new LookupState(LookupStatus.TRANSLATED, translation, null);
        }

        String errorMessage = keyQueueSupport.getError(key);
        if (errorMessage != null) {
            return new LookupState(LookupStatus.ERROR, "", errorMessage);
        }

        if (keyQueueSupport.isInProgress(key)) {
            return new LookupState(LookupStatus.IN_PROGRESS, "", null);
        }

        if (keyQueueSupport.isQueuedOrInProgress(key)) {
            return new LookupState(LookupStatus.PENDING, "", null);
        }

        return new LookupState(LookupStatus.NOT_CACHED, "", null);
    }

    LookupState lookupOrQueue(K key) {
        LookupState lookupState = peek(key);
        if (lookupState.status() != LookupStatus.NOT_CACHED) {
            return lookupState;
        }

        keyQueueSupport.enqueueIfAbsent(key);
        return new LookupState(LookupStatus.PENDING, "", null);
    }

    void promoteTranslation(K key, String translation) {
        templateCache.put(key, translation);
        keyQueueSupport.finishKeys(Set.of(key));
    }

    void updateTranslations(Map<K, String> translations) {
        templateCache.putAll(translations);
        keyQueueSupport.finishKeys(translations.keySet());
    }

    int forceRefresh(Iterable<K> keys) {
        if (keys == null) {
            return 0;
        }

        int refreshedCount = 0;
        for (K key : keys) {
            if (key == null) {
                continue;
            }
            templateCache.remove(key);
            keyQueueSupport.requeueToFront(key);
            refreshedCount++;
        }
        return refreshedCount;
    }

    long translatedCount() {
        return templateCache.values().stream()
                .filter(value -> value != null && !value.isEmpty())
                .count();
    }

    int totalCount() {
        Set<K> allKeys = new HashSet<>(templateCache.keySet());
        keyQueueSupport.appendTrackedKeysTo(allKeys);
        return allKeys.size();
    }

    Set<K> copyErroredKeys() {
        return keyQueueSupport.copyErroredKeys();
    }

    ListAccess<K, B> queues() {
        return new ListAccess<>(keyQueueSupport);
    }

    record ListAccess<K, B>(CacheKeyQueueSupport<K, B> keyQueueSupport) {
        ListAccess {
        }

        void clearPendingAndInProgress() {
            keyQueueSupport.clearPendingAndInProgress();
        }

        boolean isPendingQueueEmpty() {
            return keyQueueSupport.isPendingQueueEmpty();
        }

        java.util.List<K> drainAllPendingItems() {
            return keyQueueSupport.drainAllPendingItems();
        }

        void submitBatch(B batch) {
            keyQueueSupport.submitBatch(batch);
        }

        B takeBatch() throws InterruptedException {
            return keyQueueSupport.takeBatch();
        }

        void markAsInProgress(Collection<K> keys) {
            keyQueueSupport.markAsInProgress(keys);
        }

        boolean requeueFromError(K key) {
            return keyQueueSupport.requeueFromError(key);
        }

        void releaseInProgress(Set<K> keys) {
            keyQueueSupport.releaseInProgress(keys);
        }

        void markErrored(Collection<K> failedKeys, String errorMessage, String fallbackErrorMessage) {
            keyQueueSupport.markErrored(failedKeys, errorMessage, fallbackErrorMessage);
        }

        int pendingSize() {
            return keyQueueSupport.pendingSize();
        }

        int batchQueueSize() {
            return keyQueueSupport.batchQueueSize();
        }

        int inProgressSize() {
            return keyQueueSupport.inProgressSize();
        }

        int erroredSize() {
            return keyQueueSupport.erroredSize();
        }

        int queuedOrInProgressSize() {
            return keyQueueSupport.queuedOrInProgressSize();
        }
    }
}
