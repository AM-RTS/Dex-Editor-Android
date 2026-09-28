package modder.hub.dexeditor.activity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import modder.hub.dexeditor.model.TreeNode;
import modder.hub.dexeditor.utils.ClassTree;

/** Loads a DEX set and its explorer trees without retaining the editor Activity. */
final class DexLoadTask {
    private final WeakReference<DexEditorActivity> activityRef;
    private final List<String> paths;
    private final String cachePath;
    private volatile Thread worker;

    DexLoadTask(DexEditorActivity activity, List<String> paths, String cachePath) {
        activityRef = new WeakReference<>(activity);
        this.paths = new ArrayList<>(paths);
        this.cachePath = cachePath;
    }

    void start() {
        Thread thread = new Thread(this::load, "dex-editor-dex-load");
        worker = thread;
        thread.start();
    }

    void cancel() {
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }

    private void load() {
        ClassTree loadedTree = null;
        try {
            loadedTree = new ClassTree(paths, cachePath);
            List<TreeNode> roots = loadedTree.buildFullTree();
            List<TreeNode> modified = loadedTree.buildEditedFullTree();
            if (Thread.currentThread().isInterrupted()) return;

            DexEditorActivity activity = activityRef.get();
            if (activity == null) return;
            ClassTree result = loadedTree;
            activity.runOnUiThread(() -> activity.onDexLoadComplete(result, roots, modified));
            loadedTree = null;
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) return;
            DexEditorActivity activity = activityRef.get();
            if (activity != null) activity.runOnUiThreadIfAlive(() -> activity.onDexLoadError(e));
        } finally {
            if (loadedTree != null) loadedTree.clearAll();
            DexEditorActivity activity = activityRef.get();
            if (activity != null) activity.runOnUiThreadIfAlive(() -> activity.onDexLoadFinished(this));
        }
    }
}
