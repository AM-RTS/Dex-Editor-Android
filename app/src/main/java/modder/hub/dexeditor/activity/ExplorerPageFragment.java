package modder.hub.dexeditor.activity;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;
import java.util.Objects;

import modder.hub.dexeditor.R;
import modder.hub.dexeditor.adapter.HeaderAdapter;
import modder.hub.dexeditor.adapter.StringAdapter;
import modder.hub.dexeditor.adapter.TreeAdapter;
import modder.hub.dexeditor.fragment.SearchFragment;
import modder.hub.dexeditor.model.TreeNode;
import modder.hub.dexeditor.views.FastScrollerRecyclerView;

public class ExplorerPageFragment extends Fragment {
    FastScrollerRecyclerView rv;
    private int position;
    private RecyclerView.Adapter<?> currentAdapter;

    public static ExplorerPageFragment newInstance(int position) {
        ExplorerPageFragment fragment = new ExplorerPageFragment();
        Bundle args = new Bundle();
        args.putInt("position", position);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) position = getArguments().getInt("position");
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rv = new FastScrollerRecyclerView(requireContext());
        rv.setTrackVisible(false);
        rv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setBackgroundColor(Color.WHITE);

        if (position == 0) {
            rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    updateSubtitle();
                }
            });
        }

        updateUI();
        return rv;
    }

    // update the subtitle according the folder opened in the treeview
    private void updateSubtitle() {
        if (rv == null || !(rv.getLayoutManager() instanceof LinearLayoutManager) || !(rv.getAdapter() instanceof TreeAdapter))
            return;
        DexEditorActivity activity = (DexEditorActivity) getActivity();
        if (activity == null) return;

        LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
        int firstPos = lm.findFirstVisibleItemPosition();
        if (firstPos == RecyclerView.NO_POSITION) return;

        TreeAdapter adapter = (TreeAdapter) rv.getAdapter();
        List<TreeNode> nodes = adapter.getVisibleNodes();
        if (firstPos >= nodes.size()) return;

        TreeNode node = nodes.get(firstPos);
        View v = lm.findViewByPosition(firstPos);

        if (v != null) {
            // MT Manager style: Show the path of the current expanded folder level
            TreeNode parent = node.getParent();
            if (parent != null && parent.isExpanded()) {
                activity.setToolbarSubtitle(parent.getFullName());
            } else if (node.isDirectory() && node.isExpanded() && v.getTop() < 0) {
                activity.setToolbarSubtitle(node.getFullName().replace("/", "."));
            } else {
                activity.setToolbarSubtitle(null);
            }
        }
    }

    // class node loacter in the main treeview
    public void locateNode(String className) {
        if (rv == null || rv.getAdapter() == null) return;
        TreeAdapter adapter = (TreeAdapter) rv.getAdapter();
        TreeNode target = findNodeRecursive(adapter.getRootNodes(), className);
        if (target != null) {
            for (TreeNode node = target.getParent(); node != null; node = node.getParent()) {
                node.setExpanded(true);
            }
            adapter.refreshVisibleNodes();
            int pos = adapter.getPosition(target);
            if (pos != -1) {
                rv.scrollToPosition(pos);
                adapter.setHighlightedFullName(className);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        adapter.setHighlightedFullName(null);
                    }
                }, 2000);
            }
        }
    }

    private TreeNode findNodeRecursive(List<TreeNode> nodes, String fullName) {
        for (TreeNode node : nodes) {
            if (node.getFullName().equals(fullName)) return node;
            TreeNode found = findNodeRecursive(node.getChildren(), fullName);
            if (found != null) return found;
        }
        return null;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateUI() {
        if (rv == null) return;
        DexEditorActivity activity = (DexEditorActivity) getActivity();
        if (activity == null) return;

        // Reuse existing adapters for all tabs to avoid frame drops
        if (currentAdapter != null && position != 1) {
            if (currentAdapter instanceof TreeAdapter) {
                ((TreeAdapter) currentAdapter).refreshVisibleNodes();
            } else if (currentAdapter instanceof StringAdapter) {
                currentAdapter.notifyDataSetChanged();
            } else if (currentAdapter instanceof ConcatAdapter) {
                // For History/Modified tab, we need to be careful.
                // If we use ConcatAdapter, we might need to update sub-adapters.
                // But for now, let's just fall through if position is 1.

                // Get string adapter from ConcatAdapter of strings tab
                List<? extends RecyclerView.Adapter<?>> adapters = ((ConcatAdapter) currentAdapter).getAdapters();
                if(adapters.size() > 1) {
                    RecyclerView.Adapter<?> adapter = adapters.get(1);
                    if (adapter instanceof StringAdapter) adapter.notifyDataSetChanged();
                }
            } else {
                currentAdapter.notifyDataSetChanged();
            }
            if (position != 1) return;
        }

        switch (position) {
            case 0:
                currentAdapter = new TreeAdapter(getContext(), activity.treeRoots, new TreeAdapter.OnNodeClickListener() {
                    @Override
                    public void onNodeClick(TreeNode node) {
                        activity.openClass(node.getFullName());
                    }

                    @SuppressLint("NotifyDataSetChanged")
                    @Override
                    public boolean onNodeDeleted(TreeNode node) {
                        try {
                            activity.getClassTree().removeClass(
                                    node.getFullName() + (node.isDirectory() ? "/" : ""));
                        } catch (java.io.IOException e) {
                            activity.showErrorDialog("Unable to save deleted-class journal: " + e.getMessage());
                            return false;
                        }
                        activity.clearPositionSaving(node.getFullName());
                        activity.removeTabsForClass(node.getFullName(), node.isDirectory());
                        activity.needsModifiedTreeRebuild = true;
                        activity.needsExplorerRefresh = true;
                        activity.refreshExplorerPage(1);
                        activity.tabsAdapter.notifyDataSetChanged();
                        return true;
                    }

                    @Override
                    public void onSelectionChanged(int count) {
                        activity.showMultipleFabs(count > 0);
                        if (count > 0) activity.fabDelete.show();
                        else activity.fabDelete.hide();
                    }

                    @Override
                    public void onSearch(TreeNode node) {
                        activity.pendingSearchPath = node.getFullName() + "/";
                        activity.explorerViewPager.setCurrentItem(2);
                    }
                }, false);
                break;
            case 1:
                // For History/Modified, we use ConcatAdapter which is slightly harder to reuse,
                // but we can at least avoid rebuilding if not needed.
                ConcatAdapter concatAdapter = new ConcatAdapter();
                boolean hasRecently = !activity.historyNodes.isEmpty();
                boolean hasModified = !activity.modifiedNodes.isEmpty();

                if (hasRecently) {
                    concatAdapter.addAdapter(new HeaderAdapter("Recently", new HeaderAdapter.OnMenuClickListener() {
                        @Override
                        public void onMenuClick(View view) {
                            PopupMenu popup = new PopupMenu(requireContext(), view);
                            popup.getMenu().add("Clear all");
                            popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                                @Override
                                public boolean onMenuItemClick(MenuItem item) {
                                    if ("Clear all".contentEquals(Objects.requireNonNull(item.getTitle()))) {
                                        activity.historyNodes.clear();
                                        activity.refreshExplorerPage(1);
                                        return true;
                                    }
                                    return false;
                                }
                            });
                            popup.show();
                        }
                    }));
                    concatAdapter.addAdapter(new TreeAdapter(getContext(), activity.historyNodes, new TreeAdapter.OnNodeClickListener() {
                        @Override
                        public void onNodeClick(TreeNode node) {
                            activity.openClass(node.getFullName());
                        }

                        @Override
                        public boolean onNodeDeleted(TreeNode node) {
                            activity.historyNodes.remove(node);
                            activity.refreshExplorerPage(1);
                            return true;
                        }

                        @Override
                        public void onSelectionChanged(int count) {
                        }

                        @Override
                        public void onLocate(TreeNode node) {
                            activity.locateClass(node.getFullName());
                        }

                        @Override
                        public void onSearch(TreeNode node) {
                            activity.pendingSearchPath = node.getFullName() + "/";
                            activity.explorerViewPager.setCurrentItem(2);
                        }
                    }, true));
                }

                if (hasModified) {
                    concatAdapter.addAdapter(new HeaderAdapter("Modified"));
                    concatAdapter.addAdapter(new TreeAdapter(requireContext(), activity.modifiedNodes, new TreeAdapter.OnNodeClickListener() {
                        @Override
                        public void onNodeClick(TreeNode node) {
                            activity.openClass(node.getFullName());
                        }

                        @Override
                        public boolean onNodeDeleted(TreeNode node) { return false; /* Modified nodes are from ClassTree */ }

                        @Override
                        public void onSelectionChanged(int count) {
                        }

                        @Override
                        public void onLocate(TreeNode node) {
                            activity.locateClass(node.getFullName());
                        }

                        @Override
                        public void onSearch(TreeNode node) {
                            activity.pendingSearchPath = node.getFullName() + "/";
                            activity.explorerViewPager.setCurrentItem(2);
                        }

                        @Override
                        public void onCompare(TreeNode node) {
                            // TODO: Implement compare the difference
                        }
                    }, false, true));
                }
                currentAdapter = concatAdapter;
                break;
            case 2:
                currentAdapter = new TreeAdapter(getContext(), activity.searchNodes, new TreeAdapter.OnNodeClickListener() {
                    @Override
                    public void onNodeClick(TreeNode node) {
                        activity.openClass(node.getFullName());
                    }

                    @Override
                    public boolean onNodeDeleted(TreeNode node) {
                        activity.searchNodes.remove(node);
                        return true;
                    }

                    @Override
                    public void onSelectionChanged(int count) {
                    }

                    @Override
                    public void onLocate(TreeNode node) {
                        activity.locateClass(node.getFullName());
                    }
                }, true);
                break;
            case 3:
                View header = LayoutInflater.from(getContext()).inflate(R.layout.strings_header, rv, false);

                // holder trick so the click listener can reference the adapter it belongs to
                final StringAdapter[] holder = new StringAdapter[1];
                StringWorkspaceController strings = activity.getStringWorkspace();
                StringAdapter stringAdapter = new StringAdapter(strings.getStrings(),
                        text -> strings.showEditDialog(holder[0], header.<TextView>findViewById(R.id.btn_strings_apply), text));
                holder[0] = stringAdapter;
                currentAdapter = new ConcatAdapter(new SearchFragment.HeaderViewAdapter(header), stringAdapter);

                /*header.findViewById(R.id.btn_strings_reload).setOnClickListener(v -> {
                    stringAdapter.clearModifications();
                    stringAdapter.setFilter(null);
                    btnApply.setVisibility(View.GONE);
                    strings.load();
                });*/ // It doesnt look like reload working maybe whole loadStrings implementation would need to change to get it to work
                header.findViewById(R.id.btn_strings_filter).setOnClickListener(v -> strings.showFilterDialog(stringAdapter));
                header.findViewById(R.id.btn_strings_replace).setOnClickListener(v -> strings.showReplaceAllDialog());
                header.findViewById(R.id.btn_strings_apply).setOnClickListener(v -> strings.applyChanges(stringAdapter, header.<TextView>findViewById(R.id.btn_strings_apply)));
                break;
        }
        if (currentAdapter != null) {
            rv.setAdapter(currentAdapter);
        }
    }
}

