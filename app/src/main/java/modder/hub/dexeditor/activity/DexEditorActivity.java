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

package modder.hub.dexeditor.activity;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.os.BundleCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.tools.smali.smali2.Smali;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow;
import modder.hub.dexeditor.R;
import modder.hub.dexeditor.adapter.TreeAdapter;
import modder.hub.dexeditor.fragment.EditorFragment;
import modder.hub.dexeditor.fragment.SearchFragment;
import modder.hub.dexeditor.fragment.SmaliMethodFieldListFragment;
import modder.hub.dexeditor.model.EditorTab;
import modder.hub.dexeditor.model.TreeNode;
import modder.hub.dexeditor.smali.SmaliHelper;
import modder.hub.dexeditor.smali.SmaliInstructionHelper;
import modder.hub.dexeditor.utils.ClassTree;
import modder.hub.dexeditor.utils.EdgeToEdge;
import modder.hub.dexeditor.utils.FilePermissionManager;
import modder.hub.dexeditor.utils.EditorHelper;
import modder.hub.dexeditor.utils.EditorPositionManager;
import modder.hub.dexeditor.utils.Notify_MT;
import modder.hub.dexeditor.utils.SketchwareUtil;
import modder.hub.dexeditor.utils.UIHelper;
import modder.hub.dexeditor.views.AlertCircularProgress;
import modder.hub.dexeditor.views.AlertProgress;
import modder.hub.dexeditor.views.FastScrollerRecyclerView;
import modder.hub.dexeditor.views.SmaliInstructionsDialog;
import modder.hub.dexeditor.views.TextActionWindow;

/**
 * DexEditorActivity: The main entry point for the DEX editor.
 * Re-sequenced and refactored for better clarity and utility.
 * Author: @developer-krushna
 */
public class DexEditorActivity extends AppCompatActivity implements EditorTabsAdapter.Listener {

    // --- Constants and Static State ---
    private static final long DOUBLE_PRESS_INTERVAL = 2000;
    private volatile ClassTree classTree;
    private final StringWorkspaceController stringWorkspace = new StringWorkspaceController(this);
    private final List<EditorTab> tabs = new ArrayList<>();
    private int currentTabIndex = -1;
    private Parcelable navigationMethodsState;
    private Parcelable navigationStringsState;
    private boolean navigationShowingStrings;
    final List<TreeNode> historyNodes = new ArrayList<>();
    private final java.util.Stack<Integer> tabNavigationHistory = new java.util.Stack<>();
    private final ClassTree.CompilationOptions sessionOptions = new ClassTree.CompilationOptions();
    final List<TreeNode> treeRoots = new ArrayList<>();
    final List<TreeNode> modifiedNodes = new ArrayList<>();
    // --- Member Fields ---
    public int dexVersion;
    public List<TreeNode> searchNodes = new ArrayList<>();
    public String pendingSearchPath = null;
    public EditorTabsAdapter tabsAdapter;
    boolean needsModifiedTreeRebuild = true;
    boolean needsExplorerRefresh = false;
    private long lastBackPressTime = 0;
    private SharedPreferences dexPref;
    private Menu optionsMenu;
    // --- UI Components ---
    private DrawerLayout drawerLayout;
    private Toolbar drawerToolbar;
    private ActionBarDrawerToggle drawerToggle;
    private ViewPager2 viewPager;
    private TabAdapter tabAdapter;
    private FastScrollerRecyclerView tabsRecyclerView;
    private View classListContainer;
    ViewPager2 explorerViewPager;
    FloatingActionButton fabDelete;
    private LinearLayout fabBackground;
    private AlertCircularProgress coreProgressDialog;
    private AlertProgress progressDialog;
    private ActivityResultLauncher<String[]> patchFileLauncher;
    private SmaliPatchWorkflow patchWorkflow;
    private DexLoadTask dexLoadTask;
    private DexSaveAndExitTask saveAndExitTask;
    private final BackgroundTaskScope backgroundTasks = new BackgroundTaskScope();

    public ClassTree getClassTree() {
        return classTree;
    }

    public List<EditorTab> getOpenTabsSnapshot() {
        return new ArrayList<>(tabs);
    }

    StringWorkspaceController getStringWorkspace() {
        return stringWorkspace;
    }

    public String consumePendingStringSearchQuery() {
        return stringWorkspace.consumePendingSearchQuery();
    }

    public void saveNavigationState(Parcelable methods, Parcelable strings, boolean showingStrings) {
        navigationMethodsState = methods;
        navigationStringsState = strings;
        navigationShowingStrings = showingStrings;
    }

    public Parcelable getNavigationMethodsState() { return navigationMethodsState; }
    public Parcelable getNavigationStringsState() { return navigationStringsState; }
    public boolean isNavigationShowingStrings() { return navigationShowingStrings; }

    // ==========================================
    // Lifecycle Methods
    // ==========================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            navigationMethodsState = BundleCompat.getParcelable(
                    savedInstanceState, "navigation.methods", Parcelable.class);
            navigationStringsState = BundleCompat.getParcelable(
                    savedInstanceState, "navigation.strings", Parcelable.class);
            navigationShowingStrings = savedInstanceState.getBoolean("navigation.showingStrings");
        }
        setContentView(R.layout.dex_editor);
        EdgeToEdge.apply(this);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack(true);
            }
        });
        patchWorkflow = new SmaliPatchWorkflow(this);

        patchFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) patchWorkflow.load(uri);
        });

        ActivityResultLauncher<String[]> requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                new androidx.activity.result.ActivityResultCallback<Map<String, Boolean>>() {
                    @Override
                    public void onActivityResult(Map<String, Boolean> permissions) {
                        if (FilePermissionManager.hasStoragePermission(DexEditorActivity.this)) {
                            initializeLogic();
                        } else {
                            Notify_MT.Notify(DexEditorActivity.this, "Permission Denied", "Storage permission is required to edit DEX files.", "Go Back");
                            finish();
                        }
                    }
                }
        );

        initialize(savedInstanceState);

        if (FilePermissionManager.hasStoragePermission(this)) {
            initializeLogic();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            finish();
        } else {
            requestPermissionLauncher.launch(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            });
        }
    }

    @Override
    protected void onDestroy() {
        DexLoadTask loader = dexLoadTask;
        dexLoadTask = null;
        if (loader != null) loader.cancel();
        DexSaveAndExitTask saveTask = saveAndExitTask;
        saveAndExitTask = null;
        if (saveTask != null) saveTask.cancel();
        backgroundTasks.cancelAll();
        if (patchWorkflow != null) {
            patchWorkflow.close();
            patchWorkflow = null;
        }
        super.onDestroy();
        EditorFragment.clearCache();
    }

    public void runOnUiThreadIfAlive(Runnable action) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) action.run();
        });
    }

    private void handleBack(boolean checkDoublePress) {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }

        if (isSelectionModeActive()) {
            cancelSelectionMode();
            return;
        }

        if (viewPager.getVisibility() == View.VISIBLE) {
            if (viewPager.getTranslationX() != 0) return; // Ignore back while animating
            if (!tabNavigationHistory.isEmpty()) {
                int lastIndex = tabNavigationHistory.pop();
                if (lastIndex >= 0 && lastIndex < tabs.size()) {
                    viewPager.setCurrentItem(lastIndex, true);
                    return;
                }
            }
            hideEditor();
            return;
        }

        boolean anyModified = false;
        for (EditorTab tab : tabs) {
            if (tab.isModified) {
                anyModified = true;
                break;
            }
        }

        if (anyModified || (classTree != null && classTree.hasUnsavedChanges())
                || isCompilationOptionsActive()) {
            showExitConfirmation();
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (!checkDoublePress || currentTime - lastBackPressTime < DOUBLE_PRESS_INTERVAL) {
            exitActivity();
        } else {
            lastBackPressTime = currentTime;
            SketchwareUtil.showMessage(this, "Press back again to exit");
        }
    }

    // ==========================================
    // Initialization & UI Setup
    // ==========================================

    private void initialize(Bundle ignoredSavedInstanceState) {
        drawerLayout = findViewById(R.id.drawer_layout);
        classListContainer = findViewById(R.id.class_list_container);
        viewPager = findViewById(R.id.view_pager);
        tabsRecyclerView = findViewById(R.id.tabs_recycler_view);
        Toolbar toolbar = findViewById(R.id._toolbar);
        setSupportActionBar(toolbar);

        drawerToolbar = findViewById(R.id.drawer_toolbar);
        drawerToolbar.setOverflowIcon(ContextCompat.getDrawable(this, R.drawable.ic_more_mt));
        if (drawerToolbar.getOverflowIcon() != null) {
            androidx.core.graphics.drawable.DrawableCompat.setTint(drawerToolbar.getOverflowIcon(), Color.WHITE);
        }
        setupDrawerToolbar();

        drawerToggle = new ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(drawerToggle);
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                // Ensure the "Home" item highlight and menu states are up to date
                if (tabsAdapter != null) {
                    tabsAdapter.notifyItemChanged(0);
                }
                updateDrawerMenuState();

                if (viewPager.getVisibility() == View.VISIBLE && currentTabIndex != -1) {
                    tabsRecyclerView.scrollToPosition(currentTabIndex + 1);
                }
            }
        });
        drawerToggle.syncState();

        tabAdapter = new TabAdapter(this);
        viewPager.setAdapter(tabAdapter);
        viewPager.setOffscreenPageLimit(10);
        viewPager.setUserInputEnabled(false);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                int previousIndex = currentTabIndex;
                currentTabIndex = position;

                // Targeted updates to reduce lag
                if (previousIndex != -1) {
                    tabsAdapter.notifyItemChanged(previousIndex + 1);
                }
                tabsAdapter.notifyItemChanged(position + 1);
                tabsAdapter.notifyItemChanged(0); // Update Home item selection state

                updateToolbar();
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    tabsRecyclerView.scrollToPosition(position + 1);
                }
            }
        });

        tabsRecyclerView.setTrackVisible(false);
        tabsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        tabsAdapter = new EditorTabsAdapter(tabs, viewPager, drawerLayout, this);
        tabsRecyclerView.setAdapter(tabsAdapter);
        setupTabsTouchHelper();

        fabDelete = findViewById(R.id.fab_delete);
        fabDelete.setOnClickListener(new DeleteButtonClickListener());

        initializeExplorerTabs();
        initializeFab();
        dexPref = getSharedPreferences("dexPref", Activity.MODE_PRIVATE);
    }

    private void initializeExplorerTabs() {
        TabLayout explorerTabLayout = findViewById(R.id.explorer_tab_layout);
        explorerViewPager = findViewById(R.id.explorer_view_pager);

        ExplorerTabAdapter explorerTabAdapter = new ExplorerTabAdapter(this);
        explorerViewPager.setAdapter(explorerTabAdapter);
        explorerViewPager.setOffscreenPageLimit(1);

        new TabLayoutMediator(explorerTabLayout, explorerViewPager, new TabLayoutMediator.TabConfigurationStrategy() {
            @Override
            public void onConfigureTab(@NonNull TabLayout.Tab tab, int position) {
                String[] titles = {"Explorer", "History", "Search", "Strings"};
                tab.setText(titles[position]);
            }
        }).attach();

        explorerTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                // Default behavior handled by ViewPager2
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                Fragment fragment = getSupportFragmentManager().findFragmentByTag("f" + (2000 + position));
                if (fragment != null && fragment.getView() != null) {
                    RecyclerView rv = null;
                    if (fragment instanceof ExplorerPageFragment) {
                        rv = ((ExplorerPageFragment) fragment).rv;
                    } else if (fragment instanceof SearchFragment) {
                        rv = fragment.getView().findViewById(R.id.search_results_rv);
                    }

                    if (rv != null) {
                        rv.scrollToPosition(0);
                    }
                }
            }
        });

        explorerViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (position == 3 && stringWorkspace.getStrings().isEmpty()) {
                    stringWorkspace.load();
                }
                refreshExplorerPage(position);
            }
        });
    }

    private void initializeLogic() {
        // Pre-initialize heavy components in background to avoid lag on first editor open
        final android.content.Context appContext = getApplicationContext();
        executeBackgroundTask("dex-editor-preload", () -> {
            SmaliInstructionHelper.init(appContext);
            EditorFragment.ensureLanguageInitialized(appContext);
        });

        tabs.clear();
        tabNavigationHistory.clear();
        currentTabIndex = -1;
        if (classTree != null) classTree.clearUnsavedChanges();

        EditorPositionManager.getInstance(this).clear();

        File[] cacheFiles = getCacheDir().listFiles();
        if (cacheFiles != null) {
            for (File file : cacheFiles) {
                if (file.isDirectory() && file.getName().startsWith("dex_editor_")) {
                    deleteRecursive(file);
                }
            }
        }

        List<String> dexPaths = getIntent().getStringArrayListExtra("SelectedDexFiles");
        setTitle("Dex Editor Plus");
        showProcessingProgress(true);
        fabDelete.setBackgroundTintList(ColorStateList.valueOf(0xFFF44336));
        fabDelete.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        fabDelete.hide();

        String uniqueId = (System.currentTimeMillis() % 1000000) + "_" + (new java.util.Random().nextInt(9000) + 1000);
        File cacheDir = new File(getCacheDir(), "dex_editor_" + uniqueId);

        if (dexPaths != null && !dexPaths.isEmpty()) {
            dexLoadTask = new DexLoadTask(this, dexPaths, cacheDir.getAbsolutePath());
            dexLoadTask.start();
        } else {
            showErrorDialog("No DEX files provided");
            finish();
        }
    }

    void onDexLoadComplete(ClassTree loadedTree, List<TreeNode> roots, List<TreeNode> modified) {
        if (isFinishing() || isDestroyed()) {
            loadedTree.clearAll();
            return;
        }
        classTree = loadedTree;
        try {
            treeRoots.clear();
            treeRoots.addAll(roots);
            modifiedNodes.clear();
            modifiedNodes.addAll(modified);
            needsModifiedTreeRebuild = false;
            needsExplorerRefresh = true;
            updateToolbar();
            refreshExplorerPage(0);
            showTreeView();
        } catch (Exception e) {
            showErrorDialog("UI update failed: " + e.getMessage());
        }
    }

    void onDexLoadError(Exception error) {
        showErrorDialog("Failed to process DEX files:\n\n" + error.getMessage());
    }

    void onDexLoadFinished(DexLoadTask task) {
        if (dexLoadTask == task) {
            dexLoadTask = null;
            showProcessingProgress(false);
        }
    }

    private void setupTabsTouchHelper() {
        androidx.recyclerview.widget.ItemTouchHelper.Callback callback = new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP | androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int fromPos = viewHolder.getBindingAdapterPosition();
                int toPos = target.getBindingAdapterPosition();
                if (fromPos == 0 || toPos == 0) return false;

                int fromTab = fromPos - 1;
                int toTab = toPos - 1;

                EditorTab movedTab = tabs.remove(fromTab);
                tabs.add(toTab, movedTab);

                if (currentTabIndex == fromTab) currentTabIndex = toTab;
                else if (fromTab < currentTabIndex && toTab >= currentTabIndex) currentTabIndex--;
                else if (fromTab > currentTabIndex && toTab <= currentTabIndex) currentTabIndex++;

                tabAdapter.notifyItemMoved(fromTab, toTab);
                tabsAdapter.notifyItemMoved(fromPos, toPos);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                return 0;
            }
        };
        new androidx.recyclerview.widget.ItemTouchHelper(callback).attachToRecyclerView(tabsRecyclerView);
    }

    // ==========================================
    // Toolbar & Menu Management
    // ==========================================

    private void updateToolbar() {
        if (getSupportActionBar() == null) return;

        if (viewPager.getVisibility() == View.VISIBLE && currentTabIndex != -1 && currentTabIndex < tabs.size()) {
            getSupportActionBar().setTitle(tabs.get(currentTabIndex).title);
            getSupportActionBar().setSubtitle(null);
        } else {
            getSupportActionBar().setTitle("Dex Editor Plus");
            setToolbarSubtitle(null);
        }

        drawerToggle.setDrawerIndicatorEnabled(true);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        drawerToggle.syncState();
        invalidateOptionsMenu();
    }

    public void setToolbarSubtitle(String subtitle) {
        if (getSupportActionBar() == null) return;
        if (viewPager.getVisibility() != View.VISIBLE) {
            getSupportActionBar().setSubtitle((subtitle == null || subtitle.isEmpty()) ? "Temporary project" : subtitle.replace("/", "."));
        } else {
            getSupportActionBar().setSubtitle(subtitle);
        }
    }

    public void setToolbarTitle(String title) {
        if (getSupportActionBar() == null) return;
        getSupportActionBar().setTitle(title);
    }

    public void toggleDrawer() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START))
            drawerLayout.closeDrawer(GravityCompat.START);
        else drawerLayout.openDrawer(GravityCompat.START);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        this.optionsMenu = menu;
        getMenuInflater().inflate(viewPager.getVisibility() == View.VISIBLE ? R.menu.editor_menu : R.menu.dex_editor_main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (viewPager.getVisibility() == View.VISIBLE && currentTabIndex != -1 && currentTabIndex < tabs.size()) {
            EditorTab tab = tabs.get(currentTabIndex);
            boolean isSmali = tab.type == 0;
            boolean isGraph = tab.type == 2;

            menu.findItem(R.id.undo).setVisible(isSmali);
            menu.findItem(R.id.redo).setVisible(isSmali);

            MenuItem saveItem = menu.findItem(R.id.save);
            saveItem.setVisible(isSmali);
            saveItem.setEnabled(tab.isModified);
            if (saveItem.getIcon() != null) saveItem.getIcon().setAlpha(tab.isModified ? 255 : 100);

            menu.findItem(R.id.navigation).setVisible(isSmali);
            menu.findItem(R.id.edit_menu).setVisible(isSmali);

            // Sub-items in "More"
            menu.findItem(R.id.smali2java).setVisible(isSmali);
            menu.findItem(R.id.smali_instruction).setVisible(isSmali);

            MenuItem jumpToLine = menu.findItem(R.id.jumpToLine);
            if (jumpToLine != null) jumpToLine.setVisible(!isGraph);

            MenuItem readOnlyItem = menu.findItem(R.id.read_only);
            readOnlyItem.setVisible(isSmali);
            readOnlyItem.setChecked(tab.isReadOnly);// preserve the read-only feature for targeted tab only

            MenuItem wrapItem = menu.findItem(R.id.wrap_text);
            if (wrapItem != null) {
                wrapItem.setVisible(!isGraph);
                wrapItem.setChecked(getSharedPreferences("editor_prefs", MODE_PRIVATE).getBoolean("wrap_text", false));
            }
            handleUndoRedo();
        }
        return super.onPrepareOptionsMenu(menu);
    }


    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        int id = item.getItemId();

        if (id == R.id.action_compile) {
            startSaveAndExit();
            return true;
        } else if (id == R.id.action_apply_patch) {
            patchFileLauncher.launch(new String[]{"text/plain"});
            return true;
        } else if (id == R.id.action_preferences) {
            showCompilationOptionsDialog();
            return true;
        } else if (id == R.id.action_exit) {
            handleBack(false);
            return true;
        }

        if (viewPager.getVisibility() == View.VISIBLE && currentTabIndex != -1 && currentTabIndex < tabs.size()) {
            EditorTab tab = tabs.get(currentTabIndex);

            if (id == R.id.close) {
                closeTabWithPrompt(currentTabIndex);
                return true;
            } else if (id == R.id.preference) {
                startActivity(new Intent(DexEditorActivity.this, SettingsActivity.class));
                return true;
            }

            if (tab.type == 2) { // Graph
                return super.onOptionsItemSelected(item);
            }

            EditorFragment editorFragment = getCurrentFragment();
            if (editorFragment == null) return super.onOptionsItemSelected(item);

            CodeEditor editor = editorFragment.getEditor();

            if (id == R.id.undo) {
                editor.undo();
                handleUndoRedo();
                return true;
            } else if (id == R.id.redo) {
                editor.redo();
                handleUndoRedo();
                return true;
            } else if (id == R.id.save) {
                saveCurrentTab();
                return true;
            } else if (id == R.id.search) {
                // testing , i willl add a search bar like my modder hub app have.
                // but I have plan to replace the sora editor with my custom MH-Texteditor
                try {
                    java.lang.reflect.Method method = editor.getSearcher().getClass().getMethod("showSearchPanel");
                    method.invoke(editor.getSearcher());
                } catch (Exception ignored) {
                    // ignored
                }
                return true;
            } else if (id == R.id.navigation) {
                editorFragment.showMethodFieldList();
                return true;
            } else if (id == R.id.copy_line) {
                EditorHelper.copyLine(editor);
                return true;
            } else if (id == R.id.cut_line) {
                EditorHelper.cutLine(editor);
                return true;
            } else if (id == R.id.delete_line) {
                EditorHelper.deleteLine(editor);
                return true;
            } else if (id == R.id.empty_line) {
                EditorHelper.emptyLine(editor);
                return true;
            } else if (id == R.id.duplicate_line) {
                EditorHelper.duplicateLine(editor);
                return true;
            } else if (id == R.id.convert_uppercase) {
                EditorHelper.convertSelectedTextCase(editor, true);
                return true;
            } else if (id == R.id.convert_lowercase) {
                EditorHelper.convertSelectedTextCase(editor, false);
                return true;
            } else if (id == R.id.increase_indent) {
                EditorHelper.indent(editor, true);
                return true;
            } else if (id == R.id.decrease_indent) {
                EditorHelper.indent(editor, false);
                return true;
            } else if (id == R.id.toggle_comment) {
                toggleComment(editorFragment);
                return true;
            } else if (id == R.id.jumpToLine) {
                showJumpToLineDialog(editorFragment);
                return true;
            } else if (id == R.id.wrap_text) {
                item.setChecked(!item.isChecked());
                editor.setWordwrap(item.isChecked());
                getSharedPreferences("editor_prefs", MODE_PRIVATE).edit().putBoolean("wrap_text", item.isChecked()).apply();
                return true;
            } else if (id == R.id.read_only) {
                tab.isReadOnly = !tab.isReadOnly;
                item.setChecked(tab.isReadOnly);
                editor.setEditable(!tab.isReadOnly);
                return true;
            } else if (id == R.id.smali_instruction) {
                String instruction = getCurrentLineSmaliInstruction(editorFragment);
                if (instruction != null) {
                    new SmaliInstructionsDialog(this, "smali_instructions.txt", instruction).show();
                } else {
                    new SmaliInstructionsDialog(this, "smali_instructions.txt").show();
                }
                return true;
            } else if (id == R.id.smali2java) {
                smali2java(editorFragment);
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    // get the smali instruction from cursor position in editor
    public String getCurrentLineSmaliInstruction(EditorFragment fragment) {
        CodeEditor editor = fragment.getEditor();
        Cursor cursor = editor.getCursor();
        Content content = editor.getText();

        int line = cursor.getLeftLine();
        String lineText = content.getLineString(line);
        String trimmed = lineText.trim();

        if (trimmed.isEmpty()) {
            return null;
        }

        int endOfFirstWord = 0;
        while (endOfFirstWord < trimmed.length()) {
            char c = trimmed.charAt(endOfFirstWord);
            if (Character.isWhitespace(c)) break;
            if (c == '{' || c == '}' || c == ';') break;
            endOfFirstWord++;
        }

        String firstWord = trimmed.substring(0, endOfFirstWord);
        return SmaliInstructionHelper.isSmaliInstruction(firstWord) ? firstWord : null;
    }

    // when closing the editor fragment tab if the class is edited then there will be a prompt for
    private void closeTabWithPrompt(int index) {
        if (index < 0 || index >= tabs.size()) return;
        final EditorTab tab = tabs.get(index);
        final String className = tab.className;

        EditorFragment fragment = getFragmentAtIndex(index);
        if (fragment != null) {
            fragment.setClosing(true);
        }

        if (tab.isModified) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Warning")
                    .setMessage("Class '" + tab.title + "' has been modified. Save the code ?")
                    .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            saveTab(tab, success -> {
                                if (!success) {
                                    if (fragment != null) fragment.setClosing(false);
                                    return;
                                }
                                clearPositionSaving(className);
                                removeTab(tab);
                            });
                        }
                    })
                    .setNeutralButton("Don't Save", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            clearPositionSaving(className);
                            removeTab(tab);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            clearPositionSaving(className);
            removeTab(tab);
        }
    }

    void clearPositionSaving(String className) {
        EditorPositionManager.getInstance(this).removePosition(className);
    }

    // smali2java
    private void smali2java(EditorFragment fragment) {
        String code = fragment.getCode();
        String className = fragment.getClassName();
        String title = SmaliHelper.extractSimpleName(className) + ".java";

        // Check if java tab already exists for this class
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).className.equals(className) && tabs.get(i).type == 1 && tabs.get(i).title.equals(title)) {
                showEditor(i);
                return;
            }
        }

        AlertCircularProgress pd = new AlertCircularProgress(this);
        pd.setMessage("Decompiling...");
        pd.show();
        executeBackgroundTask("dex-editor-smali-to-java", new Runnable() {
            @Override
            public void run() {
                try {
                    final String java = modder.hub.dexeditor.smali.Smali2Java.translate(code, dexVersion);
                    runOnUiThreadIfAlive(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            addTab(className, title, java, 1); // adding the java item in the recent opened classes list
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThreadIfAlive(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            Notify_MT.Notify(DexEditorActivity.this, getString(R.string.error), e.toString(), getString(R.string.close));
                        }
                    });
                }
            }
        });
    }

    // smali toggle comment
    private void toggleComment(EditorFragment fragment) {
        try {
            TextActionWindow window = (TextActionWindow) fragment.getEditor().getComponent(EditorTextActionWindow.class);
            window.toggleComment();
        } catch (Exception ignored) {
        }
    }

    // jump to line
    private void showJumpToLineDialog(EditorFragment fragment) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_jump_to_line, null);
        EditText editText = view.findViewById(R.id.editText);
        CodeEditor smaliEditor = fragment.getEditor();

        // set dynamic hint
        String hint = "1⋯" + smaliEditor.getLineCount();
        editText.setHint(hint);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Jump to line")
                .setView(view)
                .setPositiveButton("OK", null)
                .setNegativeButton("Cancel", null);

        AlertDialog dialog_mt = builder.create();
        dialog_mt.show();
        dialog_mt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (editText.getText().toString().isEmpty()) {
                    editText.setError("Enter something !");
                } else {
                    try {
                        smaliEditor.jumpToLine(Integer.parseInt(editText.getText().toString()) - 1);
                        dialog_mt.dismiss();
                    } catch (Exception e) {
                        editText.setError("Value is out of range.");
                    }
                }

            }
        });
    }


    public void handleUndoRedo() {
        if (optionsMenu == null || viewPager.getVisibility() != View.VISIBLE) return;
        EditorFragment fragment = getCurrentFragment();
        if (fragment != null && fragment.getEditor() != null) {
            MenuItem undo = optionsMenu.findItem(R.id.undo);
            MenuItem redo = optionsMenu.findItem(R.id.redo);
            MenuItem save = optionsMenu.findItem(R.id.save);

            boolean isModified = false;
            for (EditorTab tab : tabs) {
                if (tab.className.equals(fragment.getClassName())) {
                    isModified = tab.isModified;
                    break;
                }
            }

            if (save != null) {
                save.setEnabled(isModified);
                if (save.getIcon() != null) {
                    save.getIcon().setAlpha(isModified ? 255 : 100);
                }
            }

            if (undo != null) {
                undo.setEnabled(fragment.getEditor().canUndo());
                if (undo.getIcon() != null) {
                    androidx.core.graphics.drawable.DrawableCompat.setTint(undo.getIcon(), Color.WHITE);
                    undo.getIcon().setAlpha(undo.isEnabled() ? 255 : 100);
                }
            }
            if (redo != null) {
                redo.setEnabled(fragment.getEditor().canRedo());
                if (redo.getIcon() != null) {
                    androidx.core.graphics.drawable.DrawableCompat.setTint(redo.getIcon(), Color.WHITE);
                    redo.getIcon().setAlpha(redo.isEnabled() ? 255 : 100);
                }
            }
        }
    }

    public EditorFragment getCurrentFragment() {
        return getFragmentAtIndex(viewPager.getCurrentItem());
    }

    public EditorFragment getFragmentAtIndex(int index) {
        if (index < 0 || index >= tabs.size()) return null;
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("f" + tabs.get(index).id);
        if (fragment instanceof EditorFragment) {
            return (EditorFragment) fragment;
        }
        return null;
    }

    private void showTreeView() {
        if (explorerViewPager != null) {
            explorerViewPager.setCurrentItem(0);
        }
    }

    public void refreshExplorerPage(int position) {
        if (position == 1 && classTree != null && needsModifiedTreeRebuild) {
            modifiedNodes.clear();
            modifiedNodes.addAll(classTree.buildEditedFullTree());
            needsModifiedTreeRebuild = false;
        }

        if (position == 0 && !needsExplorerRefresh) {
             // Avoid heavy refresh during swipe if not needed
             return;
        }

        Fragment fragment = getSupportFragmentManager().findFragmentByTag("f" + (2000 + position));
        if (fragment instanceof ExplorerPageFragment) {
            final ExplorerPageFragment explorerFrag = (ExplorerPageFragment) fragment;
            if (explorerFrag.rv != null) {
                explorerFrag.rv.post(new Runnable() {
                    @Override
                    public void run() {
                        explorerFrag.updateUI();
                        if (position == 0) needsExplorerRefresh = false;
                    }
                });
            }
        } else if (fragment instanceof SearchFragment) {
            ((SearchFragment) fragment).refreshUI();
        }
    }

    // locate the class in the main treeview in the EXPLORER Tab
    public void locateClass(final String className) {
        if (viewPager == null || explorerViewPager == null) return;

        // Ensure we are not in editor mode
        if (viewPager.getVisibility() == View.VISIBLE) {
            hideEditor();
        }

        // Close navigation drawer if it's open
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawers();
        }

        // Switch to the first tab (EXPLORER)
        explorerViewPager.setCurrentItem(0, true);

        // Robust locate with retry mechanism to ensure fragment is ready
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            private int retryCount = 0;

            @Override
            public void run() {
                Fragment f = getSupportFragmentManager().findFragmentByTag("f2000");
                if (f instanceof ExplorerPageFragment && f.isAdded() && f.getView() != null) {
                    ((ExplorerPageFragment) f).locateNode(className);
                } else if (retryCount < 10) { // Retry for up to 1 second
                    retryCount++;
                    handler.postDelayed(this, 100);
                }
            }
        });
    }

    // open the class from the treenodes
    public void openClass(String className) {
        // Add/Update history
        TreeNode existingNode = null;
        for (TreeNode node : historyNodes) {
            if (node.getFullName().equals(className)) {
                existingNode = node;
                break;
            }
        }
        if (existingNode != null) {
            historyNodes.remove(existingNode);
            historyNodes.add(0, existingNode);
        } else {
            historyNodes.add(0, new TreeNode(SmaliHelper.extractSimpleName(className), className, 0, false));
        }

        refreshExplorerPage(1);

        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).className.equals(className) && tabs.get(i).type == 0) {
                showEditor(i);
                return;
            }
        }

        try {
            dexVersion = classTree.getOpenedDexVersion();
        } catch (Exception e) {
            dexVersion = dexPref.getInt("dexVer", 35);
        }

        // Add tab with content if it's already in pendingSmaliMap or if it's already in another tab
        String content = null;
        if (classTree.hasPendingSmali(className))
            content = classTree.getPendingSmali(className);

        addTab(className, SmaliHelper.extractSimpleName(className), content, 0);
    }

    // method reposnsible for opening the editor tab according to the search reasult , line number and class name
    public void openClassAtLine(String className, int lineNumber, String query) {
        openClassAtLine(className, lineNumber, -1, query);
    }

    public void openClassAtLine(String className, int lineNumber, int column, String query) {
        openClass(className);

        // Find the tab we just opened or that was already open
        EditorTab targetTab = null;
        int tabIndex = -1;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).className.equals(className) && tabs.get(i).type == 0) {
                targetTab = tabs.get(i);
                tabIndex = i;
                break;
            }
        }

        if (targetTab != null) {
            // Set pending navigation info
            targetTab.pendingLine = lineNumber;
            targetTab.pendingColumn = column;
            targetTab.pendingQuery = query;

            // If fragment is already ready, navigate now
            EditorFragment fragment = getFragmentAtIndex(tabIndex);
            if (fragment != null && fragment.getEditor() != null && fragment.getEditor().getText().getLineCount() > lineNumber) {
                fragment.navigateTo(lineNumber, column, query);
                // Clear pending once navigated
                targetTab.pendingLine = -1;
                targetTab.pendingColumn = -1;
                targetTab.pendingQuery = null;
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void addTab(String className, String title, String code, int type) {
        addTab(className, title, null, code, type);
    }

    public void addTab(String className, String title, String subtitle, String code, int type) {
        // Check if tab already exists for this class and title (avoid duplicates)
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab existingTab = tabs.get(i);
            if (existingTab.className.equals(className) && existingTab.title.equals(title) &&
                    (subtitle == null || subtitle.equals(existingTab.subtitle)) && existingTab.type == type) {
                showEditor(i);
                return;
            }
        }

        EditorTab tab = new EditorTab(className, title, subtitle, code, type);
        if (type == 1 || type == 2) { // Java or Graph
            tab.isReadOnly = true;
        }
        tabs.add(tab);
        tabAdapter.notifyItemInserted(tabs.size() - 1);
        tabsAdapter.notifyItemInserted(tabs.size());
        showEditor(tabs.size() - 1);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void showEditor(int index) {
        if (index < 0 || index >= tabs.size()) return;

        int oldIndex = currentTabIndex;
        boolean wasHidden = viewPager.getVisibility() != View.VISIBLE;

        if (wasHidden) {
            tabNavigationHistory.clear();
            viewPager.setCurrentItem(index, false);

            float width = (float) getResources().getDisplayMetrics().widthPixels;

            viewPager.animate().cancel();
            classListContainer.animate().cancel();

            viewPager.setTranslationX(width);
            viewPager.setVisibility(View.VISIBLE);
            classListContainer.setVisibility(View.VISIBLE);

            viewPager.animate()
                    .translationX(0)
                    .setDuration(280)
                    .setInterpolator(new DecelerateInterpolator(1.1f))
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            classListContainer.setVisibility(View.GONE);
                            classListContainer.setTranslationX(0);
                        }
                    })
                    .start();

            classListContainer.animate()
                    .translationX(-width * 0.2f)
                    .setDuration(280)
                    .setInterpolator(new DecelerateInterpolator(1.1f))
                    .start();

            currentTabIndex = index;
            // Targeted updates instead of notifyDataSetChanged
            tabsAdapter.notifyItemChanged(0);
            tabsAdapter.notifyItemChanged(index + 1);
            updateToolbar();
        } else if (oldIndex != index) {
            tabNavigationHistory.push(oldIndex);
            viewPager.setCurrentItem(index, true);
        }

        fabDelete.hide();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void hideEditor() {
        if (viewPager.getVisibility() != View.VISIBLE || viewPager.getTranslationX() != 0) return;

        int oldIndex = currentTabIndex;
        float width = (float) getResources().getDisplayMetrics().widthPixels;

        viewPager.animate().cancel();
        classListContainer.animate().cancel();

        classListContainer.setVisibility(View.VISIBLE);
        classListContainer.setTranslationX(-width * 0.2f);

        viewPager.animate()
                .translationX(width)
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator(1.1f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        viewPager.setVisibility(View.GONE);
                        viewPager.setTranslationX(0);
                        updateToolbar();

                        // Notify adapter AFTER visibility changes to GONE
                        tabsAdapter.notifyItemChanged(0);
                        if (oldIndex != -1) {
                            tabsAdapter.notifyItemChanged(oldIndex + 1);
                        }
                    }
                })
                .start();

        classListContainer.animate()
                .translationX(0)
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator(1.1f))
                .start();
    }

    public EditorTab getTabForClassName(String className, int type) {
        for (EditorTab tab : tabs) {
            if (tab.type == type && tab.className.equals(className)) return tab;
        }
        return null;
    }

    public void onContentModified(String className) {
        needsModifiedTreeRebuild = true;
        for (int i = 0; i < tabs.size(); i++) {
            EditorTab tab = tabs.get(i);
            if (tab.className.equals(className)) {
                EditorFragment fragment = getFragmentAtIndex(i);
                if (fragment != null && fragment.getEditor() != null) {
                    String currentText = fragment.getEditor().getText().toString();
                    boolean wasModified = tab.isModified;
                    tab.updateDraft(currentText); // Keep current text available if the view is recreated.

                    if (wasModified != tab.isModified) {
                        tabsAdapter.notifyItemChanged(i + 1); // Fixed index: tabs start at position 1
                        invalidateOptionsMenu();
                    }
                }
                break;
            }
        }
    }

    private void saveCurrentTab() {
        final int index = viewPager.getCurrentItem();
        if (index < 0 || index >= tabs.size()) return;
        final EditorTab tab = tabs.get(index);
        saveTab(tab, success -> {
            if (success) {
                SketchwareUtil.showMessage(DexEditorActivity.this, "Saved " + tab.title);
            }
        });
    }

    private void showCompilationOptionsDialog() {
        CompilationOptionsDialog.show(this, sessionOptions);
    }

    public void showSmaliNavigation(String tempSmaliPath, String title, int lineNo) {
        Fragment existing = getSupportFragmentManager().findFragmentByTag("navigation");
        SmaliMethodFieldListFragment navigation;
        if (existing instanceof SmaliMethodFieldListFragment) {
            navigation = (SmaliMethodFieldListFragment) existing;
        } else {
            navigation = new SmaliMethodFieldListFragment();
        }
        if (!navigation.isAdded()) navigation.show(getSupportFragmentManager(), "navigation");
        navigation.updateUi(tempSmaliPath, title, lineNo, dexVersion);
    }

    public void goTo(String text, String currentClassName) {
        if (!text.contains(";->")) {
            String targetClass = SmaliHelper.smali2OnlySlash(text);
            if (targetClass.equals(currentClassName)) {
                SketchwareUtil.showMessage(this, " You are already in this class");
            } else if (classTree.getClassDef(targetClass) != null) {
                openClass(targetClass);
            } else {
                showClassNotfound(targetClass);
            }
        } else {
            String[] split = text.split("->");
            String className = SmaliHelper.smali2OnlySlash(split[0]);
            String methodName = split[1];
            if (className.equals(currentClassName)) {
                EditorFragment fragment = getCurrentFragment();
                if (fragment != null) {
                    fragment.extractMethodFieldInfo(methodName);
                }
            } else if (classTree.getClassDef(className) != null) {
                openClassWithMethod(className, methodName);
            } else {
                showClassNotfound(className);
            }
        }
    }

    public void showClassNotfound(String targetClass) {
        Notify_MT.Notify(this, getString(R.string.error), "Class not found: " + targetClass, "Close");
    }

    private void openClassWithMethod(String className, String methodName) {
        openClass(className);
        EditorTab tab = getTabForClassName(className, 0);
        if (tab == null) return;

        int tabIndex = tabs.indexOf(tab);
        EditorFragment fragment = tabIndex < 0 ? null : getFragmentAtIndex(tabIndex);
        if (fragment != null) fragment.openMethodWhenReady(methodName);
        else tab.pendingMethodName = methodName;
    }

    public void removeTabsForClass(String name, boolean isDirectory) {
        for (int i = tabs.size() - 1; i >= 0; i--) {
            String tabClass = tabs.get(i).className;
            if (isDirectory) {
                if (tabClass.startsWith(name)) {
                    removeTab(i);
                }
            } else {
                if (tabClass.equals(name)) {
                    removeTab(i);
                }
            }
        }
    }

    private boolean isCompilationOptionsActive() {
        return sessionOptions.removeAllDebug || sessionOptions.removeDebugSource ||
                sessionOptions.removeDebugLine || sessionOptions.removeDebugParam ||
                sessionOptions.removeDebugPrologue || sessionOptions.removeDebugLocal ||
                !sessionOptions.dexVersion.equals("Keep the same");
    }


    private void showExitConfirmation() {
        List<EditorTab> modifiedTabs = new ArrayList<>();
        for (EditorTab tab : tabs) {
            if (tab.isModified) modifiedTabs.add(tab);
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("Unsaved changes");

        if (!modifiedTabs.isEmpty()) {
            final String[] titles = new String[modifiedTabs.size()];
            final boolean[] checked = new boolean[modifiedTabs.size()];
            for (int i = 0; i < modifiedTabs.size(); i++) {
                titles[i] = modifiedTabs.get(i).title;
                checked[i] = true;
            }
            builder.setMultiChoiceItems(titles, checked, new DialogInterface.OnMultiChoiceClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                    checked[which] = isChecked;
                }
            });
            builder.setPositiveButton("Save and Exit", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    saveMultipleTabs(modifiedTabs, checked, new Runnable() {
                        @Override
                        public void run() {
                            startSaveAndExit();
                        }
                    });
                }
            });
        } else {
            builder.setMessage("Do you want to compile and save the dex files?");
            builder.setPositiveButton("Save and Exit", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startSaveAndExit();
                }
            });
        }

        builder.setNegativeButton("Cancel", null);
        builder.setNeutralButton("Exit Directly", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                exitActivity();
            }
        });
        builder.show();
    }

    // method for saviing multiple tabs
    // it will save tabs one by one
    // look the next method fo details
    public void saveMultipleTabs(final List<EditorTab> modifiedTabs, final boolean[] checked, final Runnable onProceed) {
        saveNextTab(modifiedTabs, checked, 0, onProceed);
    }

    private void saveNextTab(List<EditorTab> modifiedTabs, boolean[] checked, int startIndex,
                             Runnable onProceed) {
        int index = startIndex;
        while (index < modifiedTabs.size() && !checked[index]) index++;
        if (index == modifiedTabs.size()) {
            if (onProceed != null) onProceed.run();
            return;
        }

        EditorTab tab = modifiedTabs.get(index);
        int nextIndex = index + 1;
        saveTab(tab, success -> {
            if (success) saveNextTab(modifiedTabs, checked, nextIndex, onProceed);
        });
    }

    // main method for saving tabs
    // @tab the tab to be saved, @onSaved used for proper task to detect if the saving completed or not
    private void saveTab(final EditorTab tab, final TabSaveTask.Callback onComplete) {
        new TabSaveTask(this, tab, onComplete).start();
    }

    private void exitActivity() {
        tabs.clear();
        tabNavigationHistory.clear();
        currentTabIndex = -1;
        if (classTree != null) {
            classTree = null;
        }

        // clear all tab data
        treeRoots.clear();
        historyNodes.clear();
        modifiedNodes.clear();
        searchNodes.clear();
        stringWorkspace.clear();
        finish();
    }

    private boolean isSelectionModeActive() {
        Fragment currentFragment = getSupportFragmentManager().findFragmentByTag("f" + (2000 + explorerViewPager.getCurrentItem()));
        if (currentFragment instanceof ExplorerPageFragment) {
            RecyclerView rv = ((ExplorerPageFragment) currentFragment).rv;
            if (rv != null && rv.getAdapter() instanceof TreeAdapter) {
                return ((TreeAdapter) rv.getAdapter()).isSelectionMode();
            }
        }
        return false;
    }

    // cancel the batch selection
    private void cancelSelectionMode() {
        Fragment currentFragment = getSupportFragmentManager().findFragmentByTag("f" + (2000 + explorerViewPager.getCurrentItem()));
        if (currentFragment instanceof ExplorerPageFragment) {
            RecyclerView rv = ((ExplorerPageFragment) currentFragment).rv;
            if (rv != null && rv.getAdapter() instanceof TreeAdapter) {
                TreeAdapter adapter = (TreeAdapter) rv.getAdapter();
                adapter.setSelectionMode(false);
                showMultipleFabs(false);
                fabDelete.hide();
            }
        }
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    // show the progress dialog
    void showProcessingProgress(boolean show) {
        if (show) {
            if (coreProgressDialog == null) {
                coreProgressDialog = new AlertCircularProgress(this);
            }
            coreProgressDialog.setTitle(null);
            coreProgressDialog.setMessage("Loading...");
            coreProgressDialog.show();
        } else if (coreProgressDialog != null) {
            coreProgressDialog.dismiss();
        }
    }

    void showMultipleFabs(boolean show) {
        if (show) {
            fabBackground.setVisibility(View.VISIBLE);
            fabBackground.setTranslationY(getDip(50));
            fabBackground.setAlpha(0.0f);
            fabBackground.animate().setDuration(200L).alpha(1.0f).translationY(0.0f);
        } else {
            fabBackground.setVisibility(View.GONE);
        }
    }

    // multiple fabs for slection of tree nodes
    private void initializeFab() {
        @SuppressLint("InflateParams") View root = getLayoutInflater().inflate(R.layout.multiple_fabs, null);
        LinearLayout fabLayout = root.findViewById(R.id.linear_bg);
        fabBackground = fabLayout;
        FloatingActionButton fabInvertSelect = fabLayout.findViewById(R.id.fab_select_rest);
        FloatingActionButton fabClear = fabLayout.findViewById(R.id.fab_clear);

        ((ViewGroup) fabDelete.getParent()).addView(fabLayout);
        fabClear.setBackgroundTintList(ColorStateList.valueOf(0xFFBEBEC3));
        fabClear.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        fabInvertSelect.setBackgroundTintList(ColorStateList.valueOf(0xFFBEBEC3));
        fabInvertSelect.setImageTintList(ColorStateList.valueOf(0XFFFFFFFF));
        fabInvertSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Fragment currentFragment = getSupportFragmentManager().findFragmentByTag("f" + (2000 + explorerViewPager.getCurrentItem()));
                if (currentFragment instanceof ExplorerPageFragment) {
                    RecyclerView rv = ((ExplorerPageFragment) currentFragment).rv;
                    if (rv != null && rv.getAdapter() instanceof TreeAdapter) {
                        ((TreeAdapter) rv.getAdapter()).invertSelection();
                    }
                }
            }
        });
        fabClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Fragment currentFragment = getSupportFragmentManager().findFragmentByTag("f" + (2000 + explorerViewPager.getCurrentItem()));
                if (currentFragment instanceof ExplorerPageFragment) {
                    RecyclerView rv = ((ExplorerPageFragment) currentFragment).rv;
                    if (rv != null && rv.getAdapter() instanceof TreeAdapter) {
                        ((TreeAdapter) rv.getAdapter()).clearAllSelection();
                        showMultipleFabs(false);
                        fabDelete.hide();
                        ((TreeAdapter) rv.getAdapter()).setSelectionMode(false);
                    }
                }
            }
        });
        showMultipleFabs(false);
    }


    public float getDip(int dip) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip, getResources().getDisplayMetrics());
    }

    void showErrorDialog(String errorMessage) {
        Notify_MT.Notify(this, getResources().getString(R.string.error), errorMessage, getResources().getString(R.string.close));
    }

    private void closeTab(int index) {
        closeTabWithPrompt(index);
    }


    private void removeTab(int index) {
        tabs.remove(index);
        tabAdapter.notifyItemRemoved(index);
        tabsAdapter.notifyItemRemoved(index + 1);
        if (tabs.isEmpty()) {
            hideEditor();
        } else {
            int nextIndex = Math.max(0, index - 1);
            viewPager.setCurrentItem(nextIndex, true);
        }
    }

    private void removeTab(EditorTab tab) {
        int index = tabs.indexOf(tab);
        if (index != -1) {
            removeTab(index);
        }
    }

    private void setupDrawerToolbar() {
        drawerToolbar.getMenu().add(0, 0, 0, "Close other");
        drawerToolbar.getMenu().add(0, 1, 0, "Close all");
        drawerToolbar.getMenu().add(0, 2, 0, "Close unmodified");
        drawerToolbar.getMenu().add(0, 3, 0, "Close above");
        drawerToolbar.getMenu().add(0, 4, 0, "Close below");

        drawerToolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int index = currentTabIndex;
                switch (item.getItemId()) {
                    case 0: // Close other
                        if (index != -1) tabsAdapter.closeOtherTabs(index);
                        break;
                    case 1: // Close all
                        tabsAdapter.closeAllTabs();
                        break;
                    case 2: // Close unmodified
                        tabsAdapter.closeUnmodifiedTabs();
                        break;
                    case 3: // Close above
                        if (index != -1) tabsAdapter.closeTabsAbove(index);
                        break;
                    case 4: // Close below
                        if (index != -1) tabsAdapter.closeTabsBelow(index);
                        break;
                }
                return true;
            }
        });
    }

    private void updateDrawerMenuState() {
        Menu menu = drawerToolbar.getMenu();
        int index = currentTabIndex;
        int count = tabs.size();

        menu.findItem(0).setEnabled(index != -1 && count > 1);
        menu.findItem(1).setEnabled(count > 0);
        menu.findItem(2).setEnabled(count > 0);

        MenuItem closeAbove = menu.findItem(3);
        closeAbove.setEnabled(index > 0);
        UIHelper.setMenuItemColor(closeAbove, closeAbove.isEnabled() ? Color.BLACK : Color.GRAY);

        MenuItem closeBelow = menu.findItem(4);
        closeBelow.setEnabled(index != -1 && index < count - 1);
        UIHelper.setMenuItemColor(closeBelow, closeBelow.isEnabled() ? Color.BLACK : Color.GRAY);

        // Also update other items color if needed
        UIHelper.setMenuItemColor(menu.findItem(0), menu.findItem(0).isEnabled() ? Color.BLACK : Color.GRAY);
        UIHelper.setMenuItemColor(menu.findItem(1), menu.findItem(1).isEnabled() ? Color.BLACK : Color.GRAY);
        UIHelper.setMenuItemColor(menu.findItem(2), menu.findItem(2).isEnabled() ? Color.BLACK : Color.GRAY);
    }

    @Override
    public boolean isEditorTabSelected(int tabIndex) {
        return currentTabIndex == tabIndex;
    }

    @Override
    public void onHomeSelected() {
        hideEditor();
    }

    @Override
    public void onEditorTabSelected(int tabIndex) {
        showEditor(tabIndex);
    }

    @Override
    public void onCloseEditorTab(int tabIndex) {
        closeTab(tabIndex);
    }

    @Override
    public void onLocateEditorTab(String className) {
        locateClass(className);
    }

    @Override
    public void onTabsActionFinished() {
        updateDrawerMenuState();
    }

    private static class ExplorerTabAdapter extends FragmentStateAdapter {
        public ExplorerTabAdapter(@NonNull AppCompatActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            if (position == 2) return new SearchFragment();
            return ExplorerPageFragment.newInstance(position);
        }

        @Override
        public int getItemCount() {
            return 4;
        }

        @Override
        public long getItemId(int position) {
            return 2000 + position;
        }

        @Override
        public boolean containsItem(long itemId) {
            return itemId >= 2000 && itemId <= 2003;
        }
    }

    public Thread executeBackgroundTask(String name, Runnable work) {
        return backgroundTasks.execute(name, work);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        Fragment navigation = getSupportFragmentManager().findFragmentByTag("navigation");
        if (navigation instanceof SmaliMethodFieldListFragment) {
            ((SmaliMethodFieldListFragment) navigation).saveStateForHost();
        }
        outState.putParcelable("navigation.methods", navigationMethodsState);
        outState.putParcelable("navigation.strings", navigationStringsState);
        outState.putBoolean("navigation.showingStrings", navigationShowingStrings);
        super.onSaveInstanceState(outState);
    }

    private void startSaveAndExit() {
        if (saveAndExitTask != null) saveAndExitTask.cancel();
        saveAndExitTask = new DexSaveAndExitTask(this, sessionOptions);
        saveAndExitTask.onClick(null, 0);
    }

    void onSaveTaskFinished(DexSaveAndExitTask task) {
        if (saveAndExitTask == task) saveAndExitTask = null;
    }

    // batch class deletion function
    private class DeleteButtonClickListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            Fragment currentFragment = getSupportFragmentManager().findFragmentByTag(
                    "f" + (2000 + explorerViewPager.getCurrentItem()));
            if (!(currentFragment instanceof ExplorerPageFragment)) return;
            RecyclerView recyclerView = ((ExplorerPageFragment) currentFragment).rv;
            if (recyclerView == null || !(recyclerView.getAdapter() instanceof TreeAdapter)) return;
            final TreeAdapter adapter = (TreeAdapter) recyclerView.getAdapter();
            final List<TreeNode> selected = new ArrayList<>(adapter.getSelectedNodes());
            if (selected.isEmpty()) return;
            final ClassTree targetTree = classTree;
            final AlertProgress deleteProgress = new AlertProgress(DexEditorActivity.this);
            deleteProgress.setCancelable(false);
            deleteProgress.setCanceledOnTouchOutside(false);
            deleteProgress.setMessage("Deleting classes...");
            deleteProgress.show();

            executeBackgroundTask("dex-editor-delete-classes", () -> {
                try {
                    List<String> namesToDelete = new ArrayList<>();
                    EditorPositionManager posManager = EditorPositionManager.getInstance(DexEditorActivity.this);
                    for (TreeNode node : selected) {
                        if (Thread.currentThread().isInterrupted()) return;
                        String fullName = node.getFullName();
                        namesToDelete.add(fullName + (node.isDirectory() ? "/" : ""));
                    }
                    if (Thread.currentThread().isInterrupted()) return;
                    targetTree.removeClasses(namesToDelete);
                    for (TreeNode node : selected) posManager.removePosition(node.getFullName());
                    runOnUiThreadIfAlive(() -> {
                        for (TreeNode node : selected) removeTabsForClass(node.getFullName(), node.isDirectory());
                        adapter.removeSelectedNodes();
                        adapter.setSelectionMode(false);
                        showMultipleFabs(false);
                        fabDelete.hide();
                        needsModifiedTreeRebuild = true;
                        refreshExplorerPage(1);
                    });
                } catch (Exception e) {
                    final String message = e.toString();
                    runOnUiThreadIfAlive(() -> showErrorDialog(message));
                } finally {
                    runOnUiThreadIfAlive(() -> {
                        if (deleteProgress.isShowing()) deleteProgress.dismiss();
                    });
                }
            });
        }
    }

    private class TabAdapter extends FragmentStateAdapter {
        public TabAdapter(@NonNull AppCompatActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            EditorTab tab = tabs.get(position);
            if (tab.type == 2) {
                return modder.hub.dexeditor.fragment.GraphFragment.newInstance(tab.className, tab.title, tab.subtitle, tab.content);
            }
            return EditorFragment.newInstance(tab.className, tab.title, tab.content, tab.type);
        }

        @Override
        public int getItemCount() {
            return tabs.size();
        }

        @Override
        public long getItemId(int position) {
            return tabs.get(position).id;
        }

        @Override
        public boolean containsItem(long itemId) {
            for (EditorTab tab : tabs) {
                if (tab.id == itemId) return true;
            }
            return false;
        }
    }

}
