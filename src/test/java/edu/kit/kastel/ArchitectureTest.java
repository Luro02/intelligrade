/* Licensed under EPL-2.0 2024-2026. */
package edu.kit.kastel;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.intellij.openapi.Disposable;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import edu.kit.kastel.sdq.intelligrade.state.ProjectState;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "edu.kit.kastel.sdq.intelligrade")
class ArchitectureTest {
    @ArchTest
    static final ArchRule noForEachInCollectionsOrStream = noClasses()
            .should()
            .callMethod(Stream.class, "forEach", Consumer.class)
            .orShould()
            .callMethod(Stream.class, "forEachOrdered", Consumer.class)
            .orShould()
            .callMethod(List.class, "forEach", Consumer.class)
            .orShould()
            .callMethod(List.class, "forEachOrdered", Consumer.class)
            .because("Lambdas should be functional. ForEach is typically used for side-effects.");

    @ArchTest
    static final ArchRule noOptionalAsParameter = noMethods()
            .should()
            .haveRawParameterTypes(Optional.class)
            .because("Optional should be used as return type only.");

    @Test
    void pluginStateListenerRegistrationRequiresParentDisposable() {
        for (var method : ProjectState.class.getDeclaredMethods()) {
            if (!method.getName().matches("register.*Listeners?")) {
                continue;
            }

            assertTrue(
                    List.of(method.getParameterTypes()).contains(Disposable.class),
                    () -> "Listener registration method must accept a parent Disposable: " + method);
        }
    }
}
