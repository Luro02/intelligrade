/* Licensed under EPL-2.0 2024-2026. */
package edu.kit.kastel.sdq.intelligrade.listeners;

import java.nio.file.Path;
import java.util.Arrays;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopes;
import com.intellij.psi.search.PsiShortNamesCache;
import edu.kit.kastel.sdq.intelligrade.AssessmentTracker;
import edu.kit.kastel.sdq.intelligrade.extensions.settings.ArtemisSettingsState;
import edu.kit.kastel.sdq.intelligrade.state.ActiveAssessment;
import edu.kit.kastel.sdq.intelligrade.state.ProjectState;
import org.jspecify.annotations.Nullable;

@Service(Service.Level.PROJECT)
public final class FileOpener implements DumbService.DumbModeListener, Disposable {
    private static final Logger LOG = Logger.getInstance(FileOpener.class);

    private volatile boolean openClassesNextTime = false;

    private final Project project;
    private final ProjectState projectState;

    public static FileOpener getInstance(Project project) {
        return project.getService(FileOpener.class);
    }

    public FileOpener(Project project) {
        this.project = project;
        this.projectState = ProjectState.getInstance(project);

        AssessmentTracker.getInstance(project).subscribeNoInit(this, new AssessmentStateListener() {
            @Override
            public void activeAssessmentChanged(@Nullable ActiveAssessment assessment) {
                var settings = ArtemisSettingsState.getInstance();
                // Relevant if building indices is not finished before the assessment is closed
                synchronized (FileOpener.this) {
                    openClassesNextTime = assessment != null && settings.isAutoOpenMainClass();
                }
            }
        });
    }

    @Override
    public void dispose() {
        synchronized (this) {
            openClassesNextTime = false;
        }
    }

    @Override
    public void exitDumbMode() {
        if (!openClassesNextTime || !projectState.isAssessing()) {
            return;
        }

        // Open the main class
        // Do this in the background because it may cause a synchronous vfs refresh
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            synchronized (this) {
                if (!openClassesNextTime || !projectState.isAssessing()) {
                    return;
                }

                openClassesNextTime = false;
            }

            var projectRoot = Path.of(project.getBasePath() == null ? "" : project.getBasePath());
            // Only look in assignment/, we aren't interested in test classes
            var directory = VfsUtil.findFile(projectRoot.resolve(ActiveAssessment.ASSIGNMENT_SUB_PATH), true);

            if (directory == null) {
                LOG.warn("Can't resolve assignment directory");
                return;
            }

            // Even though we exited dumb mode, the index operations below may throw IndexNotReadyExceptions,
            // so defensively wrap this in a smart mode action

            // - pauses current thread until dumb mode is finished
            // - runs the provided runnable
            // -> it is guaranteed that indexes are available for the runnable
            // might throw a ProcessCanceledException if the project is closed during dumb mode

            // This returns a value that is unused, because they deprecated the method
            // that takes a Runnable and the documentation says to use a Callable instead
            ReadAction.nonBlocking(() -> {
                        findAnOpenMainMethod(project, directory);
                        return 0;
                    })
                    .inSmartMode(project)
                    .executeSynchronously();
        });
    }

    private static void findAnOpenMainMethod(Project project, VirtualFile directory) {
        var scope = GlobalSearchScopes.directoryScope(project, directory, true);
        var mainMethods = PsiShortNamesCache.getInstance(project).getMethodsByName("main", scope);

        PsiType stringType =
                PsiType.getJavaLangString(PsiManager.getInstance(project), GlobalSearchScope.allScope(project));
        for (var method : mainMethods) {
            // Is public & static & returns void
            var modifiers = method.getModifierList();
            if (!modifiers.hasExplicitModifier(PsiModifier.PUBLIC)
                    || !modifiers.hasExplicitModifier(PsiModifier.STATIC)
                    || !PsiTypes.voidType().equals(method.getReturnType())) {
                continue;
            }

            // Single parameter of type String[] or String...
            var parameters = method.getParameterList();
            if (parameters.getParametersCount() != 1) {
                continue;
            }

            var parameter = parameters.getParameters()[0];
            var type = parameter.getType();

            // This should also cover varargs, since PsiEllipsisType is a subtype of PsiArrayType
            if (type instanceof PsiArrayType arrayType) {
                if (!stringType.equals(arrayType.getComponentType())) {
                    continue;
                }
            } else {
                continue;
            }

            // All checks passed, this is a main method!
            openFile(project, method.getContainingFile().getVirtualFile(), method.getTextOffset());
            return;
        }

        LOG.info("No main class found");

        // if it could not find a main class, open the first class in the directory:
        for (String className : PsiShortNamesCache.getInstance(project).getAllClassNames()) {
            var psiClass = Arrays.stream(PsiShortNamesCache.getInstance(project).getClassesByName(className, scope))
                    .findFirst();
            if (psiClass.isPresent()) {
                var file = psiClass.get().getContainingFile().getVirtualFile();
                openFile(project, file, 0);
                return;
            }
        }
    }

    private static void openFile(Project project, VirtualFile file, int offset) {
        ApplicationManager.getApplication().invokeLater(() -> {
            // Open the file in an editor, and place the caret at the main method's declaration
            FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, offset), true);

            // Expand the project view and select the file
            ProjectView.getInstance(project).select(null, file, true);
        });
    }
}
