/* Licensed under EPL-2.0 2026. */
package edu.kit.kastel.sdq.intelligrade.listeners;

import java.util.EventListener;

import com.intellij.util.messages.Topic;
import edu.kit.kastel.sdq.artemis4j.grading.ProgrammingExercise;
import edu.kit.kastel.sdq.artemis4j.grading.penalty.GradingConfig;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Project-level listener that is notified when the config or exercise changes.
 *
 * <p>The plugin publishes these events on the EDT because most subscribers update UI state.
 */
public interface ExerciseListener extends EventListener {
    @Topic.ProjectLevel
    Topic<ExerciseListener> TOPIC = Topic.create("Exercise might have changed", ExerciseListener.class);

    default void exerciseChanged(@Nullable ProgrammingExercise exercise) {}

    default void configChanged(GradingConfig.@NonNull GradingConfigDTO config) {}
}
