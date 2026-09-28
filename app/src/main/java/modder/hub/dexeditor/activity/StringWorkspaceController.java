package modder.hub.dexeditor.activity;

import android.view.View;
import android.view.LayoutInflater;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import modder.hub.dexeditor.R;
import modder.hub.dexeditor.adapter.StringAdapter;
import modder.hub.dexeditor.fragment.SearchFragment;
import modder.hub.dexeditor.utils.ClassTree;

/** Owns the string-list state and workflows for the DEX strings explorer page. */
final class StringWorkspaceController {
    private final WeakReference<DexEditorActivity> activityRef;
    private final List<String> strings = new ArrayList<>();
    private String pendingSearchQuery;

    StringWorkspaceController(DexEditorActivity activity) {
        activityRef = new WeakReference<>(activity);
    }

    List<String> getStrings() {
        return strings;
    }

    void clear() {
        strings.clear();
        pendingSearchQuery = null;
    }

    void load() {
        DexEditorActivity activity = activityRef.get();
        if (activity == null) return;
        ClassTree tree = activity.getClassTree();
        if (tree == null) return;

        activity.showProcessingProgress(true);
        activity.executeBackgroundTask("dex-editor-load-strings", () -> {
            try {
                List<String> loadedStrings = tree.getAllStrings();
                DexEditorActivity host = activityRef.get();
                if (host == null) return;
                host.runOnUiThreadIfAlive(() -> {
                    strings.clear();
                    strings.addAll(loadedStrings);
                    host.refreshExplorerPage(3);
                    host.showProcessingProgress(false);
                });
            } catch (Exception error) {
                DexEditorActivity host = activityRef.get();
                if (host != null) host.runOnUiThreadIfAlive(() -> {
                    host.showProcessingProgress(false);
                    host.showErrorDialog("Failed to load strings: " + error.getMessage());
                });
            }
        });
    }

    void showEditDialog(StringAdapter adapter, View applyButton, String original) {
        DexEditorActivity activity = activityRef.get();
        if (activity == null) return;
        View container = LayoutInflater.from(activity).inflate(R.layout.string_edit_dialog, null);
        EditText editText = container.findViewById(R.id.string_edit_text);
        editText.setText(adapter.getPendingValue(original));
        editText.setSelection(editText.getText().length());

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Edit string")
                .setView(container)
                .setPositiveButton("OK", null)
                .setNeutralButton("Search", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            adapter.markModified(original, editText.getText().toString());
            applyButton.setVisibility(adapter.hasModifications() ? View.VISIBLE : View.GONE);
            dialog.dismiss();
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            dialog.dismiss();
            searchInClasses(original);
        });
    }

    void showFilterDialog(StringAdapter adapter) {
        DexEditorActivity activity = activityRef.get();
        if (activity == null) return;
        View container = LayoutInflater.from(activity).inflate(R.layout.string_edit_dialog, null);
        TextView editText = container.findViewById(R.id.string_edit_text);
        editText.setHint("Type to filter...");
        if (adapter.getCurrentFilter() != null) editText.setText(adapter.getCurrentFilter());

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Filter strings")
                .setView(container)
                .setPositiveButton("Apply", (dialog, which) -> adapter.setFilter(editText.getText().toString()))
                .setNegativeButton("Clear", (dialog, which) -> adapter.setFilter(null))
                .show();
    }

    void showReplaceAllDialog() {
        DexEditorActivity activity = activityRef.get();
        if (activity == null) return;
        View container = LayoutInflater.from(activity).inflate(R.layout.string_replace_all_dialog, null);
        TextView findText = container.findViewById(R.id.etFind);
        TextView replaceText = container.findViewById(R.id.etReplace);
        MaterialCheckBox matchCase = container.findViewById(R.id.swMatchCase);

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Replace in all strings")
                .setView(container)
                .setPositiveButton("Replace", (dialog, which) -> {
                    String find = findText.getText().toString();
                    if (!find.isEmpty()) {
                        new StringBatchTask(activity, null, find, replaceText.getText().toString(),
                                matchCase.isChecked(), null).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void applyChanges(StringAdapter adapter, View applyButton) {
        Map<String, String> changes = new LinkedHashMap<>(adapter.getModifiedStrings());
        if (changes.isEmpty()) return;
        DexEditorActivity activity = activityRef.get();
        if (activity == null) return;
        new StringBatchTask(activity, changes, null, null, true, () -> {
            adapter.clearModifications();
            applyButton.setVisibility(View.GONE);
            load();
        }).start();
    }

    void searchInClasses(String query) {
        DexEditorActivity activity = activityRef.get();
        if (activity == null || activity.explorerViewPager == null) return;
        activity.explorerViewPager.setCurrentItem(2, true);
        androidx.fragment.app.Fragment fragment = activity.getSupportFragmentManager().findFragmentByTag("f2002");
        if (fragment instanceof SearchFragment) {
            ((SearchFragment) fragment).runStringSearch(query);
        } else {
            pendingSearchQuery = query;
        }
    }

    String consumePendingSearchQuery() {
        String query = pendingSearchQuery;
        pendingSearchQuery = null;
        return query;
    }
}
