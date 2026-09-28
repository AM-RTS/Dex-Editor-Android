package modder.hub.dexeditor.activity;

import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import modder.hub.dexeditor.R;
import modder.hub.dexeditor.utils.ClassTree;

/** Displays and updates the options used when compiling the edited DEX files. */
final class CompilationOptionsDialog {
    private CompilationOptionsDialog() {}

    static void show(Context context, ClassTree.CompilationOptions options) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_compilation_options, null);
        Spinner spinnerDexVersion = dialogView.findViewById(R.id.spinner_dex_version);
        SwitchCompat removeAllDebug = dialogView.findViewById(R.id.sw_remove_all_debug);
        SwitchCompat removeDebugSource = dialogView.findViewById(R.id.sw_remove_debug_source);
        SwitchCompat removeDebugLine = dialogView.findViewById(R.id.sw_remove_debug_line);
        SwitchCompat removeDebugParam = dialogView.findViewById(R.id.sw_remove_debug_param);
        SwitchCompat removeDebugPrologue = dialogView.findViewById(R.id.sw_remove_debug_prologue);
        SwitchCompat removeDebugLocal = dialogView.findViewById(R.id.sw_remove_debug_local);

        String[] versions = {"Keep the same", "35", "37", "38", "39"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context, android.R.layout.simple_spinner_item, versions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDexVersion.setAdapter(adapter);
        for (int i = 0; i < versions.length; i++) {
            if (versions[i].equals(options.dexVersion)) {
                spinnerDexVersion.setSelection(i);
                break;
            }
        }

        removeAllDebug.setChecked(options.removeAllDebug);
        removeDebugSource.setChecked(options.removeDebugSource);
        removeDebugLine.setChecked(options.removeDebugLine);
        removeDebugParam.setChecked(options.removeDebugParam);
        removeDebugPrologue.setChecked(options.removeDebugPrologue);
        removeDebugLocal.setChecked(options.removeDebugLocal);

        removeAllDebug.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
                removeDebugSource.setEnabled(!isChecked);
                removeDebugLine.setEnabled(!isChecked);
                removeDebugParam.setEnabled(!isChecked);
                removeDebugPrologue.setEnabled(!isChecked);
                removeDebugLocal.setEnabled(!isChecked);
                if (isChecked) {
                    removeDebugSource.setChecked(true);
                    removeDebugLine.setChecked(true);
                    removeDebugParam.setChecked(true);
                    removeDebugPrologue.setChecked(true);
                    removeDebugLocal.setChecked(true);
                }
            }
        });

        if (options.removeAllDebug) {
            removeDebugSource.setEnabled(false);
            removeDebugLine.setEnabled(false);
            removeDebugParam.setEnabled(false);
            removeDebugPrologue.setEnabled(false);
            removeDebugLocal.setEnabled(false);
        }

        new MaterialAlertDialogBuilder(context)
                .setView(dialogView)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        options.dexVersion = spinnerDexVersion.getSelectedItem().toString();
                        options.removeAllDebug = removeAllDebug.isChecked();
                        options.removeDebugSource = removeDebugSource.isChecked();
                        options.removeDebugLine = removeDebugLine.isChecked();
                        options.removeDebugParam = removeDebugParam.isChecked();
                        options.removeDebugPrologue = removeDebugPrologue.isChecked();
                        options.removeDebugLocal = removeDebugLocal.isChecked();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }
}
