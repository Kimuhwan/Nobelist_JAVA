package com.scientificlens.harness.runtime;

import com.scientificlens.harness.domain.AgentEvent;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Per-run hooks handed to runtimes: cooperative cancellation and live token streaming. */
public interface StageContext {
    StageContext NONE = new StageContext() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void onToken(String token) {}
    };

    boolean isCancelled();

    /** Receives generated text as it streams (LLM runtimes only). */
    void onToken(String token);

    default void onLensesSelected(List<String> lensIds) {}

    default void onAgentEvent(AgentEvent event) {}

    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("analysis cancelled");
        }
    }
}
