package modder.hub.dexeditor.activity;

import androidx.fragment.app.Fragment;

import java.lang.ref.WeakReference;
import java.util.Collections;

import modder.hub.dexeditor.fragment.EditorFragment;
import modder.hub.dexeditor.model.EditorTab;
import modder.hub.dexeditor.utils.ClassTree;
import modder.hub.dexeditor.utils.Notify_MT;
import modder.hub.dexeditor.views.AlertCircularProgress;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.tools.smali.smali2.Smali;

/** Assembles and commits one editor tab without blocking the UI thread. */
final class TabSaveTask {
    interface Callback {
        void onComplete(boolean success);
    }

    private final WeakReference<DexEditorActivity> activityRef;
    private final EditorTab tab;
    private final Callback callback;

    TabSaveTask(DexEditorActivity activity, EditorTab tab, Callback callback) {
        activityRef = new WeakReference<>(activity);
        this.tab = tab;
        this.callback = callback;
    }

    void start() {
        DexEditorActivity activity = activityRef.get();
        if (activity == null) return;
        if (tab == null) {
            fail(activity, "There is no editor tab to save.");
            return;
        }

        Fragment found = activity.getSupportFragmentManager().findFragmentByTag("f" + tab.id);
        EditorFragment fragment = found instanceof EditorFragment ? (EditorFragment) found : null;

        ClassTree tree = activity.getClassTree();
        if (tree == null) {
            fail(activity, "No DEX is open.");
            return;
        }

        String code = fragment != null && fragment.getEditor() != null
                ? fragment.getEditor().getText().toString() : tab.content;
        if (code == null) {
            try {
                String pending = tree.getPendingSmali(tab.className);
                ClassDef current = tree.getClassDef("L" + tab.className + ";");
                if (pending != null) code = pending;
                else if (current != null) code = tree.getSmaliByType(current);
            } catch (Exception error) {
                fail(activity, error.getMessage());
                return;
            }
        }
        if (code == null) {
            fail(activity, "Could not load the current Smali text for " + tab.title + ".");
            return;
        }
        final String codeToSave = code;
        final long expectedTabRevision = tab.getContentRevision();

        final long expectedRevision;
        try {
            expectedRevision = tree.getEditRevision();
        } catch (RuntimeException error) {
            fail(activity, error.getMessage());
            return;
        }

        AlertCircularProgress progress = new AlertCircularProgress(activity);
        progress.setMessage("Saving " + tab.title + "...");
        progress.show();
        Thread worker = activity.executeBackgroundTask("dex-editor-save-tab", () -> {
            try {
                if (Thread.currentThread().isInterrupted()) return;
                ClassDef editedClass = Smali.assemble(codeToSave, new SmaliOptions(), tree.getDexVersionForClass(tab.className));
                if (Thread.currentThread().isInterrupted()) return;
                runOnUiThread(host -> {
                    if (tab.getContentRevision() != expectedTabRevision) {
                        progress.dismiss();
                        Notify_MT.Notify(host, "Save cancelled", "The editor changed while it was being assembled. Save again to include the latest text.", "Close");
                        finishCallback(false);
                        return;
                    }
                    try {
                        tree.commitClassDefs(expectedRevision, Collections.singletonList(editedClass));
                        progress.dismiss();
                        tab.markCommitted(codeToSave);
                        int currentIndex = host.getOpenTabsSnapshot().indexOf(tab);
                        if (currentIndex != -1) host.tabsAdapter.notifyItemChanged(currentIndex + 1);
                        host.needsModifiedTreeRebuild = true;
                        host.refreshExplorerPage(1);
                        host.handleUndoRedo();
                        finishCallback(true);
                    } catch (Exception error) {
                        progress.dismiss();
                        Notify_MT.Notify(host, "Error saving " + tab.title, error.getMessage(), "Close");
                        finishCallback(false);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(host -> {
                    progress.dismiss();
                    Notify_MT.Notify(host, "Error saving " + tab.title, error.getMessage(), "Close");
                    finishCallback(false);
                });
            }
        });
        if (worker == null) {
            progress.dismiss();
            fail(activity, "The save could not be started because the editor is closing.");
        }
    }

    private void fail(DexEditorActivity activity, String message) {
        Notify_MT.Notify(activity, "Error saving " + (tab == null ? "tab" : tab.title),
                message == null || message.isEmpty() ? "Unknown save error." : message, "Close");
        finishCallback(false);
    }

    private void finishCallback(boolean success) {
        if (callback != null) callback.onComplete(success);
    }

    private void runOnUiThread(java.util.function.Consumer<DexEditorActivity> action) {
        DexEditorActivity activity = activityRef.get();
        if (activity != null) activity.runOnUiThreadIfAlive(() -> action.accept(activity));
    }
}
