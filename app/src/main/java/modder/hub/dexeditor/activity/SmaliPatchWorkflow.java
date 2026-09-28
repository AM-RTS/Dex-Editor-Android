package modder.hub.dexeditor.activity;

import modder.hub.dexeditor.model.EditorTab;

import android.net.Uri;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import modder.hub.dexeditor.fragment.EditorFragment;
import modder.hub.dexeditor.utils.ClassTree;
import modder.hub.dexeditor.utils.Notify_MT;
import modder.hub.dexeditor.utils.SmaliPatchEngine;
import modder.hub.dexeditor.views.AlertProgress;

/** Owns patch-file parsing, validation, preview, and application for the editor screen. */
final class SmaliPatchWorkflow {
    private final WeakReference<DexEditorActivity> activityRef;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Future<?> worker;
    private volatile AtomicBoolean cancellation;
    private volatile AlertProgress progress;
    private Map<EditorTab, Long> previewTabRevisions = new IdentityHashMap<>();

    SmaliPatchWorkflow(DexEditorActivity activity) {
        activityRef = new WeakReference<>(activity);
    }

    void load(Uri uri) {
        DexEditorActivity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Could not open the selected file.");
            SmaliPatchEngine.Document document = SmaliPatchEngine.read(input);
            String details = "Ordered rules: " + document.rules.size()
                    + "\nMatching is checked against expected occurrence counts before anything is applied.";
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(document.name)
                    .setMessage(details)
                    .setPositiveButton("Preview", (dialog, which) -> prepare(document))
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    void close() {
        AtomicBoolean currentCancellation = cancellation;
        if (currentCancellation != null) currentCancellation.set(true);
        Future<?> currentWorker = worker;
        if (currentWorker != null) currentWorker.cancel(true);
        executor.shutdownNow();
        AlertProgress currentProgress = progress;
        progress = null;
        if (currentProgress != null && currentProgress.isShowing()) currentProgress.dismiss();
        activityRef.clear();
    }

    private void prepare(SmaliPatchEngine.Document document) {
        DexEditorActivity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        ClassTree tree = activity.getClassTree();
        if (tree == null) {
            showError("No DEX is open.");
            return;
        }

        final ClassTree.EditSnapshot snapshot;
        final Map<String, String> openSmali = new HashMap<>();
        try {
            snapshot = tree.snapshotEditState();
        } catch (IllegalStateException e) {
            showError("No DEX is open.");
            return;
        }
        List<EditorTab> tabs = activity.getOpenTabsSnapshot();
        Map<EditorTab, Long> capturedTabRevisions = new IdentityHashMap<>();
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            if (tab.type != 0) continue;
            capturedTabRevisions.put(tab, tab.getContentRevision());
            EditorFragment fragment = activity.getFragmentAtIndex(i);
            openSmali.put(tab.className, fragment != null && fragment.getEditor() != null
                    ? fragment.getEditor().getText().toString() : tab.content);
        }

        AtomicBoolean cancelled = new AtomicBoolean();
        cancellation = cancelled;
        AlertProgress dialog = new AlertProgress(activity);
        progress = dialog;
        dialog.setTitle("Validating patch...");
        dialog.setCancelable(true);
        dialog.setOnCancelListener(() -> cancelled.set(true));
        dialog.show();

        worker = executor.submit(() -> {
            try {
                SmaliPatchEngine.Plan plan = SmaliPatchEngine.prepare(tree, document, openSmali,
                        snapshot.getRevision(), new SmaliPatchEngine.ProgressListener() {
                            @Override
                            public boolean isCancelled() {
                                return cancelled.get() || Thread.currentThread().isInterrupted();
                            }

                            @Override
                            public void onProgress(int processed, int total, String currentClass) {
                                if (processed % 40 != 0 && processed != total) return;
                                post(() -> {
                                    if (dialog.isShowing()) {
                                        dialog.setMessage("Checking " + currentClass);
                                        dialog.setProgress(processed, total);
                                    }
                                });
                            }
                        });
                post(() -> {
                    dismiss(dialog);
                    if (!cancelled.get()) {
                        previewTabRevisions = capturedTabRevisions;
                        showPlan(plan);
                    }
                });
            } catch (SmaliPatchEngine.CancelledException ignored) {
                post(() -> dismiss(dialog));
            } catch (Exception e) {
                post(() -> {
                    dismiss(dialog);
                    if (!cancelled.get()) showError(e.getMessage());
                });
            }
        });
    }

    private void showPlan(SmaliPatchEngine.Plan plan) {
        DexEditorActivity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        StringBuilder summary = new StringBuilder("Validated ")
                .append(plan.getChangedClassCount()).append(" class(es).\n\n");
        for (SmaliPatchEngine.RuleResult result : plan.getRuleResults()) {
            summary.append(result.getId()).append(": ").append(result.getActual())
                    .append(" / ").append(result.getExpected()).append(" expected match(es)\n");
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Apply " + plan.getName() + "?")
                .setMessage(summary)
                .setPositiveButton("Apply", (dialog, which) -> apply(plan))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void apply(SmaliPatchEngine.Plan plan) {
        DexEditorActivity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            for (Map.Entry<EditorTab, Long> entry : previewTabRevisions.entrySet()) {
                if (entry.getKey().getContentRevision() != entry.getValue()) {
                    showError("An open editor changed after the patch preview. Preview the patch again before applying it.");
                    return;
                }
            }
            plan.commit(activity.getClassTree());
            Map<String, String> updatedSmali = plan.getUpdatedSmali();
            List<EditorTab> tabs = activity.getOpenTabsSnapshot();
            for (int i = 0; i < tabs.size(); i++) {
                EditorTab tab = tabs.get(i);
                String updated = tab.type == 0 ? updatedSmali.get(tab.className) : null;
                if (updated == null) continue;
                tab.markCommitted(updated);
                EditorFragment fragment = activity.getFragmentAtIndex(i);
                if (fragment != null && fragment.getEditor() != null) fragment.getEditor().setText(updated);
                if (activity.tabsAdapter != null) activity.tabsAdapter.notifyItemChanged(i + 1);
            }
            activity.needsModifiedTreeRebuild = true;
            activity.needsExplorerRefresh = true;
            activity.refreshExplorerPage(1);
            activity.refreshExplorerPage(0);
            Notify_MT.Notify(activity, "Patch applied", plan.getChangedClassCount()
                    + " class(es) changed. Compile and save to write the DEX files.", "Close");
        } catch (RuntimeException e) {
            showError(e.getMessage());
        }
    }

    private void showError(String message) {
        DexEditorActivity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Patch not applied")
                .setMessage(message == null ? "Unknown patch error." : message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void dismiss(AlertProgress dialog) {
        if (progress == dialog) progress = null;
        if (dialog.isShowing()) dialog.dismiss();
    }

    private void post(Runnable action) {
        DexEditorActivity activity = activityRef.get();
        if (activity != null) activity.runOnUiThreadIfAlive(action);
    }
}
