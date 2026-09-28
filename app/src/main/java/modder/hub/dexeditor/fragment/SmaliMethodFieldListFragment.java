
/*
 * Dex-Editor-Android an Advanced Dex Editor for Android
 * Copyright 2024-26, developer-krushna
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of developer-krushna nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


 *     Please contact Krushna by email mt.modder.hub@gmail.com if you need
 *     additional information or have any questions
 */

package modder.hub.dexeditor.fragment;

import modder.hub.dexeditor.model.EditorTab;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;

import modder.hub.dexeditor.views.AlertCircularProgress;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;

import modder.hub.dexeditor.GraphDot.DrawFlowDiagram;
import modder.hub.dexeditor.GraphDot.Method;
import modder.hub.dexeditor.R;
import modder.hub.dexeditor.activity.AIOverViewActivity;
import modder.hub.dexeditor.activity.DexEditorActivity;
import modder.hub.dexeditor.smali.Smali2Java;
import modder.hub.dexeditor.smali.SmaliFieldAccessParser;
import modder.hub.dexeditor.smali.SmaliMethodBody;
import modder.hub.dexeditor.smali.SmaliMethodInvokeParser;
import modder.hub.dexeditor.utils.Notify_MT;
import modder.hub.dexeditor.utils.SketchwareUtil;
import modder.hub.dexeditor.utils.SmaliNavigationParser;
import modder.hub.dexeditor.smali.SmaliHelper;
import modder.hub.dexeditor.utils.UIHelper;
import modder.hub.dexeditor.utils.ViewAnimationHelper;
import modder.hub.dexeditor.views.FastScrollerRecyclerView;

/*
Author @developer-krushna
Code fixed comments by ChatGPT
*/


public class SmaliMethodFieldListFragment extends DialogFragment {
    private AlertCircularProgress progressDialog;
    private AlertCircularProgress secondaryProgressDialog;
    private int dexVersion;
    private int editorLineNumber;
    private String lineNumber;
    private FastScrollerRecyclerView methodRecyclerView;
    private FastScrollerRecyclerView stringsRecyclerView;
    private Toolbar toolbar;
    private String savedMethodData = "";
    private String savedStringsData = "";
    private String searchQuery = "";
    private String smaliFilePath = "";
    private String className = "";
    private LoadDataTask loadDataTask;
    private List<HashMap<String, Object>> methodOrFieldInfo = new ArrayList<>();
    private List<HashMap<String, Object>> stringListInfo = new ArrayList<>();
    private String fullClassName = "???";
    private Parcelable methodRecyclerViewState;
    private Parcelable stringsRecyclerViewState;
    private boolean wasStringsVisible;

    private final String smaliCallSyntax = "->";
    private static Typeface monoTypeface;

    private Typeface getMonoTypeface() {
        if (monoTypeface == null && getActivity() != null) {
            monoTypeface = Typeface.createFromAsset(getActivity().getAssets(), "fonts/mono.ttf");
        }
        return monoTypeface;
    }

    private boolean isFirstLoad = true;

    // Update the UI with the smali file path, class name, editor line number, and dex version
    public void updateUi(String smaliFilePath, String className, int editorLineNumber, int dexVersion) {
        this.smaliFilePath = smaliFilePath;
        this.className = className;
        this.editorLineNumber = editorLineNumber;
        this.dexVersion = dexVersion;
        this.isFirstLoad = true; // Always mark as first load to trigger data refresh from file

        if (isAdded()) {
            // Refresh data if already showing
            new LoadDataRunnable().run();
        }
    }

    @NonNull
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.list_method_layout, container, false);
        initialize(savedInstanceState, view);
        initializeLogic();
        return view;
    }

    private void initialize(Bundle savedInstanceState, View view) {
        toolbar = view.findViewById(R.id.toolbar);
        methodRecyclerView = view.findViewById(R.id.recyclerview_method_list);
        methodRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        stringsRecyclerView = view.findViewById(R.id.recyclerview_strings_list);
        stringsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

    }

    private void initializeLogic() {
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getDialog().getWindow().requestFeature(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        ViewAnimationHelper.enableSwipeViewToggle(methodRecyclerView, stringsRecyclerView);

        toolbar.setTitle("Navigation");
        toolbar.inflateMenu(R.menu.smali_navigation_menu);

        Menu menu = toolbar.getMenu();
        MenuItem searchItem = menu.findItem(R.id.search);
        menu.findItem(R.id.close);
        menu.findItem(R.id.strings_list);
        searchItem.setVisible(true);

        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView != null) {
			searchView.setQueryHint("Search");
			searchView.setMaxWidth(Integer.MAX_VALUE);
			searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
				@Override
				public boolean onQueryTextSubmit(String query) {
					searchQuery = query;
					if (stringsRecyclerView.getVisibility() == View.VISIBLE) {
						performStringsSearch(searchQuery);
					} else {
						performSearch(searchQuery);
					}
					return false;
				}

				@Override
				public boolean onQueryTextChange(String newText) {
					searchQuery = newText;
					if (stringsRecyclerView.getVisibility() == View.VISIBLE) {
						performStringsSearch(searchQuery);
					} else {
						performSearch(searchQuery);
					}
					return true;
				}
			});
		}
        if (isFirstLoad) {
            final Handler handler = new Handler(Looper.getMainLooper());
            final Runnable loadDataRunnable = new LoadDataRunnable();
            handler.postDelayed(loadDataRunnable, 200L);
            isFirstLoad = false;
        } else {
            // Restore adapters without reloading
            methodRecyclerView.setAdapter(new MethodListAdapter(methodOrFieldInfo));
            stringsRecyclerView.setAdapter(new StringListAdapter(stringListInfo));
            restoreRecyclerViewState();
        }

        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.close) {
                    saveCurrentState(); // Save state before dismissing
                    dismiss();
                    return true;
                }
                if (item.getItemId() == R.id.strings_list) {
                    if (!stringListInfo.isEmpty()) {
                        if (stringsRecyclerView.getVisibility() == View.VISIBLE) {
                            ViewAnimationHelper.hideViewAndShowViewWithAnimation(stringsRecyclerView, methodRecyclerView);
                            item.setTitle("Show Strings");
                        } else {
                            ViewAnimationHelper.hideViewAndShowViewWithAnimation(methodRecyclerView, stringsRecyclerView);
                            item.setTitle("Show Methods");
                        }
                    } else {
                        SketchwareUtil.showMessage(getActivity(), "No strings found");
                    }
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            // Get screen width
            int screenWidth = requireActivity().getResources().getDisplayMetrics().widthPixels;

            // Set fixed width (80% of screen width)
            int dialogWidth = (int) (screenWidth * 0.8);

            // Height will WRAP_CONTENT automatically
            Objects.requireNonNull(dialog.getWindow()).setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private void saveCurrentState() {
        if (methodRecyclerView == null || stringsRecyclerView == null) return;
        // Save RecyclerView scroll states
        if (methodRecyclerView.getLayoutManager() != null) {
            methodRecyclerViewState = methodRecyclerView.getLayoutManager().onSaveInstanceState();
        }
        if (stringsRecyclerView.getLayoutManager() != null) {
            stringsRecyclerViewState = stringsRecyclerView.getLayoutManager().onSaveInstanceState();
        }
        wasStringsVisible = stringsRecyclerView.getVisibility() == View.VISIBLE;
        Activity activity = getActivity();
        if (activity instanceof DexEditorActivity) {
            ((DexEditorActivity) activity).saveNavigationState(
                    methodRecyclerViewState, stringsRecyclerViewState, wasStringsVisible);
        }
    }

    public void restorePreviousState(Parcelable methodState, Parcelable stringsState, boolean wasStringsVisible) {
        // Restore visibility
        if (wasStringsVisible) {
            methodRecyclerView.setVisibility(View.GONE);
            stringsRecyclerView.setVisibility(View.VISIBLE);
            toolbar.getMenu().findItem(R.id.strings_list).setTitle("Show Methods");
        } else {
            methodRecyclerView.setVisibility(View.VISIBLE);
            stringsRecyclerView.setVisibility(View.GONE);
            toolbar.getMenu().findItem(R.id.strings_list).setTitle("Show Strings");
        }

        // Restore scroll positions
        if (methodState != null) {
            if (methodRecyclerView.getLayoutManager() != null) {
                methodRecyclerView.getLayoutManager().onRestoreInstanceState(methodState);
            }
        }
        if (stringsState != null) {
            if (stringsRecyclerView.getLayoutManager() != null) {
                stringsRecyclerView.getLayoutManager().onRestoreInstanceState(stringsState);
            }
        }
    }

    private void restoreRecyclerViewState() {
        Activity activity = getActivity();
        if (activity instanceof DexEditorActivity) {
            DexEditorActivity editorActivity = (DexEditorActivity) activity;
            methodRecyclerViewState = editorActivity.getNavigationMethodsState();
            stringsRecyclerViewState = editorActivity.getNavigationStringsState();
            wasStringsVisible = editorActivity.isNavigationShowingStrings();
        }
        restorePreviousState(methodRecyclerViewState, stringsRecyclerViewState, wasStringsVisible);
    }

    @Override
    public void onDestroyView() {
        saveCurrentState(); // Save state when dialog is dismissed
        if (loadDataTask != null) {
            loadDataTask.cancel();
            loadDataTask = null;
        }
        super.onDestroyView();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void performSearch(final String _charSeq) {
        try {
            methodOrFieldInfo.clear();
            methodOrFieldInfo = new Gson().fromJson(savedMethodData, new TypeToken<ArrayList<HashMap<String, Object>>>() {
            }.getType());
            int mapNumber = methodOrFieldInfo.size();
            int currentIndex = mapNumber - 1;
            for (int i = 0; i < mapNumber; i++) {
                String methodName = Objects.requireNonNull(methodOrFieldInfo.get(currentIndex).get("MethodOrFieldName")).toString();
                if (!(_charSeq.length() > methodName.length()) && methodName.toLowerCase(Locale.ROOT).contains(_charSeq.toLowerCase(Locale.ROOT))) {

                } else {
                    methodOrFieldInfo.remove(currentIndex);
                }
                currentIndex--;
            }
            methodRecyclerView.setAdapter(new MethodListAdapter(methodOrFieldInfo));
            if (methodRecyclerView.getAdapter() != null) {
                methodRecyclerView.getAdapter().notifyDataSetChanged();
            }

        } catch (java.lang.NullPointerException ignored) {
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void performStringsSearch(final String _charSeq) {
        try {
            stringListInfo.clear();
            stringListInfo = new Gson().fromJson(savedStringsData, new TypeToken<ArrayList<HashMap<String, Object>>>() {
            }.getType());
            int mapNumber = stringListInfo.size();
            int currentIndex = mapNumber - 1;
            for (int i = 0; i < mapNumber; i++) {
                String stringName = Objects.requireNonNull(stringListInfo.get(currentIndex).get("StringName")).toString();
                if (!(_charSeq.length() > stringName.length()) && stringName.toLowerCase(Locale.ROOT).contains(_charSeq.toLowerCase(Locale.ROOT))) {

                } else {
                    stringListInfo.remove(currentIndex);
                }
                currentIndex--;
            }
            stringsRecyclerView.setAdapter(new StringListAdapter(stringListInfo));
            if (stringsRecyclerView.getAdapter() != null) {
                stringsRecyclerView.getAdapter().notifyDataSetChanged();
            }

        } catch (java.lang.NullPointerException ignored) {
        }
    }

    @NonNull
	private GradientDrawable createHolderBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(50);
        drawable.setColor(color);
        return drawable;
    }

    // Update the editor line number in the parent activity or current fragment
    public void updateEditorLineNumber(String lineNumber) {
        if (getActivity() instanceof DexEditorActivity) {
            DexEditorActivity activity = (DexEditorActivity) getActivity();
            EditorFragment editorFragment = activity.getCurrentFragment();
            if (editorFragment != null) {
                editorFragment._updateEditorLineNumber(lineNumber);
            }
        } else if (getActivity() instanceof DialogLineNumberListener) {
            ((DialogLineNumberListener) getActivity())._updateEditorLineNumber(lineNumber);
        }
    }

    // Generate and display a flowchart for the given method
    public void methodFlowChart(final String methodName) {
        final Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        final String sourcePath = smaliFilePath;
        final String sourceClass = fullClassName;

        if (activity instanceof DexEditorActivity) {
            DexEditorActivity dexActivity = (DexEditorActivity) activity;
            String cleanedClassName = SmaliHelper.smali2OnlySlash(fullClassName);
            String title = SmaliHelper.extractSimpleName(fullClassName) + "." + _getTextBefore(methodName, "(");
            String subtitle = "(" + _getTextAfter(methodName, "(");

            // Check if tab already exists to avoid redundant generation
            List<EditorTab> openTabs = dexActivity.getOpenTabsSnapshot();
            for (int i = 0; i < openTabs.size(); i++) {
                EditorTab tab = openTabs.get(i);
                if (tab.className.equals(cleanedClassName) && tab.title.equals(title) &&
                    tab.subtitle != null && tab.subtitle.equals(subtitle) && tab.type == 2) {
                    dexActivity.showEditor(i);
                    dismiss();
                    return;
                }
            }
        }
        
        dismiss(); // Close dialog fragment
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (activity instanceof DexEditorActivity) {
                    new MethodFlowChartTask((DexEditorActivity) activity, sourcePath, sourceClass,
                            methodName).start();
                }
            }
        }, 200L);
    }

    // Helper method to get the text before a specific delimiter
    public String _getTextBefore(String text, String delimiter) {
        int index = text.indexOf(delimiter);
        return index != -1 ? text.substring(0, index) : "";
    }

    // Helper method to get the text after a specific delimiter
    public String _getTextAfter(String text, String delimiter) {
        int index = text.indexOf(delimiter);
        return index != -1 ? text.substring(index + delimiter.length()) : "";
    }

    // Convert Smali code to Java code for the given method
    public void smali2Java(final String methodName) {
        final Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        final String sourcePath = smaliFilePath;
        final String sourceClass = fullClassName;
        final int sourceDexVersion = dexVersion;

        if (activity instanceof DexEditorActivity) {
            DexEditorActivity dexActivity = (DexEditorActivity) activity;
            String cleanedClassName = SmaliHelper.smali2OnlySlash(fullClassName);
            String title = SmaliHelper.extractSimpleName(fullClassName) + "." + _getTextBefore(methodName, "(");

            // Check if tab already exists to avoid redundant decompilation
            List<EditorTab> openTabs = dexActivity.getOpenTabsSnapshot();
            for (int i = 0; i < openTabs.size(); i++) {
                EditorTab tab = openTabs.get(i);
                if (tab.className.equals(cleanedClassName) && tab.title.equals(title) && tab.type == 1) {
                    dexActivity.showEditor(i);
                    dismiss();
                    return;
                }
            }
        }
        
        dismiss();
        if (activity instanceof DexEditorActivity) {
            new Handler(Looper.getMainLooper()).postDelayed(
                    new SmaliToJavaTask((DexEditorActivity) activity, sourcePath, sourceClass,
                            sourceDexVersion, methodName), 200L);
        }
    }

    public void showExceptionDlg(final Activity activity, final Exception e) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Notify_MT.Notify(activity, activity.getString(R.string.error), e.getMessage(), activity.getString(R.string.close));
            }
        });
    }

    public interface DialogLineNumberListener {
        void _updateEditorLineNumber(String lineNumber);
    }

    private class LoadDataRunnable implements Runnable {
        @Override
        public void run() {
            saveCurrentState(); // Save current scroll position before reloading data
            if (loadDataTask != null) loadDataTask.cancel();
            loadDataTask = new LoadDataTask(SmaliMethodFieldListFragment.this, smaliFilePath);
            loadDataTask.execute();
        }
    }

    private static final class LoadDataTask {
        private final WeakReference<SmaliMethodFieldListFragment> fragmentRef;
        private final File sourceFile;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private volatile boolean cancelled;
        private Thread worker;

        LoadDataTask(SmaliMethodFieldListFragment fragment, String filePath) {
            fragmentRef = new WeakReference<>(fragment);
            sourceFile = new File(filePath);
        }

        void execute() {
            worker = new Thread(() -> {
                final SmaliNavigationParser.Result result;
                try {
                    result = SmaliNavigationParser.parse(sourceFile, () -> cancelled);
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
                if (result == null) return;
                mainHandler.post(() -> {
                    SmaliMethodFieldListFragment fragment = fragmentRef.get();
                    if (cancelled || fragment == null || fragment.loadDataTask != this
                            || !fragment.isAdded() || fragment.getView() == null) return;
                    fragment.loadDataTask = null;
                    fragment.onLoadDataParsed(result);
                });
            }, "dex-editor-method-field-load");
            worker.start();
        }

        void cancel() {
            cancelled = true;
            Thread current = worker;
            if (current != null) current.interrupt();
        }

    }

    @SuppressLint("NotifyDataSetChanged")
    private void onLoadDataParsed(SmaliNavigationParser.Result result) {
        fullClassName = result.getClassName();
        Map<String, List<HashMap<String, Object>>> parsedDataMap = result.getData();
        if (parsedDataMap != null && !parsedDataMap.isEmpty()) {
            methodOrFieldInfo.clear();
            stringListInfo.clear();

            methodOrFieldInfo.addAll(Objects.requireNonNull(parsedDataMap.get("ClassInfo")));
            methodOrFieldInfo.addAll(Objects.requireNonNull(parsedDataMap.get("FieldInfo")));
            methodOrFieldInfo.addAll(Objects.requireNonNull(parsedDataMap.get("MethodInfo")));
            stringListInfo.addAll(Objects.requireNonNull(parsedDataMap.get("StringInfo")));

            savedMethodData = new Gson().toJson(methodOrFieldInfo);

            savedStringsData = new Gson().toJson(stringListInfo);

            methodRecyclerView.setAdapter(new MethodListAdapter(methodOrFieldInfo));
            stringsRecyclerView.setAdapter(new StringListAdapter(stringListInfo));


            int methodPositionToScroll = -1;
            int stringPositionToScroll = -1;

            // Step 1: Find position in methodOrFieldInfo
            for (int i = 0; i < methodOrFieldInfo.size(); i++) {
                Map<String, Object> item = methodOrFieldInfo.get(i);
                String startLineNumber = Objects.requireNonNull(item.get("StartLineNumber")).toString();
                int startLine = (int) Math.floor(Double.parseDouble(startLineNumber));

                if (item.containsKey("EndLineNumber")) {
                    // This is a method - check line range
                    String endLineNumber = Objects.requireNonNull(item.get("EndLineNumber")).toString();
                    int endLine = (int) Math.floor(Double.parseDouble(endLineNumber));
                    if (editorLineNumber >= startLine && editorLineNumber <= endLine) {
                        methodPositionToScroll = i;
                        break;
                    }
                } else {
                    if (editorLineNumber == startLine) {
                        methodPositionToScroll = i;
                        break;
                    }
                }

            }

            // Step 2: Find position in stringListInfo
            for (int i = 0; i < stringListInfo.size(); i++) {
                String startLineNumber = stringListInfo.get(i).get("StartLineNumber").toString();
                int startLine = (int) Math.floor(Double.parseDouble(startLineNumber));
                if (editorLineNumber == startLine) {
                    stringPositionToScroll = i;
                    break;
                }
            }

            // Step 3: Update adapters for both RecyclerViews
            if (methodRecyclerView.getAdapter() != null) {
                methodRecyclerView.getAdapter().notifyDataSetChanged();
            }
            if (stringsRecyclerView.getAdapter() != null) {
                stringsRecyclerView.getAdapter().notifyDataSetChanged();
            }

            // Step 4: Scroll the appropriate RecyclerView based on the found position
            if (methodPositionToScroll != -1) {
                // Scroll methodRecyclerView and ensure it's visible
                methodRecyclerView.scrollToPosition(methodPositionToScroll); // Immediate scroll
            } else if (stringPositionToScroll != -1) {
                // Scroll stringsRecyclerView and ensure it's visible
                stringsRecyclerView.scrollToPosition(stringPositionToScroll); // Immediate scroll

            }

            // Restore state after silent reload
            restoreRecyclerViewState();
        }
    }

    public class MethodListAdapter extends RecyclerView.Adapter<MethodListAdapter.ViewHolder> {
        List<HashMap<String, Object>> data;

        public MethodListAdapter(List<HashMap<String, Object>> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.method_list, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, @SuppressLint("RecyclerView") int position) {
            HashMap<String, Object> item = data.get(position);
            String methodOrFieldName = Objects.requireNonNull(item.get("MethodOrFieldName")).toString();
            String startLineNumber = Objects.requireNonNull(item.get("StartLineNumber")).toString();

            holder.indexNameTextView.setTypeface(getMonoTypeface(), Typeface.NORMAL);

            LayerDrawable layerDrawable = (LayerDrawable) holder.backgroundLayout.getBackground();
            GradientDrawable dynamicBackground = (GradientDrawable) layerDrawable.findDrawableByLayerId(R.id.dynamic_background);

            if (methodOrFieldName.startsWith("L") && methodOrFieldName.endsWith(";")) {
                holder.indexNameContainer.setBackground(createHolderBackground(Color.parseColor("#3860AF")));
                holder.indexNameTextView.setBackground(createHolderBackground(Color.parseColor("#3860AF")));
                holder.indexNameTextView.setText("C");
                fullClassName = methodOrFieldName;
                holder.methodNameTextView.setText(SmaliHelper.extractSimpleName(methodOrFieldName));
                holder.returnTypeTextView.setText(methodOrFieldName);

                if (editorLineNumber == ((int) Math.floor(Double.parseDouble(startLineNumber)))) {
                    dynamicBackground.setColor(Color.parseColor("#67C1DF"));
                } else {
                    dynamicBackground.setColor(Color.TRANSPARENT);
                }
            } else if (methodOrFieldName.contains(":")) {
                holder.indexNameContainer.setBackground(createHolderBackground(Color.parseColor("#FB8C00")));
                holder.indexNameTextView.setBackground(createHolderBackground(Color.parseColor("#FB8C00")));
                holder.indexNameTextView.setText("F");
                holder.methodNameTextView.setText(_getTextBefore(methodOrFieldName, ":"));
                holder.returnTypeTextView.setText(_getTextAfter(methodOrFieldName, ":"));

                if (editorLineNumber == ((int) Math.floor(Double.parseDouble(startLineNumber)))) {
                    dynamicBackground.setColor(Color.parseColor("#67C1DF"));
                } else {
                    dynamicBackground.setColor(Color.TRANSPARENT);
                }
            } else {
                holder.indexNameContainer.setBackground(createHolderBackground(Color.parseColor("#E53935")));
                holder.indexNameTextView.setBackground(createHolderBackground(Color.parseColor("#E53935")));
                holder.indexNameTextView.setText("M");
                String methodName = _getTextBefore(methodOrFieldName, "(");
                String parameters = "(" + _getTextAfter(methodOrFieldName, "(");
                int startLine = (int) Math.floor(Double.parseDouble(startLineNumber));
                int endLine = (int) Math.floor(Double.parseDouble(Objects.requireNonNull(item.get("EndLineNumber")).toString()));

                holder.methodNameTextView.setText(methodName);
                holder.returnTypeTextView.setText(parameters);

                if (editorLineNumber >= startLine && editorLineNumber <= endLine) {
                    dynamicBackground.setColor(Color.parseColor("#67C1DF"));
                } else {
                    dynamicBackground.setColor(Color.TRANSPARENT);
                }
            }

            holder.backgroundLayout.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View _view) {
                    // Get the method or field name from the clicked position
                    final String methodOrFieldName = Objects.requireNonNull(methodOrFieldInfo.get(position).get("MethodOrFieldName")).toString();

                    // Create a popup menu attached to the clicked view
                    PopupMenu popupMenu = new PopupMenu(getActivity(), _view);
                    Menu menu = popupMenu.getMenu();

                    // Check if this is a class signature (starts with L and ends with ;)
                    if (methodOrFieldName.startsWith("L") && methodOrFieldName.endsWith(";")) {
                        menu.add(1, 1, 1, "Copy class signature");
                        menu.add(2, 2, 2, "Copy subclass signature");
                    }

                    // Check if this is a field (contains :)
                    if (methodOrFieldName.contains(":")) {
                        menu.add(3, 3, 3, "Copy field signature");
                        menu.add(9, 9, 9, "Copy field get code");  // Generate smali get instruction
                        menu.add(10, 10, 10, "Copy field put code"); // Generate smali put instruction
                    }

                    // Check if this is a method (contains ( but not :)
                    if (methodOrFieldName.contains("(") && !methodOrFieldName.contains(":")) {
                        menu.add(4, 4, 4, "Copy method signature");
                        menu.add(5, 5, 5, "Copy method code");      // Get full method body
                        menu.add(6, 6, 6, "Copy method invoke code"); // Generate invoke instruction
                        menu.add(7, 7, 7, "View flowchart");       // Show method flowchart
                        menu.add(8, 8, 8, "Smali to Java");        // Convert smali to Java
                        menu.add(11, 11, 11, "AI Explanation");
                    }

                    // Set click listener for popup menu items
                    popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @SuppressLint("NewApi")
                        @Override
                        public boolean onMenuItemClick(MenuItem menuItem) {
                            switch (menuItem.getItemId()) {
                                case 1:  // Copy class signature
                                    UIHelper.copyToClipboard(requireContext(), methodOrFieldName);
                                    return true;

                                case 2:  // Copy subclass signature
                                    UIHelper.copyToClipboard(requireContext(), Objects.requireNonNull(methodOrFieldInfo.get(position).get("SuperClass")).toString());
                                    return true;

                                case 3:  // Copy field signature
                                    String fieldSignature = fullClassName + smaliCallSyntax + methodOrFieldName;
                                    // Clean up the signature by removing extra parts after space
                                    int spaceIndex = fieldSignature.indexOf(" ");
                                    if (spaceIndex != -1) {
                                        fieldSignature = fieldSignature.substring(0, spaceIndex);
                                    }
                                    UIHelper.copyToClipboard(requireContext(), fieldSignature);
                                    return true;

                                case 4:  // Copy method signature
                                    UIHelper.copyToClipboard(requireContext(), fullClassName + smaliCallSyntax + methodOrFieldName);
                                    return true;

                                case 5:  // Copy method code
                                    // Parse and copy the full method body from smali file
                                    SmaliMethodBody smaliMethodBody = new SmaliMethodBody(
                                            smaliFilePath,
                                            new String[]{Objects.requireNonNull(methodOrFieldInfo.get(position).get("MethodOrFieldName")).toString()},
                                            false
                                    );
                                    UIHelper.copyToClipboard(requireContext(), smaliMethodBody.parseClassInSmali());
                                    return true;

                                case 6:  // Copy method invoke code
                                    // Generate and copy smali invoke instruction
                                    SmaliMethodInvokeParser parser = new SmaliMethodInvokeParser(fullClassName);
                                    UIHelper.copyToClipboard(requireContext(), parser.generateInvokeCode(
                                            Objects.requireNonNull(item.get("FullMethodOrField")).toString(),
                                            "v0"  // Using register v0
                                    ));
                                    return true;

                                case 7:  // View flowchart
                                    methodFlowChart(Objects.requireNonNull(methodOrFieldInfo.get(position).get("MethodOrFieldName")).toString());
                                    return true;

                                case 8:  // Smali to Java
                                    smali2Java(Objects.requireNonNull(methodOrFieldInfo.get(position).get("MethodOrFieldName")).toString());
                                    return true;

                                case 9:  // Copy field get code
                                    // Generate and copy smali get instruction for field
                                    SmaliFieldAccessParser parser2 = new SmaliFieldAccessParser(fullClassName);
                                    UIHelper.copyToClipboard(requireContext(), parser2.generateGetCode(Objects.requireNonNull(item.get("FullMethodOrField")).toString()));
                                    return true;

                                case 10:  // Copy field put code
                                    // Generate and copy smali put instruction for field
                                    SmaliFieldAccessParser parser3 = new SmaliFieldAccessParser(fullClassName);
                                    UIHelper.copyToClipboard(requireContext(), parser3.generatePutCode(Objects.requireNonNull(item.get("FullMethodOrField")).toString()));
                                    return true;

                                case 11:  // AI Explanation
                                    SmaliMethodBody smaliMethodBody2 = new SmaliMethodBody(smaliFilePath, new String[]{methodOrFieldInfo.get(position).get("MethodOrFieldName").toString()}, false);
                                    Intent intent = new Intent(requireContext().getApplicationContext(), AIOverViewActivity.class);
                                    intent.putExtra("smali", smaliMethodBody2.parseClassInSmali());
                                    startActivity(intent);
                                    return true;

                                default:
                                    return false;
                            }
                        }
                    });

                    // Show the popup menu
                    popupMenu.show();
                    return true;  // Consume the long click event
                }
            });

            holder.backgroundLayout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View _view) {
                    lineNumber = Objects.requireNonNull(methodOrFieldInfo.get(position).get("StartLineNumber")).toString();
                    updateEditorLineNumber(lineNumber);
                    dismiss();
                }
            });

        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            LinearLayout backgroundLayout;
            LinearLayout indexNameContainer;
            TextView indexNameTextView;
            TextView methodNameTextView;
            TextView returnTypeTextView;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                backgroundLayout = itemView.findViewById(R.id.linear_bg);
                indexNameContainer = itemView.findViewById(R.id.indexName_container);
                indexNameTextView = itemView.findViewById(R.id.indexName);
                methodNameTextView = itemView.findViewById(R.id.method_name);
                returnTypeTextView = itemView.findViewById(R.id.return_type);
            }
        }
    }

    private static class MethodFlowChartTask implements Runnable {
        private final DexEditorActivity activity;
        private final String sourcePath;
        private final String sourceClass;
        private final String methodName;
        private AlertCircularProgress progress;

        MethodFlowChartTask(DexEditorActivity activity, String sourcePath, String sourceClass,
                            String methodName) {
            this.activity = activity;
            this.sourcePath = sourcePath;
            this.sourceClass = sourceClass;
            this.methodName = methodName;
        }

        void start() {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            progress = new AlertCircularProgress(activity);
            progress.setMessage("Generating flowchart...");
            progress.show();
            activity.executeBackgroundTask("dex-editor-method-flowchart", this);
        }

        @Override
        public void run() {
            try {
                if (Thread.currentThread().isInterrupted()) return;
                DrawFlowDiagram diagram = new DrawFlowDiagram(sourcePath, new String[]{methodName});
                diagram.run();
                List<String> diagrams = new ArrayList<>();
                for (Method method : diagram.getClassInSmali().getMethodDict().values()) {
                    if (Thread.currentThread().isInterrupted()) return;
                    diagrams.add(diagram.drawMethodFlowDiagram(method));
                }
                if (Thread.currentThread().isInterrupted()) return;
                String cleanedClassName = SmaliHelper.smali2OnlySlash(sourceClass);
                String title = SmaliHelper.extractSimpleName(sourceClass) + "." + before(methodName, "(");
                String subtitle = "(" + after(methodName, "(");
                activity.runOnUiThreadIfAlive(() -> {
                    dismissProgress();
                    for (String dot : diagrams) {
                        activity.addTab(cleanedClassName, title, subtitle, dot, 2);
                    }
                });
            } catch (Exception e) {
                final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                activity.runOnUiThreadIfAlive(() -> {
                    dismissProgress();
                    Notify_MT.Notify(activity, activity.getString(R.string.error), message,
                            activity.getString(R.string.close));
                });
            }
        }

        private void dismissProgress() {
            if (progress != null) progress.dismiss();
        }
    }

    public void saveStateForHost() {
        saveCurrentState();
    }

    private static class SmaliToJavaTask implements Runnable {
        private final DexEditorActivity activity;
        private final String sourcePath;
        private final String sourceClass;
        private final int dexVersion;
        private final String methodName;
        private AlertCircularProgress progress;

        SmaliToJavaTask(DexEditorActivity activity, String sourcePath, String sourceClass,
                        int dexVersion, String methodName) {
            this.activity = activity;
            this.sourcePath = sourcePath;
            this.sourceClass = sourceClass;
            this.dexVersion = dexVersion;
            this.methodName = methodName;
        }

        @Override
        public void run() {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            progress = new AlertCircularProgress(activity);
            progress.setMessage("Decompiling...");
            progress.show();
            activity.executeBackgroundTask("dex-editor-method-smali-to-java", () -> {
                try {
                    if (Thread.currentThread().isInterrupted()) return;
                    SmaliMethodBody body = new SmaliMethodBody(sourcePath, new String[]{methodName}, true);
                    String javaCode = Smali2Java.translate(body.parseClassInSmali(), dexVersion);
                    if (Thread.currentThread().isInterrupted()) return;
                    String cleanedClassName = SmaliHelper.smali2OnlySlash(sourceClass);
                    String title = SmaliHelper.extractSimpleName(sourceClass) + "." + before(methodName, "(");
                    activity.runOnUiThreadIfAlive(() -> {
                        dismissProgress();
                        activity.addTab(cleanedClassName, title, javaCode, 1);
                    });
                } catch (Exception e) {
                    final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                    activity.runOnUiThreadIfAlive(() -> {
                        dismissProgress();
                        Notify_MT.Notify(activity, activity.getString(R.string.error), message,
                                activity.getString(R.string.close));
                    });
                }
            });
        }

        private void dismissProgress() {
            if (progress != null) progress.dismiss();
        }
    }

    private static String before(String text, String delimiter) {
        int index = text.indexOf(delimiter);
        return index != -1 ? text.substring(0, index) : "";
    }

    private static String after(String text, String delimiter) {
        int index = text.indexOf(delimiter);
        return index != -1 ? text.substring(index + delimiter.length()) : "";
    }

    private class StringListAdapter extends RecyclerView.Adapter<StringListAdapter.ViewHolder> {
        List<HashMap<String, Object>> data;

        public StringListAdapter(List<HashMap<String, Object>> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.string_list, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            HashMap<String, Object> item = data.get(position);
            String stringName = Objects.requireNonNull(item.get("StringName")).toString();
            String startLineNumber = Objects.requireNonNull(item.get("StartLineNumber")).toString();
            holder.indexNameTextView.setTypeface(getMonoTypeface(), Typeface.NORMAL);
            holder.indexNameContainer.setBackground(createHolderBackground(Color.parseColor("#40AD3E")));
            holder.indexNameTextView.setBackground(createHolderBackground(Color.parseColor("#40AD3E")));
            holder.stringTextView.setText(stringName);

            LayerDrawable layerDrawable = (LayerDrawable) holder.backgroundLayout.getBackground();
            GradientDrawable dynamicBackground = (GradientDrawable) layerDrawable.findDrawableByLayerId(R.id.dynamic_background);

            if (editorLineNumber == ((int) Math.floor(Double.parseDouble(startLineNumber)))) {
                dynamicBackground.setColor(Color.parseColor("#67C1DF"));
            } else {
                dynamicBackground.setColor(Color.TRANSPARENT);
            }

            holder.backgroundLayout.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View _view) {
                    PopupMenu popupMenu = new PopupMenu(getActivity(), _view);
                    Menu menu = popupMenu.getMenu();
                    menu.add(1, 1, 1, "Copy");
                    popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                            if (item.getItemId() == 1) {
                                UIHelper.copyToClipboard(requireContext(), stringName);
                                return true;
                            }
                            return false;
                        }
                    });
                    popupMenu.show();
                    return true;
                }
            });

            holder.backgroundLayout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View _view) {
                    updateEditorLineNumber(startLineNumber);
                    dismiss();
                }
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            TextView stringTextView;
            LinearLayout backgroundLayout;
            LinearLayout indexNameContainer;
            TextView indexNameTextView;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                backgroundLayout = itemView.findViewById(R.id.linear_bg);
                stringTextView = itemView.findViewById(R.id.string_name);
                indexNameContainer = itemView.findViewById(R.id.indexName_container);
                indexNameTextView = itemView.findViewById(R.id.indexName);
            }
        }
    }

}
