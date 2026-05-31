package org.mockserver.mock.replay;

import org.mockserver.model.HttpResponse;

/**
 * Callback invoked when a replayed request completes (or fails).
 */
@FunctionalInterface
public interface ReplayResultCallback {
    void onResult(HttpResponse response, Throwable error);
}
