/* Licensed under EPL-2.0 2024. */
package edu.kit.kastel.listeners;

import java.io.File;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import edu.kit.kastel.exceptions.ImplementationMissing;
import edu.kit.kastel.extensions.settings.ArtemisSettingsState;
import edu.kit.kastel.sdq.artemis4j.grading.config.JsonFileConfig;
import edu.kit.kastel.utils.AssessmentUtils;

/**
 * This class handles everything related to the grading config and related UI events.
 * It loads and parses the config and updates the UI.
 */
public class GradingConfigSelectedListener implements DocumentListener {

    private final TextFieldWithBrowseButton gradingConfigInput;

    public GradingConfigSelectedListener(TextFieldWithBrowseButton gradingConfigInput) {
        this.gradingConfigInput = gradingConfigInput;
    }

    @Override
    public void insertUpdate(DocumentEvent documentEvent) {
        String gradingConfigPath = gradingConfigInput.getText();
        // store saved grading config path
        ArtemisSettingsState settings = ArtemisSettingsState.getInstance();
        settings.setSelectedGradingConfigPath(gradingConfigPath);

        // parse JSON Data and make it accessible to the listeners
        JsonFileConfig gradingConfig = new JsonFileConfig(new File(gradingConfigPath));
        ExerciseSelectedListener.updateJsonConfig(gradingConfig);
        AssessmentUtils.initExerciseConfig(gradingConfig);
    }

    @Override
    public void removeUpdate(DocumentEvent documentEvent) {
        ArtemisSettingsState.getInstance().setSelectedGradingConfigPath(null);
    }

    @Override
    public void changedUpdate(DocumentEvent documentEvent) {
        throw new ImplementationMissing(
                "Wrong event `GradingConfigSelectedListener::changedUpdate` " + "called. This requires bug fixing!");
    }
}
