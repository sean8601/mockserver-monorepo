package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.HttpChaosProfile;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.HttpChaosProfileDTO;
import org.mockserver.time.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Orchestrates scheduled multi-stage chaos experiments. An experiment is an
 * ordered sequence of stages, each applying a service-scoped chaos profile to
 * one or more hosts for a specified duration, progressing automatically. When
 * the last stage completes, chaos is cleared (or the experiment loops if
 * configured).
 *
 * <p><b>C1 auto-halt integration:</b> If {@link ChaosAutoHaltMonitor} halts
 * chaos mid-experiment (by calling {@link ServiceChaosRegistry#reset()}), the
 * orchestrator detects the empty registry at the next stage advance and stops
 * the experiment. An experiment stopped by auto-halt is reported with status
 * {@code "halted_by_auto_halt"}.
 *
 * <p><b>Safety limits:</b>
 * <ul>
 *   <li>Maximum {@value #MAX_STAGES} stages per experiment</li>
 *   <li>Maximum stage duration: {@value #MAX_STAGE_DURATION_MILLIS} ms (24 hours)</li>
 *   <li>Only one experiment may be active at a time</li>
 *   <li>Stopping an experiment is idempotent</li>
 * </ul>
 *
 * <p>The orchestrator uses a single-thread {@link ScheduledExecutorService}
 * for non-blocking stage advancement. It never blocks the Netty event loop.
 * Time is measured via a pluggable {@link LongSupplier} clock (defaults to
 * {@link TimeService#currentTimeMillis()}) so tests can drive advancement
 * deterministically without wall-clock sleeps.
 *
 * <p>The singleton instance is shared process-wide, consistent with
 * {@link ServiceChaosRegistry}'s singleton pattern.
 */
public class ChaosExperimentOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(ChaosExperimentOrchestrator.class);

    /** Maximum number of stages in an experiment definition (DoS bound). */
    static final int MAX_STAGES = 50;
    /** Maximum duration for a single stage: 24 hours in milliseconds. */
    static final long MAX_STAGE_DURATION_MILLIS = 24L * 60 * 60 * 1000;

    private static final ChaosExperimentOrchestrator INSTANCE = new ChaosExperimentOrchestrator(
        TimeService::currentTimeMillis,
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chaos-experiment-scheduler");
            t.setDaemon(true);
            return t;
        })
    );

    private final LongSupplier clock;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<RunningExperiment> current = new AtomicReference<>(null);

    ChaosExperimentOrchestrator(LongSupplier clock, ScheduledExecutorService scheduler) {
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public static ChaosExperimentOrchestrator getInstance() {
        return INSTANCE;
    }

    /**
     * Starts an experiment. Returns a validation error message if the definition
     * is invalid, or {@code null} on success. Only one experiment may be active
     * at a time; starting a new one while one is running stops the previous one.
     */
    public String start(ExperimentDefinition definition) {
        // Validate
        String error = validate(definition);
        if (error != null) {
            return error;
        }
        // Stop any existing experiment
        stopInternal(false);

        RunningExperiment experiment = new RunningExperiment(definition, clock.getAsLong());
        current.set(experiment);

        // Apply stage 0
        applyStage(experiment, 0);

        // Schedule advancement to stage 1 (if there are more stages)
        scheduleNextStage(experiment, 0);

        LOG.info("chaos experiment '{}' started with {} stage(s), loop={}",
            definition.name, definition.stages.size(), definition.loop);

        return null;
    }

    /**
     * Stops the current experiment and clears all chaos from the registry.
     * Idempotent: no-op if no experiment is running.
     */
    public void stop() {
        stopInternal(false);
    }

    /**
     * Resets the orchestrator: stops any running experiment and clears chaos.
     * Called on server reset.
     */
    public void reset() {
        stopInternal(false);
    }

    /**
     * Returns the current experiment status, or {@code null} if no experiment
     * is running or was recently active.
     */
    public ExperimentStatus getStatus() {
        RunningExperiment exp = current.get();
        if (exp == null) {
            return null;
        }
        long now = clock.getAsLong();
        long stageElapsed = now - exp.stageStartedAtMillis;
        Stage currentStage = exp.currentStageIndex < exp.definition.stages.size()
            ? exp.definition.stages.get(exp.currentStageIndex) : null;
        long stageRemaining = currentStage != null
            ? Math.max(0, currentStage.durationMillis - stageElapsed) : 0;
        return new ExperimentStatus(
            exp.definition.name,
            exp.status,
            exp.currentStageIndex,
            exp.definition.stages.size(),
            stageElapsed,
            stageRemaining,
            exp.loopIteration,
            now - exp.startedAtMillis,
            exp.definition
        );
    }

    // -- Internal methods --

    private void stopInternal(boolean autoHalted) {
        RunningExperiment exp = current.getAndSet(null);
        if (exp != null) {
            ScheduledFuture<?> pending = exp.pendingAdvance;
            if (pending != null) {
                pending.cancel(false);
            }
            exp.status = autoHalted ? "halted_by_auto_halt" : "stopped";
            // Clear chaos from the registry
            ServiceChaosRegistry.getInstance().reset();
            LOG.info("chaos experiment '{}' {}", exp.definition.name, exp.status);
        }
    }

    private void applyStage(RunningExperiment experiment, int stageIndex) {
        Stage stage = experiment.definition.stages.get(stageIndex);
        experiment.currentStageIndex = stageIndex;
        experiment.stageStartedAtMillis = clock.getAsLong();
        experiment.status = "running";

        // Clear previous stage's chaos, then apply new stage's profiles
        ServiceChaosRegistry registry = ServiceChaosRegistry.getInstance();
        registry.reset();

        for (Map.Entry<String, HttpChaosProfile> entry : stage.profiles.entrySet()) {
            registry.put(entry.getKey(), entry.getValue());
        }
    }

    private void scheduleNextStage(RunningExperiment experiment, int currentStageIndex) {
        Stage stage = experiment.definition.stages.get(currentStageIndex);
        long delayMillis = stage.durationMillis;

        ScheduledFuture<?> future = scheduler.schedule(() -> advanceStage(experiment), delayMillis, TimeUnit.MILLISECONDS);
        experiment.pendingAdvance = future;
    }

    private void advanceStage(RunningExperiment experiment) {
        // Check if experiment was stopped or replaced
        if (current.get() != experiment) {
            return;
        }

        // C1 auto-halt integration: if the registry was cleared by auto-halt
        // while this stage was running, stop the experiment
        if (ServiceChaosRegistry.getInstance().entries().isEmpty() && experiment.status.equals("running")) {
            LOG.warn("chaos experiment '{}' halted by auto-halt safety circuit-breaker at stage {}",
                experiment.definition.name, experiment.currentStageIndex);
            experiment.status = "halted_by_auto_halt";
            current.compareAndSet(experiment, null);
            return;
        }

        int nextStageIndex = experiment.currentStageIndex + 1;

        if (nextStageIndex >= experiment.definition.stages.size()) {
            // End of stages
            if (experiment.definition.loop) {
                // Loop back to stage 0
                experiment.loopIteration++;
                applyStage(experiment, 0);
                scheduleNextStage(experiment, 0);
                LOG.info("chaos experiment '{}' looping, iteration {}",
                    experiment.definition.name, experiment.loopIteration);
            } else {
                // Experiment complete
                experiment.status = "completed";
                ServiceChaosRegistry.getInstance().reset();
                current.compareAndSet(experiment, null);
                LOG.info("chaos experiment '{}' completed after {} stage(s)",
                    experiment.definition.name, experiment.definition.stages.size());
            }
        } else {
            // Advance to next stage
            applyStage(experiment, nextStageIndex);
            scheduleNextStage(experiment, nextStageIndex);
            LOG.info("chaos experiment '{}' advanced to stage {}/{}",
                experiment.definition.name, nextStageIndex + 1, experiment.definition.stages.size());
        }
    }

    /**
     * Forces immediate advancement to the next stage, bypassing the scheduler.
     * Used by tests to drive stage transitions deterministically without
     * wall-clock sleeps. No-op if no experiment is running.
     */
    void advanceNow() {
        RunningExperiment exp = current.get();
        if (exp != null) {
            ScheduledFuture<?> pending = exp.pendingAdvance;
            if (pending != null) {
                pending.cancel(false);
            }
            advanceStage(exp);
        }
    }

    private String validate(ExperimentDefinition definition) {
        if (definition == null) {
            return "'experiment' is required";
        }
        if (definition.name == null || definition.name.isBlank()) {
            return "'name' is required";
        }
        if (definition.stages == null || definition.stages.isEmpty()) {
            return "'stages' must contain at least one stage";
        }
        if (definition.stages.size() > MAX_STAGES) {
            return "'stages' exceeds maximum of " + MAX_STAGES;
        }
        for (int i = 0; i < definition.stages.size(); i++) {
            Stage stage = definition.stages.get(i);
            if (stage.durationMillis <= 0) {
                return "stage[" + i + "].durationMillis must be > 0";
            }
            if (stage.durationMillis > MAX_STAGE_DURATION_MILLIS) {
                return "stage[" + i + "].durationMillis exceeds maximum of " + MAX_STAGE_DURATION_MILLIS + " ms (24h)";
            }
            if (stage.profiles == null || stage.profiles.isEmpty()) {
                return "stage[" + i + "] must have at least one host/profile entry";
            }
        }
        return null;
    }

    // -- Data classes --

    /**
     * An experiment definition: name, ordered stages, and whether to loop.
     */
    public static class ExperimentDefinition {
        public final String name;
        public final List<Stage> stages;
        public final boolean loop;

        public ExperimentDefinition(String name, List<Stage> stages, boolean loop) {
            this.name = name;
            this.stages = stages != null ? Collections.unmodifiableList(new ArrayList<>(stages)) : Collections.emptyList();
            this.loop = loop;
        }

        /**
         * Deserializes an experiment definition from a JSON node.
         */
        public static ExperimentDefinition fromJson(JsonNode node) {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            String name = node.path("name").asText(null);
            boolean loop = node.path("loop").asBoolean(false);
            List<Stage> stages = new ArrayList<>();
            JsonNode stagesNode = node.path("stages");
            if (stagesNode.isArray()) {
                for (JsonNode stageNode : stagesNode) {
                    long durationMillis = stageNode.path("durationMillis").asLong(0);
                    Map<String, HttpChaosProfile> profiles = new java.util.LinkedHashMap<>();
                    JsonNode profilesNode = stageNode.path("profiles");
                    if (profilesNode.isObject()) {
                        var fields = profilesNode.fields();
                        while (fields.hasNext()) {
                            var entry = fields.next();
                            try {
                                HttpChaosProfileDTO dto = mapper.treeToValue(entry.getValue(), HttpChaosProfileDTO.class);
                                profiles.put(entry.getKey(), dto.buildObject());
                            } catch (Exception e) {
                                throw new IllegalArgumentException(
                                    "invalid chaos profile for host '" + entry.getKey() + "': " + e.getMessage(), e);
                            }
                        }
                    }
                    stages.add(new Stage(durationMillis, profiles));
                }
            }
            return new ExperimentDefinition(name, stages, loop);
        }

        /**
         * Serializes this experiment definition to a JSON node.
         */
        public ObjectNode toJson() {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("name", name);
            node.put("loop", loop);
            ArrayNode stagesArray = node.putArray("stages");
            for (Stage stage : stages) {
                ObjectNode stageNode = mapper.createObjectNode();
                stageNode.put("durationMillis", stage.durationMillis);
                ObjectNode profilesNode = stageNode.putObject("profiles");
                for (Map.Entry<String, HttpChaosProfile> entry : stage.profiles.entrySet()) {
                    profilesNode.set(entry.getKey(), mapper.valueToTree(new HttpChaosProfileDTO(entry.getValue())));
                }
                stagesArray.add(stageNode);
            }
            return node;
        }
    }

    /**
     * A single stage: profiles to apply to specific hosts for a duration.
     */
    public static class Stage {
        public final long durationMillis;
        public final Map<String, HttpChaosProfile> profiles;

        public Stage(long durationMillis, Map<String, HttpChaosProfile> profiles) {
            this.durationMillis = durationMillis;
            this.profiles = profiles != null
                ? Collections.unmodifiableMap(new java.util.LinkedHashMap<>(profiles))
                : Collections.emptyMap();
        }
    }

    /**
     * Snapshot of the current experiment status.
     */
    public static class ExperimentStatus {
        public final String name;
        public final String status;
        public final int currentStageIndex;
        public final int totalStages;
        public final long stageElapsedMillis;
        public final long stageRemainingMillis;
        public final int loopIteration;
        public final long totalElapsedMillis;
        public final ExperimentDefinition definition;

        public ExperimentStatus(String name, String status, int currentStageIndex, int totalStages,
                                long stageElapsedMillis, long stageRemainingMillis,
                                int loopIteration, long totalElapsedMillis,
                                ExperimentDefinition definition) {
            this.name = name;
            this.status = status;
            this.currentStageIndex = currentStageIndex;
            this.totalStages = totalStages;
            this.stageElapsedMillis = stageElapsedMillis;
            this.stageRemainingMillis = stageRemainingMillis;
            this.loopIteration = loopIteration;
            this.totalElapsedMillis = totalElapsedMillis;
            this.definition = definition;
        }

        public ObjectNode toJson() {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("name", name);
            node.put("status", status);
            node.put("currentStageIndex", currentStageIndex);
            node.put("totalStages", totalStages);
            node.put("stageElapsedMillis", stageElapsedMillis);
            node.put("stageRemainingMillis", stageRemainingMillis);
            node.put("loopIteration", loopIteration);
            node.put("totalElapsedMillis", totalElapsedMillis);
            if (definition != null) {
                node.set("experiment", definition.toJson());
            }
            return node;
        }
    }

    /**
     * Mutable state for a running experiment.
     */
    private static final class RunningExperiment {
        final ExperimentDefinition definition;
        final long startedAtMillis;
        volatile int currentStageIndex;
        volatile long stageStartedAtMillis;
        volatile String status;
        volatile int loopIteration;
        volatile ScheduledFuture<?> pendingAdvance;

        RunningExperiment(ExperimentDefinition definition, long startedAtMillis) {
            this.definition = definition;
            this.startedAtMillis = startedAtMillis;
            this.status = "starting";
            this.loopIteration = 0;
        }
    }
}
