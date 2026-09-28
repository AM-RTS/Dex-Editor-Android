package modder.hub.dexeditor.activity;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.List;

import modder.hub.dexeditor.R;
import modder.hub.dexeditor.model.EditorTab;

/** Renders the home row and open editor tabs in the navigation drawer. */
public final class EditorTabsAdapter extends RecyclerView.Adapter<EditorTabsAdapter.ViewHolder> {
    public interface Listener {
        boolean isEditorTabSelected(int tabIndex);
        void onHomeSelected();
        void onEditorTabSelected(int tabIndex);
        void onCloseEditorTab(int tabIndex);
        void onLocateEditorTab(String className);
        void onTabsActionFinished();
    }

    private final List<EditorTab> tabs;
    private final ViewPager2 viewPager;
    private final DrawerLayout drawerLayout;
    private final Listener listener;
    private int swipedPosition = -1;

    public EditorTabsAdapter(List<EditorTab> tabs, ViewPager2 viewPager,
                             DrawerLayout drawerLayout, Listener listener) {
        this.tabs = tabs;
        this.viewPager = viewPager;
        this.drawerLayout = drawerLayout;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.tab_item, parent, false);
        return new ViewHolder(view);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, @SuppressLint("RecyclerView") int position) {
        boolean isSelected;
        int selectedColor = Color.parseColor("#00B0FF");
        float swipeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 120,
                holder.itemView.getResources().getDisplayMetrics());

        holder.mainView.animate().cancel();
        holder.mainView.setTranslationX(position == swipedPosition ? swipeWidth : 0);

        if (position == 0) {
            isSelected = viewPager.getVisibility() != View.VISIBLE;
            holder.title.setText("Dex Editor Plus");
            holder.title.setTextColor(isSelected ? selectedColor : Color.BLACK);
            holder.path.setVisibility(View.GONE);
            holder.icon.setImageResource(R.drawable.ic_home);
            holder.icon.setImageTintList(ColorStateList.valueOf(isSelected ? selectedColor : Color.BLACK));
            holder.mainView.setOnClickListener(v -> {
                drawerLayout.closeDrawer(GravityCompat.START);
                v.postDelayed(listener::onHomeSelected, 250);
            });
            holder.menuView.setVisibility(View.GONE);
            holder.mainView.setOnTouchListener(null);
        } else {
            int tabIndex = position - 1;
            EditorTab tab = tabs.get(tabIndex);
            isSelected = viewPager.getVisibility() == View.VISIBLE
                    && listener.isEditorTabSelected(tabIndex);
            holder.title.setText((tab.isModified ? "*" : "") + tab.title);
            holder.title.setTextColor(isSelected ? selectedColor : Color.BLACK);
            holder.path.setVisibility(View.VISIBLE);
            holder.path.setText(tab.className);
            if (tab.type == 1) {
                holder.icon.setImageResource(R.drawable.ic_java_mt);
            } else if (tab.type == 2) {
                holder.icon.setImageResource(R.drawable.ic_flow_diagram);
            } else {
                holder.icon.setImageResource(R.drawable.ic_edit_mt);
            }
            holder.icon.setImageTintList(ColorStateList.valueOf(isSelected ? selectedColor : Color.BLACK));

            holder.mainView.setOnClickListener(v -> {
                int currentPosition = holder.getBindingAdapterPosition();
                if (currentPosition == RecyclerView.NO_POSITION) return;
                if (v.getTranslationX() != 0) {
                    v.animate().translationX(0).setDuration(150).start();
                    swipedPosition = -1;
                    return;
                }
                drawerLayout.closeDrawer(GravityCompat.START);
                int targetTabIndex = currentPosition - 1;
                v.postDelayed(() -> listener.onEditorTabSelected(targetTabIndex), 200);
            });

            holder.mainView.setOnTouchListener(new View.OnTouchListener() {
                private final int touchSlop = android.view.ViewConfiguration
                        .get(holder.itemView.getContext()).getScaledTouchSlop();
                private float startX;
                private float initialX;
                private boolean isDragging;

                @SuppressLint("ClickableViewAccessibility")
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialX = event.getRawX();
                            startX = v.getTranslationX();
                            isDragging = false;
                            break;
                        case MotionEvent.ACTION_MOVE:
                            float diff = event.getRawX() - initialX;
                            if (Math.abs(diff) > touchSlop || isDragging) {
                                isDragging = true;
                                v.setTranslationX(Math.max(0, Math.min(startX + diff, swipeWidth)));
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                                return true;
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (isDragging) {
                                float target = v.getTranslationX() > swipeWidth / 3 ? swipeWidth : 0;
                                v.animate().translationX(target).setDuration(150).start();
                                int oldSwiped = swipedPosition;
                                swipedPosition = target > 0 ? holder.getBindingAdapterPosition() : -1;
                                if (oldSwiped != -1 && oldSwiped != swipedPosition) {
                                    notifyItemChanged(oldSwiped);
                                }
                                return true;
                            }
                            break;
                    }
                    return false;
                }
            });

            holder.menuView.setVisibility(View.VISIBLE);
            holder.menuClose.setOnClickListener(v -> {
                int currentPosition = holder.getBindingAdapterPosition();
                if (currentPosition != RecyclerView.NO_POSITION) {
                    swipedPosition = -1;
                    holder.mainView.setTranslationX(0);
                    listener.onCloseEditorTab(currentPosition - 1);
                }
            });
            holder.menuLocate.setOnClickListener(v -> {
                int currentPosition = holder.getBindingAdapterPosition();
                if (currentPosition != RecyclerView.NO_POSITION) {
                    swipedPosition = -1;
                    holder.mainView.animate().translationX(0).setDuration(200).start();
                    listener.onLocateEditorTab(tabs.get(currentPosition - 1).className);
                    drawerLayout.closeDrawers();
                }
            });
        }
        holder.mainView.setBackgroundColor(isSelected ? Color.parseColor("#E1F5FE") : Color.WHITE);
    }

    public void closeOtherTabs(int index) {
        for (int i = tabs.size() - 1; i >= 0; i--) {
            if (i != index) listener.onCloseEditorTab(i);
        }
        listener.onTabsActionFinished();
    }

    public void closeAllTabs() {
        for (int i = tabs.size() - 1; i >= 0; i--) listener.onCloseEditorTab(i);
        listener.onTabsActionFinished();
    }

    public void closeUnmodifiedTabs() {
        for (int i = tabs.size() - 1; i >= 0; i--) {
            if (!tabs.get(i).isModified) listener.onCloseEditorTab(i);
        }
        listener.onTabsActionFinished();
    }

    public void closeTabsAbove(int index) {
        for (int i = index - 1; i >= 0; i--) listener.onCloseEditorTab(i);
        listener.onTabsActionFinished();
    }

    public void closeTabsBelow(int index) {
        for (int i = tabs.size() - 1; i > index; i--) listener.onCloseEditorTab(i);
        listener.onTabsActionFinished();
    }

    @Override
    public int getItemCount() {
        return tabs.size() + 1;
    }

    public static final class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView path;
        final ImageView icon;
        final View mainView;
        final View menuView;
        final ImageView menuClose;
        final ImageView menuLocate;

        ViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tab_title);
            path = itemView.findViewById(R.id.tab_path);
            icon = itemView.findViewById(R.id.tab_icon);
            mainView = itemView.findViewById(R.id.main_view);
            menuView = itemView.findViewById(R.id.menu_view);
            menuClose = itemView.findViewById(R.id.menu_close);
            menuLocate = itemView.findViewById(R.id.menu_locate);
        }
    }
}
