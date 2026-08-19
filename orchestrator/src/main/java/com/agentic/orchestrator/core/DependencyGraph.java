package com.agentic.orchestrator.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAG of {@link Stage}s. Groups stages into levels (Kahn's algorithm) so
 * independent stages can run in parallel while dependent ones stay in order.
 */
public final class DependencyGraph {

    private final Map<String, Stage> stagesById = new LinkedHashMap<>();

    public DependencyGraph addStage(Stage stage) {
        stagesById.put(stage.id(), stage);
        return this;
    }

    public Stage get(String id) {
        return stagesById.get(id);
    }

    public Collection<Stage> stages() {
        return stagesById.values();
    }

    public void validate() {
        for (Stage s : stagesById.values()) {
            for (String dep : s.dependsOn()) {
                if (!stagesById.containsKey(dep)) {
                    throw new IllegalStateException("Stage '" + s.id() + "' depends on unknown stage '" + dep + "'");
                }
            }
        }
        // this also catches cycles, since computeLevels can't finish if there's one
        computeLevels(Set.of());
    }

    /** Groups stages into ordered levels, skipping anything already in {@code alreadySatisfied}. */
    public List<List<Stage>> computeLevels(Set<String> alreadySatisfied) {
        Map<String, Integer> remainingDeps = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (Stage s : stagesById.values()) {
            dependents.putIfAbsent(s.id(), new ArrayList<>());
        }
        for (Stage s : stagesById.values()) {
            long unsatisfied = s.dependsOn().stream().filter(d -> !alreadySatisfied.contains(d)).count();
            remainingDeps.put(s.id(), (int) unsatisfied);
            for (String dep : s.dependsOn()) {
                if (!alreadySatisfied.contains(dep)) {
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(s.id());
                }
            }
        }

        List<List<Stage>> levels = new ArrayList<>();
        Set<String> done = new java.util.HashSet<>(alreadySatisfied);
        int remaining = stagesById.size() - alreadySatisfied.size();

        while (remaining > 0) {
            List<Stage> level = new ArrayList<>();
            for (var entry : remainingDeps.entrySet()) {
                if (!done.contains(entry.getKey()) && entry.getValue() == 0) {
                    level.add(stagesById.get(entry.getKey()));
                }
            }
            if (level.isEmpty()) {
                throw new IllegalStateException("Cycle detected in dependency graph among: " +
                        remainingDeps.keySet().stream().filter(k -> !done.contains(k)).toList());
            }
            for (Stage s : level) {
                done.add(s.id());
                remaining--;
                for (String dependent : dependents.getOrDefault(s.id(), List.of())) {
                    remainingDeps.merge(dependent, -1, Integer::sum);
                }
            }
            levels.add(level);
        }
        return levels;
    }

    /** All stage ids that transitively depend on {@code stageId} (exclusive). */
    public Set<String> transitiveDependents(String stageId) {
        Set<String> result = new java.util.LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Stage s : stagesById.values()) {
                if (result.contains(s.id())) continue;
                boolean dependsOnTarget = s.dependsOn().contains(stageId) ||
                        s.dependsOn().stream().anyMatch(result::contains);
                if (dependsOnTarget) {
                    result.add(s.id());
                    changed = true;
                }
            }
        }
        return result;
    }
}
