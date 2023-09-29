package edu.kit.kastel.utils;

import edu.kit.kastel.sdq.artemis4j.grading.model.annotation.Annotation;
import edu.kit.kastel.sdq.artemis4j.grading.model.annotation.AnnotationException;
import edu.kit.kastel.sdq.artemis4j.grading.model.annotation.AnnotationManagement;
import edu.kit.kastel.wrappers.EventListener;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Holds all information and meta on assessments that are required globally.
 * Some Information are:
 * - all assessment annotations
 * - whether assessment mode is currently enabled
 */
public final class AssessmentUtils {
  private static boolean assesmentMode = false;

  private static List<EventListener> assesmentListeners = new ArrayList<>();

  private static AnnotationManagement annotationManager = new AnnotationManagement();

  private static Annotation latestAnnotation;

  private AssessmentUtils() {
    throw new IllegalAccessError("Utility Class constructor");
  }

  public static void enabeleAssessmentMode() {
    AssessmentUtils.assesmentMode = true;
    AssessmentUtils.resetAnnotations();
  }

  public static void disableAssessmentMode() {
    AssessmentUtils.assesmentMode = false;
  }

  public static boolean isAssesmentMode() {
    return AssessmentUtils.assesmentMode;
  }

  /**
   * add an annotation and update all listening components.
   *
   * @param annotation the annotation to be added
   * @throws AnnotationException if adding the annotation fails
   */
  public static void addAnnotation(@NotNull Annotation annotation) throws AnnotationException {

    AssessmentUtils.annotationManager.addAnnotation(
            annotation.getUUID(),
            annotation.getMistakeType(),
            annotation.getStartLine(),
            annotation.getEndLine(),
            annotation.getClassFilePath(),
            annotation.getCustomMessage().orElse(""),
            annotation.getCustomPenalty().orElse(0.0)
    );

    //add latest annotation so UI can use it
    latestAnnotation = annotation;

    //trigger each event so that all assessment views are updated
    assesmentListeners.forEach(EventListener::trigger);
  }

  public static void resetAnnotations() {
    AssessmentUtils.annotationManager = new AnnotationManagement();
  }

  public static void registerAssessmentListener(EventListener assessmentListener) {
    AssessmentUtils.assesmentListeners.add(assessmentListener);
  }

  public static void resetAssessmentListeners(){
    assesmentListeners = new ArrayList<>();
  }

  public static Annotation getLatestAnnotation() {
    return latestAnnotation;
  }
}
