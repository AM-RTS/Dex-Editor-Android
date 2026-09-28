package modder.hub.dexeditor.activity;

import modder.hub.dexeditor.model.EditorTab;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;

import modder.hub.dexeditor.fragment.EditorFragment;
import modder.hub.dexeditor.utils.ClassTree;
import modder.hub.dexeditor.utils.Notify_MT;
import modder.hub.dexeditor.utils.SmaliLiteralReplacementTransaction;
import modder.hub.dexeditor.utils.SmaliTextReplacer;
import modder.hub.dexeditor.views.AlertProgress;

/** Applies string replacements against an editor snapshot and reports the result to the host. */
final class StringBatchTask {
    private final WeakReference<DexEditorActivity> activityRef;
    private final Map<String, String> exactReplacements;
    private final SmaliTextReplacer.Rule substringRule;
    private final Runnable onDone;
    private final Map<EditorTab, Long> openTabRevisions = new IdentityHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AlertProgress progressDialog;
    private volatile boolean isStopped;

    StringBatchTask(DexEditorActivity activity, Map<String, String> exactReplacements,
                    String findSubstring, String replaceSubstring, boolean matchCase, Runnable onDone) {
        activityRef = new WeakReference<>(activity);
        this.exactReplacements = exactReplacements;
        this.onDone = onDone;
        substringRule = findSubstring == null || findSubstring.isEmpty() ? null
                : SmaliTextReplacer.compile(findSubstring, replaceSubstring, false, matchCase, false);
    }

    void start() {
        DexEditorActivity activity = activityRef.get();
        if (activity == null) return;
        ClassTree tree = activity.getClassTree();
        if (tree == null) return;

        progressDialog = new AlertProgress(activity);
        progressDialog.setTitle("Applying changes...");
        progressDialog.setCancelable(false);
        progressDialog.setOnCancelListener(() -> isStopped = true);
        progressDialog.show();

        Map<String, String> openTabsContent = new HashMap<>();
        List<EditorTab> openTabs = activity.getOpenTabsSnapshot();
        for (int i = 0; i < openTabs.size(); i++) {
            EditorTab tab = openTabs.get(i);
            if (tab.type != 0) continue;
            openTabRevisions.put(tab, tab.getContentRevision());
            EditorFragment editor = activity.getFragmentAtIndex(i);
            openTabsContent.put(tab.className, editor != null && editor.getEditor() != null
                    ? editor.getEditor().getText().toString() : tab.content);
        }

        activity.executeBackgroundTask("dex-editor-string-batch", () -> {
            SmaliLiteralReplacementTransaction.Result result = SmaliLiteralReplacementTransaction.applyAndCommit(
                    tree, tree.snapshotEditState(), openTabsContent, exactReplacements, substringRule,
                    () -> isStopped || Thread.currentThread().isInterrupted() || hasStaleDraft(),
                    (processed, total) -> mainHandler.post(() -> {
                        DexEditorActivity host = activityRef.get();
                        AlertProgress dialog = progressDialog;
                        if (host != null && !host.isFinishing() && !host.isDestroyed()
                                && dialog != null && dialog.isShowing()) dialog.setProgress(processed, total);
                    }));
            mainHandler.post(() -> showResult(result));
        });
    }

    private boolean hasStaleDraft() {
        for (Map.Entry<EditorTab, Long> entry : openTabRevisions.entrySet()) {
            if (entry.getKey().getContentRevision() != entry.getValue()) return true;
        }
        return false;
    }

    private void showResult(SmaliLiteralReplacementTransaction.Result result) {
        DexEditorActivity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();

        Map<String, String> updatedTabs = result.getUpdatedOpenText();
        if (!result.isCancelled() && result.getErrors().isEmpty() && result.getTransactionError() == null) {
            for (Map.Entry<String, String> entry : updatedTabs.entrySet()) {
                List<EditorTab> currentTabs = activity.getOpenTabsSnapshot();
                for (int i = 0; i < currentTabs.size(); i++) {
                    EditorTab tab = currentTabs.get(i);
                    if (!tab.className.equals(entry.getKey()) || tab.type != 0) continue;
                    tab.markCommitted(entry.getValue());
                    EditorFragment editor = activity.getFragmentAtIndex(i);
                    if (editor != null && editor.getEditor() != null) editor.getEditor().setText(entry.getValue());
                }
            }
        }

        String error = !result.getErrors().isEmpty()
                ? result.getErrors().get(0) : result.getTransactionError();
        if (result.isCancelled()) {
            Notify_MT.Notify(activity, "Info", "Replacement cancelled; no changes were applied.", "Close");
        } else if (error != null) {
            Notify_MT.Notify(activity, "Error", "Replacement not applied: " + error, "Close");
        } else {
            Notify_MT.Notify(activity, "Info", "Replaced " + result.getReplacedCount()
                    + " occurrence(s) in " + result.getAffectedClasses() + " class(es).", "Close");
            activity.needsModifiedTreeRebuild = true;
            activity.refreshExplorerPage(1);
            if (onDone != null) onDone.run();
        }
    }
}
