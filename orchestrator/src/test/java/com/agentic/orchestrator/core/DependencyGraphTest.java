package com.agentic.orchestrator.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyGraphTest {

    @Test
    void detectsCycles() {
        TestStage a = TestStage.alwaysSucceeds("a", List.of("b"));
        TestStage b = TestStage.alwaysSucceeds("b", List.of("a"));
        DependencyGraph graph = new DependencyGraph().addStage(a).addStage(b);

        assertThrows(IllegalStateException.class, graph::validate);
    }

    @Test
    void rejectsDependencyOnUnknownStage() {
        TestStage a = TestStage.alwaysSucceeds("a", List.of("does-not-exist"));
        DependencyGraph graph = new DependencyGraph().addStage(a);

        assertThrows(IllegalStateException.class, graph::validate);
    }

    @Test
    void groupsIndependentStagesIntoTheSameLevel() {
        TestStage a = TestStage.alwaysSucceeds("a", List.of());
        TestStage b = TestStage.alwaysSucceeds("b", List.of("a"));
        TestStage c = TestStage.alwaysSucceeds("c", List.of("a"));
        TestStage d = TestStage.alwaysSucceeds("d", List.of("b", "c"));
        DependencyGraph graph = new DependencyGraph().addStage(a).addStage(b).addStage(c).addStage(d);

        List<List<Stage>> levels = graph.computeLevels(Set.of());

        assertEquals(3, levels.size());
        assertEquals(1, levels.get(0).size());
        assertEquals(2, levels.get(1).size(), "b and c are independent and must share a level");
        assertEquals(1, levels.get(2).size());
    }

    @Test
    void transitiveDependentsIncludesIndirectDescendants() {
        TestStage a = TestStage.alwaysSucceeds("a", List.of());
        TestStage b = TestStage.alwaysSucceeds("b", List.of("a"));
        TestStage c = TestStage.alwaysSucceeds("c", List.of("b"));
        TestStage unrelated = TestStage.alwaysSucceeds("unrelated", List.of());
        DependencyGraph graph = new DependencyGraph().addStage(a).addStage(b).addStage(c).addStage(unrelated);

        Set<String> dependents = graph.transitiveDependents("a");

        assertTrue(dependents.containsAll(Set.of("b", "c")));
        assertTrue(!dependents.contains("unrelated"));
    }
}
