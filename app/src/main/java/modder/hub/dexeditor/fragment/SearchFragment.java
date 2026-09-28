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
 */

package modder.hub.dexeditor.fragment;

import modder.hub.dexeditor.model.EditorTab;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import modder.hub.dexeditor.R;
import modder.hub.dexeditor.activity.DexEditorActivity;
import modder.hub.dexeditor.adapter.TreeAdapter;
import modder.hub.dexeditor.model.TreeNode;
import modder.hub.dexeditor.utils.ClassTree;
import modder.hub.dexeditor.utils.SketchwareUtil;
import modder.hub.dexeditor.utils.TreeHelper;
import modder.hub.dexeditor.utils.UIHelper;
import modder.hub.dexeditor.views.FastScrollerRecyclerView;

// Author : @developer-krushna
// Got some LOGICS from AI but thought , idea other resources copied from mt manager interface
// Thanks @Bin

/**
 * SearchFragment: Handles all search and replace operations in the DEX editor.
 * It supports Smali code, class names, methods, fields, and even raw integers/hex.
 */
public class SearchFragment extends Fragment {
    // ponytail: cap class workers to bound temporary Smali text; benchmark before raising the ceiling.
    static final int MAX_CLASS_WORKERS = 4;
    private LinearLayout btnSearchInResults, btnReplaceInResults, btnClearResults, layoutSearchInfo;
    private TextView tvSearchInfo;
    private List<TreeNode> searchResults = new ArrayList<>();
    private TreeAdapter adapter;
    private String currentQuery;

    private String lastSearchQuery = "", lastSearchPath = "/", lastReplaceWith = "", lastSearchType = "Smali";
    private boolean lastSearchSubfolders = true, lastMatchCase = false, lastIsRegex = false, lastExactlyMatch = false, lastIsHex = false, lastUseExcludeList = false;
    SearchReplaceTask activeReplaceTask;
    private SearchTask activeSearchTask;

    @Override
    public void onDestroyView() {
        SearchReplaceTask task = activeReplaceTask;
        activeReplaceTask = null;
        if (task != null) task.cancel();
        SearchTask searchTask = activeSearchTask;
        activeSearchTask = null;
        if (searchTask != null) searchTask.cancelForViewDestroyed();
        super.onDestroyView();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_search, container, false);
        FastScrollerRecyclerView recyclerView = view.findViewById(R.id.search_results_rv);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);

        View headerView = inflater.inflate(R.layout.search_header, recyclerView, false);
        LinearLayout btnNewSearch = headerView.findViewById(R.id.btn_new_search);
        btnSearchInResults = headerView.findViewById(R.id.btn_search_in_results);
        btnReplaceInResults = headerView.findViewById(R.id.btn_replace_in_results);
        btnClearResults = headerView.findViewById(R.id.btn_clear_results);
        layoutSearchInfo = headerView.findViewById(R.id.layout_search_info);
        tvSearchInfo = headerView.findViewById(R.id.tv_search_results_info);
        ImageView treeOptionsButton = headerView.findViewById(R.id.btn_treeview_options);

        DexEditorActivity activity = (DexEditorActivity) getActivity();
        if (activity != null) {
            searchResults = activity.searchNodes;
            adapter = new TreeAdapter(getContext(), searchResults, new TreeAdapter.OnNodeClickListener() {
                @Override
                public void onNodeClick(TreeNode node) {
                    if (node.isSnippet()) {
                        activity.openClassAtLine(node.getFullName(), node.getLineNumber(), currentQuery);
                    } else if (!node.isDirectory()) {
                        activity.openClass(node.getFullName());
                    }
                }

                @Override
                public boolean onNodeDeleted(TreeNode node) {
                    if (node.isSnippet()) {
                        TreeNode parent = node.getParent();
                        if (parent != null && !parent.isDirectory()) {
                            removeNodeFromSearchResults(parent);
                        } else {
                            removeNodeFromSearchResults(node);
                        }
                    } else {
                        removeNodeFromSearchResults(node);
                    }
                    return true;
                }

                @Override public void onSelectionChanged(int count) {}
                @Override public void onLocate(TreeNode node) {
                    activity.locateClass(node.getFullName());
                }
                @Override public void onCopyName(TreeNode node) {
                    UIHelper.copyToClipboard(requireContext(), node.getName());
                }
            }, false);
            adapter.setSearchList(true, currentQuery);
            recyclerView.setAdapter(new ConcatAdapter(new HeaderViewAdapter(headerView), adapter));
        }

        btnNewSearch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                checkUnsavedAndShowWarning(new Runnable() {
                    @Override public void run() {
                        showSearchDialog(false);
                    }
                });
            }
        });
        btnSearchInResults.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSearchDialog(true);
            }
        });
        btnReplaceInResults.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showReplaceDialog();
            }
        });
        btnClearResults.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                clearResults();
            }
        });
        treeOptionsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showTreeOptionsMenu(v);
            }
        });

        updateUIState();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        DexEditorActivity activity = (DexEditorActivity) getActivity();
        if (activity != null && activity.pendingSearchPath != null) {
            String path = activity.pendingSearchPath;
            activity.pendingSearchPath = null;
            showSearchDialogWithPath(path);
        }
        if (activity != null) {
            String query = activity.consumePendingStringSearchQuery();
            if (query != null) runStringSearch(query);
        }
    }

    public void refreshUI() {
        if (adapter != null) {
            adapter.refreshVisibleNodes();
            updateSearchInfoBar();
        }
    }

    SearchTask getActiveSearchTask() {
        return activeSearchTask;
    }

    void setActiveSearchTask(SearchTask task) {
        activeSearchTask = task;
    }

    void onSearchTaskFinished(SearchTask task, String query, String highlightQuery, List<TreeNode> tree) {
        if (activeSearchTask == task) activeSearchTask = null;
        if (getView() == null) return;
        currentQuery = query;
        DexEditorActivity activity = (DexEditorActivity) getActivity();
        if (activity == null) return;
        activity.searchNodes.clear();
        activity.searchNodes.addAll(tree);
        updateSearchInfoBar();
        updateUIState();
        if (adapter != null) {
            adapter.setSearchList(true, highlightQuery);
            adapter.refreshVisibleNodes();
        }
    }

    public void showSearchDialogWithPath(String path) {
        showSearchDialog(false, path);
    }

    public void runStringSearch(String query) {
        lastSearchQuery = query;
        lastSearchType = "String";
        lastMatchCase = true;
        lastIsRegex = false;
        lastExactlyMatch = false;
        searchResults.clear();
        currentQuery = null;
        if (adapter != null) adapter.refreshVisibleNodes();
        updateUIState();
        new SearchTask(this, query, "/", "String", true, true, false, false, false, null, false).start();
    }

    public static class HeaderViewAdapter extends RecyclerView.Adapter<HeaderViewAdapter.ViewHolder> {
        private final View view;
        public HeaderViewAdapter(View view) {
            this.view = view;
        }
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(view);
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {}
        @Override public int getItemCount() {
            return 1;
        }
        static class ViewHolder extends RecyclerView.ViewHolder {
            ViewHolder(View view) {
                super(view);
            }
        }
    }

    /**
     * Checks if there are any unsaved changes in open tabs before performing a search.
     * This prevents potential data loss or search inconsistencies.
     */
    private void checkUnsavedAndShowWarning(Runnable onProceed) {
        List<EditorTab> modifiedTabs = new ArrayList<>();
        DexEditorActivity activity = (DexEditorActivity) getActivity();
        if (activity != null) {
            for (EditorTab tab : activity.getOpenTabsSnapshot()) {
                if (tab.isModified) {
                    modifiedTabs.add(tab);
                }
            }
        }

        if (!modifiedTabs.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Info")
                    .setMessage("You need to save all the codes before proceeding. Do you want to continue ?")
                    .setPositiveButton("Save and Continue", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            boolean[] checked = new boolean[modifiedTabs.size()];
                            Arrays.fill(checked, true);
                            if (activity != null) {
                                activity.saveMultipleTabs(modifiedTabs, checked, onProceed);
                            } else {
                                onProceed.run();
                            }
                        }
                    })
                    .setNeutralButton("Cancel", null)
                    .show();
        } else {
            onProceed.run();
        }
    }

    private void updateUIState() {
        boolean hasResults = !searchResults.isEmpty();
        boolean hasQuery = currentQuery != null;
        int visibility = hasResults ? View.VISIBLE : View.GONE;
        btnSearchInResults.setVisibility(visibility);
        btnReplaceInResults.setVisibility(visibility);
        btnClearResults.setVisibility(visibility);
        layoutSearchInfo.setVisibility(hasQuery || hasResults ? View.VISIBLE : View.GONE);
    }

    private void removeNodeFromSearchResults(TreeNode node) {
        if (node.getParent() != null) {
            node.getParent().getChildren().remove(node);
            if (node.getParent().getChildren().isEmpty()) {
                removeNodeFromSearchResults(node.getParent());
            }
        } else {
            searchResults.remove(node);
        }
        updateSearchInfoBar();
        updateUIState();
        adapter.refreshVisibleNodes();
    }

    private void clearResults() {
        searchResults.clear();
        currentQuery = null;
        updateSearchInfoBar();
        updateUIState();
        adapter.setSearchList(true, null);
        adapter.refreshVisibleNodes();
    }

    private void showTreeOptionsMenu(View v) {
        PopupMenu popup = new PopupMenu(requireContext(), v);
        popup.getMenu().add(0, 1, 0, "Collapse all");
        popup.getMenu().add(0, 2, 0, "Expand all");
        popup.getMenu().add(0, 3, 0, "Only expand packages");
        popup.getMenu().add(0, 4, 0, "Copy class names");

        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        TreeHelper.collapseAll(searchResults);
                        adapter.refreshVisibleNodes();
                        return true;
                    case 2:
                        TreeHelper.expandAll(searchResults);
                        adapter.refreshVisibleNodes();
                        return true;
                    case 3:
                        TreeHelper.onlyExpandPackages(searchResults);
                        adapter.refreshVisibleNodes();
                        return true;
                    case 4:
                        copyClassNames();
                        return true;
                }
                return false;
            }
        });
        popup.show();
    }

    private void copyClassNames() {
        List<String> names = new ArrayList<>();
        collectClassFullNames(searchResults, names);
        StringBuilder sb = new StringBuilder();
        for (String name : names) sb.append(name.replace('/', '.')).append("\n");
        if (sb.length() > 0) UIHelper.copyToClipboard(requireContext(), sb.toString().trim());
    }

    private void collectClassFullNames(List<TreeNode> nodes, List<String> out) {
        for (TreeNode node : nodes) {
            if (!node.isDirectory() && !node.isSnippet()) out.add(node.getFullName());
            collectClassFullNames(node.getChildren(), out);
        }
    }

    @SuppressLint("SetTextI18n")
    private void updateSearchInfoBar() {
        boolean hasResults = !searchResults.isEmpty();
        boolean hasQuery = currentQuery != null;
        if (!hasQuery && !hasResults) {
            layoutSearchInfo.setVisibility(View.GONE);
        } else {
            layoutSearchInfo.setVisibility(View.VISIBLE);
            int count = countTotalSnippets(searchResults);
            tvSearchInfo.setText("search results (" + count + ") - " + (currentQuery != null ? currentQuery : ""));
        }
    }

    // count total serach result
    // snippets are basically the tota;l number of highligted region exluding the folder
    private int countTotalSnippets(List<TreeNode> nodes) {
        int count = 0;
        for (TreeNode node : nodes) {
            if (node.isSnippet() || (!node.isDirectory() && node.getChildren().isEmpty())) count++;
            count += countTotalSnippets(node.getChildren());
        }
        return count;
    }

    private void showExcludeListDialog() {
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("search_prefs", android.content.Context.MODE_PRIVATE);
        String savedExcludes = prefs.getString("exclude_list", "");

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        EditText etExcludes = new EditText(requireContext());
        etExcludes.setText(savedExcludes);
        etExcludes.setHint("com/gms/\nandroidx/");
        etExcludes.setMinLines(3);
        etExcludes.setGravity(android.view.Gravity.TOP);

        TextView tvExplanation = new TextView(requireContext());
        tvExplanation.setText("Set a list of paths that need to be excluded from the search range, one per line, for example:\ncom/gms/\nandroidx/\nAttention! The exclusion list only takes effect when the search path is empty or \"/\"!");
        tvExplanation.setTextSize(14);
        tvExplanation.setPadding(0, 20, 0, 0);

        layout.addView(etExcludes);
        layout.addView(tvExplanation);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle("Exclude list");
        builder.setView(layout);
        builder.setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                prefs.edit().putString("exclude_list", etExcludes.getText().toString()).apply();
            }
        });
        builder.setNegativeButton("CANCEL", null);
        builder.show();
    }

    /**
     * Configures and displays the search configuration dialog.
     * @param searchInResults If true, filters the existing results instead of searching all files.
     */
    private void showSearchDialog(boolean searchInResults) {
        showSearchDialog(searchInResults, null);
    }

    private void showSearchDialog(boolean searchInResults, String initialPath) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_search_dex, null);
        AutoCompleteTextView etFind = dialogView.findViewById(R.id.et_find);
        EditText etPath = dialogView.findViewById(R.id.et_path);
        Spinner spinnerSearchType = dialogView.findViewById(R.id.spinner_search_type);
        CheckBox cbSearchSubfolders = dialogView.findViewById(R.id.cb_search_subfolders);
        CheckBox cbMatchCase = dialogView.findViewById(R.id.cb_match_case);
        CheckBox cbRegex = dialogView.findViewById(R.id.cb_regex);
        CheckBox cbExactlyMatch = dialogView.findViewById(R.id.cb_exactly_match);
        CheckBox cbHex = dialogView.findViewById(R.id.cb_hex);
        CheckBox cbUseExcludeList = dialogView.findViewById(R.id.cb_use_exclude_list);
        TextView tvExcludeList = dialogView.findViewById(R.id.tv_exclude_list);

        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("search_prefs", android.content.Context.MODE_PRIVATE);
        lastUseExcludeList = prefs.getBoolean("use_exclude_list", false);

        etFind.setText(lastSearchQuery);
        if (searchInResults) {
            etPath.setText("");
            etPath.setHint("<Current search results>");
            etPath.setEnabled(false);
            tvExcludeList.setEnabled(false);
            cbUseExcludeList.setEnabled(false);
            cbSearchSubfolders.setVisibility(View.GONE);
        } else {
            etPath.setText(initialPath != null ? initialPath : lastSearchPath);
            etPath.setEnabled(true);
            tvExcludeList.setEnabled(true);
            cbUseExcludeList.setEnabled(true);
            cbSearchSubfolders.setVisibility(View.VISIBLE);
            cbSearchSubfolders.setChecked(lastSearchSubfolders);
        }

        cbMatchCase.setChecked(lastMatchCase);
        cbRegex.setChecked(lastIsRegex);
        cbExactlyMatch.setChecked(lastExactlyMatch);
        cbHex.setChecked(lastIsHex);
        cbUseExcludeList.setChecked(lastUseExcludeList);

        tvExcludeList.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showExcludeListDialog();
            }
        });

        String[] searchTypes = {"Smali", "Class name", "Field name", "Method name", "String", "Integer"};
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, searchTypes);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSearchType.setAdapter(typeAdapter);

        int typePos = 0;
        for (int i = 0; i < searchTypes.length; i++) {
            if (searchTypes[i].equals(lastSearchType)) {
                typePos = i;
                break;
            }
        }
        spinnerSearchType.setSelection(typePos);

        spinnerSearchType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String selected = searchTypes[position];
                if (!searchInResults) cbSearchSubfolders.setVisibility(View.VISIBLE);
                cbMatchCase.setVisibility(View.VISIBLE);
                cbRegex.setVisibility(View.VISIBLE);
                cbExactlyMatch.setVisibility(View.VISIBLE);
                cbHex.setVisibility(View.GONE);

                if (selected.equals("Smali")) {
                    cbExactlyMatch.setVisibility(View.GONE);
                } else if (selected.equals("Integer")) {
                    cbMatchCase.setVisibility(View.GONE);
                    cbRegex.setVisibility(View.GONE);
                    cbExactlyMatch.setVisibility(View.GONE);
                    cbHex.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Search")
                .setView(dialogView)
                .setPositiveButton("OK", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String query = etFind.getText().toString().trim(), type = spinnerSearchType.getSelectedItem().toString(), path = searchInResults ? "" : etPath.getText().toString();
                if (query.isEmpty()) { SketchwareUtil.showMessage(requireActivity(), "Search text cannot be empty"); return; }
                boolean isHex = cbHex.isChecked(), useExcludeList = cbUseExcludeList.isChecked();
                if (type.equals("Integer")) {
                    try {
                        String q = query;
                        if (isHex) { if (q.startsWith("0x")) q = q.substring(2); Long.parseLong(q, 16); }
                        else { if (q.startsWith("0x")) throw new NumberFormatException(); Long.parseLong(q); }
                    } catch (Exception e) { SketchwareUtil.showMessage(requireActivity(), "Value format error"); return; }
                }
                lastSearchQuery = query; lastSearchType = type;
                if (!searchInResults) { lastSearchPath = path; lastSearchSubfolders = cbSearchSubfolders.isChecked(); lastUseExcludeList = useExcludeList; prefs.edit().putBoolean("use_exclude_list", useExcludeList).apply(); }
                lastMatchCase = cbMatchCase.isChecked(); lastIsRegex = cbRegex.isChecked(); lastExactlyMatch = cbExactlyMatch.isChecked(); lastIsHex = isHex;
                List<String> scopeClasses = null;
                if (searchInResults) { scopeClasses = new ArrayList<>(); collectClassFullNames(searchResults, scopeClasses); }
                final SearchTask task;
                try {
                    task = new SearchTask(SearchFragment.this, query, path, type, lastSearchSubfolders, lastMatchCase, lastIsRegex, lastExactlyMatch, isHex, scopeClasses, useExcludeList);
                } catch (java.util.regex.PatternSyntaxException error) {
                    SketchwareUtil.showMessage(requireActivity(), "Invalid regular expression: " + error.getDescription());
                    return;
                }
                searchResults.clear(); currentQuery = null; adapter.refreshVisibleNodes(); updateUIState();
                task.start();
                dialog.dismiss();
            }
        });
    }

    @SuppressLint("SetTextI18n")
    private void showReplaceDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_search_dex, null);
        AutoCompleteTextView etFind = dialogView.findViewById(R.id.et_find);
        EditText etReplaceWith = dialogView.findViewById(R.id.et_path);
        Spinner spinnerSearchType = dialogView.findViewById(R.id.spinner_search_type);
        CheckBox cbSearchSubfolders = dialogView.findViewById(R.id.cb_search_subfolders);
        CheckBox cbMatchCase = dialogView.findViewById(R.id.cb_match_case);
        CheckBox cbRegex = dialogView.findViewById(R.id.cb_regex);
        CheckBox cbExactlyMatch = dialogView.findViewById(R.id.cb_exactly_match);
        CheckBox cbHex = dialogView.findViewById(R.id.cb_hex);
        TextView pathTitleText = dialogView.findViewById(R.id.pathTitle);
        View layoutExcludeList = dialogView.findViewById(R.id.layout_exclude_list);

        pathTitleText.setText("Replace with");
        layoutExcludeList.setVisibility(View.GONE);
        cbSearchSubfolders.setVisibility(View.GONE);
        cbHex.setVisibility(View.GONE);

        etFind.setText(lastSearchQuery);
        etReplaceWith.setText(lastReplaceWith);
        cbMatchCase.setChecked(lastMatchCase);
        cbRegex.setChecked(lastIsRegex);
        cbExactlyMatch.setChecked(lastExactlyMatch);

        String[] replaceTypes = {"Smali", "String"};
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, replaceTypes);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSearchType.setAdapter(typeAdapter);

        spinnerSearchType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String selected = replaceTypes[position];
                cbExactlyMatch.setVisibility(selected.equals("String") ? View.VISIBLE : View.GONE);
                cbRegex.setVisibility(selected.equals("Smali") || selected.equals("String") ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        // Setting up the replace dialog with options like regex and match case
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Replace")
                .setView(dialogView)
                .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String find = etFind.getText().toString(), replace = etReplaceWith.getText().toString(), type = spinnerSearchType.getSelectedItem().toString();
                        lastSearchQuery = find; lastReplaceWith = replace; lastMatchCase = cbMatchCase.isChecked(); lastIsRegex = cbRegex.isChecked(); lastExactlyMatch = cbExactlyMatch.isChecked();
                        List<String> scopeClasses = new ArrayList<>();
                        collectClassFullNames(searchResults, scopeClasses);
                        try {
                            DexEditorActivity activity = (DexEditorActivity) getActivity();
                            ClassTree targetTree = activity == null ? null : activity.getClassTree();
                            if (targetTree == null) {
                                throw new IllegalStateException("No DEX is open.");
                            }
                            ClassTree.EditSnapshot snapshot = targetTree.snapshotEditState();
                            long baseRevision = snapshot.getRevision();
                            Map<String, ClassDef> classSnapshot = snapshot.getClassDefs();
                            SearchReplaceTask task = new SearchReplaceTask(SearchFragment.this, targetTree, baseRevision, classSnapshot,
                                    find, replace, type, lastMatchCase, lastIsRegex, lastExactlyMatch,
                                    scopeClasses);
                            activeReplaceTask = task;
                            task.start();
                        } catch (IllegalArgumentException e) {
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("Invalid replacement rule")
                                    .setMessage(e.getMessage())
                                    .setPositiveButton("OK", null)
                                    .show();
                        } catch (IllegalStateException e) {
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("Replacement unavailable")
                                    .setMessage(e.getMessage())
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }



}
