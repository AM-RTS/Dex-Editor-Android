package modder.hub.dexeditor.fragment;

import modder.hub.dexeditor.model.EditorTab;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;

import com.android.tools.smali.dexlib2.iface.ClassDef;

import modder.hub.dexeditor.activity.DexEditorActivity;
import modder.hub.dexeditor.utils.ClassTree;
import modder.hub.dexeditor.utils.Notify_MT;
import modder.hub.dexeditor.utils.SmaliReplacementTransaction;
import modder.hub.dexeditor.utils.SmaliTextReplacer;
import modder.hub.dexeditor.views.AlertProgress;

/** Runs a validated, cancelable replacement against a captured DEX snapshot. */
final class SearchReplaceTask {
    private final WeakReference<SearchFragment> fragmentRef;
    private final ClassTree targetTree;
    private final long baseRevision;
    private final Map<String, ClassDef> classSnapshot;
    private final List<String> scopeClasses;
    private final SmaliTextReplacer.Rule replacementRule;
    private final Map<String, String> openTabsContent = new HashMap<>();
    private final Map<EditorTab, Long> openTabRevisions = new IdentityHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile WeakReference<DexEditorActivity> activityRef = new WeakReference<>(null);
    private volatile boolean isStopped;
    private volatile Thread worker;
    private AlertProgress progressDialog;
    private SmaliReplacementTransaction.Result result;
    private String taskError;

    SearchReplaceTask(SearchFragment fragment, ClassTree targetTree, long baseRevision,
                      Map<String, ClassDef> classSnapshot, String findQuery, String replaceWith,
                      String type, boolean matchCase, boolean isRegex, boolean exactlyMatch,
                      List<String> scopeClasses) {
        this.fragmentRef = new WeakReference<>(fragment);
        this.targetTree = targetTree;
        this.baseRevision = baseRevision;
        this.classSnapshot = classSnapshot;
        this.scopeClasses = scopeClasses;
        boolean stringsOnly = "String".equals(type);
        this.replacementRule = SmaliTextReplacer.compile(findQuery, replaceWith, isRegex, matchCase,
                stringsOnly, stringsOnly && exactlyMatch);
    }

    void start() {
        SearchFragment fragment = fragmentRef.get();
        if (fragment == null) return;
        DexEditorActivity activity = (DexEditorActivity) fragment.getActivity();
        if (activity == null || targetTree == null) return;
        activityRef = new WeakReference<>(activity);

        progressDialog = new AlertProgress(activity);
        progressDialog.setTitle("Processing...");
        progressDialog.setCancelable(true);
        progressDialog.setOnCancelListener(() -> cancel());
        progressDialog.show();

        List<EditorTab> openTabs = activity.getOpenTabsSnapshot();
        for (int i = 0; i < openTabs.size(); i++) {
            EditorTab tab = openTabs.get(i);
            if (tab.type != 0) continue;
            openTabRevisions.put(tab, tab.getContentRevision());
            EditorFragment editor = activity.getFragmentAtIndex(i);
            openTabsContent.put(tab.className, editor != null && editor.getEditor() != null
                    ? editor.getEditor().getText().toString() : tab.content);
        }

        worker = new Thread(() -> {
            try {
                result = SmaliReplacementTransaction.applyAndCommit(targetTree, baseRevision,
                        classSnapshot, openTabsContent, scopeClasses, replacementRule,
                        () -> isStopped || Thread.currentThread().isInterrupted() || hasStaleDraft(),
                        (processed, total) -> mainHandler.post(() -> {
                            AlertProgress dialog = progressDialog;
                            if (!isStopped && dialog != null && dialog.isShowing()) {
                                dialog.setMax(total);
                                dialog.setProgress(processed);
                            }
                        }));
            } catch (Exception e) {
                taskError = e.getMessage();
                if (e instanceof InterruptedException) isStopped = true;
            }
            mainHandler.post(() -> {
                DexEditorActivity currentActivity = activityRef.get();
                if (currentActivity != null && !currentActivity.isDestroyed()) onPostExecute(currentActivity);
            });
        }, "smali-replace");
        worker.start();
    }

    private boolean hasStaleDraft() {
        for (Map.Entry<EditorTab, Long> entry : openTabRevisions.entrySet()) {
            if (entry.getKey().getContentRevision() != entry.getValue()) return true;
        }
        return false;
    }

    void cancel() {
        isStopped = true;
        Thread currentWorker = worker;
        if (currentWorker != null) currentWorker.interrupt();
        AlertProgress dialog = progressDialog;
        progressDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void onPostExecute(DexEditorActivity activity) {
        AlertProgress dialog = progressDialog;
        progressDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        SearchFragment fragment = fragmentRef.get();
        if (fragment != null && fragment.activeReplaceTask == this) fragment.activeReplaceTask = null;
        boolean wasCancelled = result == null ? isStopped : result.isCancelled();
        if (wasCancelled) {
            if (fragment != null && fragment.getView() != null) {
                Notify_MT.Notify(activity, "Info", "Replacement cancelled; no changes were applied.", "Close");
            }
            return;
        }
        if (fragment == null || fragment.getView() == null) return;
        List<String> errors = result == null ? Collections.<String>emptyList() : result.getErrors();
        String transactionError = result == null ? taskError : result.getTransactionError();
        if (transactionError != null || !errors.isEmpty() || result == null) {
            String reason = transactionError != null ? transactionError
                    : errors.size() + " class(es) could not be assembled. No changes were applied.";
            Notify_MT.Notify(activity, "Replacement failed", reason, "Close");
            if (!errors.isEmpty()) showErrorDialog(activity, result);
            return;
        }
        Notify_MT.Notify(activity, "Info", "Total replaced " + result.getReplacedCount()
                + " times in " + result.getAffectedClasses() + " classes.", "Close");

        if (!result.getReplacedOpenText().isEmpty()) {
            List<EditorTab> currentTabs = activity.getOpenTabsSnapshot();
            for (Map.Entry<String, String> entry : result.getReplacedOpenText().entrySet()) {
                String className = entry.getKey();
                String newText = entry.getValue();
                for (int i = 0; i < currentTabs.size(); i++) {
                    EditorTab tab = currentTabs.get(i);
                    if (!tab.className.equals(className) || tab.type != 0) continue;
                    tab.markCommitted(newText);
                    EditorFragment editor = activity.getFragmentAtIndex(i);
                    if (editor != null && editor.getEditor() != null) editor.getEditor().setText(newText);
                }
            }
            if (activity.tabsAdapter != null) activity.tabsAdapter.notifyDataSetChanged();
        }
        activity.refreshExplorerPage(1);
    }

    private void showErrorDialog(DexEditorActivity activity, SmaliReplacementTransaction.Result result) {
        List<String> errorClasses = result.getErrors();
        StringBuilder message = new StringBuilder(
                "The following classes could not be assembled after replacement:\n\n");
        for (int i = 0; i < Math.min(errorClasses.size(), 20); i++) {
            message.append(errorClasses.get(i)).append('\n');
        }
        if (errorClasses.size() > 20) message.append("\n... and ").append(errorClasses.size() - 20).append(" more.");

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle("Replacement Errors")
                .setMessage(message.toString())
                .setNegativeButton("Close", null);
        if (result.getFirstErrorClass() != null) {
            builder.setPositiveButton("Fix First", (dialog, which) -> {
                if (result.getFirstErrorLine() != -1) {
                    activity.openClassAtLine(result.getFirstErrorClass(), result.getFirstErrorLine(),
                            result.getFirstErrorColumn(), null);
                } else {
                    activity.openClass(result.getFirstErrorClass());
                }
            });
        }
        builder.show();
    }
}
