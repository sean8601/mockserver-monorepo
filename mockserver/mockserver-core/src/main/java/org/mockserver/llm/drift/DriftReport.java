package org.mockserver.llm.drift;

import java.util.List;

/**
 * Result of a drift-detection run: one {@link ExchangeDrift} per recorded
 * exchange that was replayed against the live provider, plus rollup counts.
 */
public final class DriftReport {

    /** Per-exchange structural-drift outcome. */
    public static final class ExchangeDrift {
        public enum Status {
            NO_DRIFT,
            DRIFT,
            COULD_NOT_CHECK
        }

        private final int index;
        private final Status status;
        private final List<String> addedPaths;
        private final List<String> removedPaths;
        private final List<String> typeChangedPaths;
        private final String note;

        ExchangeDrift(int index, Status status, List<String> addedPaths, List<String> removedPaths,
                      List<String> typeChangedPaths, String note) {
            this.index = index;
            this.status = status;
            this.addedPaths = addedPaths;
            this.removedPaths = removedPaths;
            this.typeChangedPaths = typeChangedPaths;
            this.note = note;
        }

        public int getIndex() {
            return index;
        }

        public Status getStatus() {
            return status;
        }

        public List<String> getAddedPaths() {
            return addedPaths;
        }

        public List<String> getRemovedPaths() {
            return removedPaths;
        }

        public List<String> getTypeChangedPaths() {
            return typeChangedPaths;
        }

        public String getNote() {
            return note;
        }
    }

    private final List<ExchangeDrift> exchanges;

    public DriftReport(List<ExchangeDrift> exchanges) {
        this.exchanges = exchanges;
    }

    public List<ExchangeDrift> getExchanges() {
        return exchanges;
    }

    public long driftedCount() {
        return exchanges.stream().filter(e -> e.getStatus() == ExchangeDrift.Status.DRIFT).count();
    }

    public long checkedCount() {
        return exchanges.stream().filter(e -> e.getStatus() != ExchangeDrift.Status.COULD_NOT_CHECK).count();
    }

    public long couldNotCheckCount() {
        return exchanges.stream().filter(e -> e.getStatus() == ExchangeDrift.Status.COULD_NOT_CHECK).count();
    }

    public boolean hasDrift() {
        return driftedCount() > 0;
    }
}
