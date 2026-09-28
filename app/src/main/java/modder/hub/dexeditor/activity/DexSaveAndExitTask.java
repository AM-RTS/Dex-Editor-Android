package modder.hub.dexeditor.activity;

import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import modder.hub.dexeditor.utils.ClassTree;
import modder.hub.dexeditor.utils.EditorPositionManager;
import modder.hub.dexeditor.utils.SketchwareUtil;
import modder.hub.dexeditor.views.AlertProgress;

/** Compiles and publishes the current DEX set before exiting the editor. */
final class DexSaveAndExitTask implements DialogInterface.OnClickListener {
    private static final Pattern ERROR_POSITION = Pattern.compile("\\[(\\d+),(\\d+)]");

    private final WeakReference<DexEditorActivity> activityRef;
    private final ClassTree.CompilationOptions options;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private volatile Thread worker;
    private AlertProgress progress;

    DexSaveAndExitTask(DexEditorActivity activity, ClassTree.CompilationOptions options) {
        activityRef = new WeakReference<>(activity);
        this.options = options;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        DexEditorActivity activity = activityRef.get();
        if (activity == null) return;
        ClassTree tree = activity.getClassTree();
        if (tree == null) return;

        progress = new AlertProgress(activity);
        progress.setTitle("Processing...");
        progress.setMessage("...");
        progress.setOnCancelListener(this::cancel);
        progress.show();
        tree.setCompilationOptions(options);

        worker = activity.executeBackgroundTask("dex-editor-save-dex", () -> save(tree));
    }

    void cancel() {
        stopped = true;
        Thread current = worker;
        if (current != null) current.interrupt();
        AlertProgress currentProgress = progress;
        progress = null;
        if (currentProgress != null && currentProgress.isShowing()) currentProgress.dismiss();
    }

    private void save(ClassTree tree) {
        DexEditorActivity activity = activityRef.get();
        if (activity == null) return;
        try {
            tree.saveAllDexFiles(new ClassTree.DexSaveProgress() {
                @Override
                public void onProgress(int current, int total) {
                    postToProgress(dialog -> dialog.setProgress(current, total));
                }

                @Override
                public void onTitle(String title) {
                    postToProgress(dialog -> dialog.setTitle(title));
                }

                @Override
                public void onMessage(String message) {
                    if (stopped || Thread.currentThread().isInterrupted()) {
                        throw new RuntimeException("CANCELLED");
                    }
                    postToProgress(dialog -> dialog.setMessage(message));
                }
            });
            activity.runOnUiThreadIfAlive(() -> {
                EditorPositionManager.getInstance(activity).clear();
                SketchwareUtil.showMessage(activity.getApplicationContext(), "Success");
                activity.finish();
            });
        } catch (Exception e) {
            if ("CANCELLED".equals(e.getMessage()) || e instanceof InterruptedException
                    || stopped || Thread.currentThread().isInterrupted()) {
                postMessage("Operation Cancelled");
                return;
            }
            showSaveError(activity, e);
        } finally {
            activity.runOnUiThreadIfAlive(() -> {
                AlertProgress dialog = progress;
                progress = null;
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
                activity.onSaveTaskFinished(this);
            });
        }
    }

    private void postToProgress(java.util.function.Consumer<AlertProgress> update) {
        DexEditorActivity activity = activityRef.get();
        if (activity == null) return;
        activity.runOnUiThreadIfAlive(() -> {
            AlertProgress dialog = progress;
            if (!stopped && dialog != null && dialog.isShowing()) update.accept(dialog);
        });
    }

    private void postMessage(String message) {
        DexEditorActivity activity = activityRef.get();
        if (activity != null) activity.runOnUiThreadIfAlive(
                () -> SketchwareUtil.showMessage(activity.getApplicationContext(), message));
    }

    private void showSaveError(DexEditorActivity activity, Exception error) {
        String message = error.getMessage();
        if (message == null || !message.startsWith("COMPILE_ERROR:")) {
            activity.runOnUiThreadIfAlive(() -> activity.showErrorDialog(
                    "An error occurred while processing dex\n\n---StackTrace---\n\n" + error));
            return;
        }

        String[] parts = message.split(":", 3);
        String faultyClass = parts[1];
        String detail = parts[2];
        int line = -1;
        int column = -1;
        Matcher matcher = ERROR_POSITION.matcher(detail);
        if (matcher.find()) {
            try {
                line = Integer.parseInt(Objects.requireNonNull(matcher.group(1))) - 1;
                column = Integer.parseInt(Objects.requireNonNull(matcher.group(2))) - 1;
            } catch (NumberFormatException ignored) {
            }
        }
        final int errorLine = line;
        final int errorColumn = column;
        activity.runOnUiThreadIfAlive(() -> new AlertDialog.Builder(activity)
                .setTitle("Compile Error")
                .setMessage("Class: " + faultyClass + "\n\n" + detail)
                .setPositiveButton("Fix", (dialog, which) -> {
                    if (errorLine != -1) activity.openClassAtLine(faultyClass, errorLine, errorColumn, null);
                    else activity.openClass(faultyClass);
                })
                .setNegativeButton("Close", null)
                .show());
    }
}
