package modder.hub.dexeditor.utils;

import android.system.ErrnoException;
import android.system.Os;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import modder.hub.dexeditor.model.EditorTab;

/** Persists open editor drafts between Android process recreation for the same source DEX set. */
public final class EditorSessionRecovery {
    private static final Gson GSON = new Gson();

    private EditorSessionRecovery() {}

    public static File fileIn(File sessionDirectory) {
        return new File(sessionDirectory, "editor-session.json");
    }

    public static void save(File file, ClassTree tree, List<EditorTab> tabs, int selectedTab,
                            boolean editorVisible, List<Integer> navigationHistory) throws IOException {
        Session session = new Session();
        session.version = 1;
        session.fingerprints = tree.getRecoveryFingerprints();
        session.tabs = new ArrayList<>();
        for (EditorTab tab : tabs) session.tabs.add(new TabState(tab));
        session.selectedTab = selectedTab;
        session.editorVisible = editorVisible;
        session.navigationHistory = new ArrayList<>(navigationHistory);

        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create the editor recovery directory.");
        }
        File temp = new File(file.getAbsolutePath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp);
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            GSON.toJson(session, writer);
            writer.flush();
            output.getFD().sync();
        } catch (IOException e) {
            temp.delete();
            throw e;
        }
        try {
            Os.rename(temp.getAbsolutePath(), file.getAbsolutePath());
        } catch (ErrnoException e) {
            temp.delete();
            throw new IOException("Could not atomically save editor recovery data.", e);
        }
    }

    public static Session restore(File file, ClassTree tree) throws IOException {
        if (!file.exists()) return null;
        final Session session;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            session = GSON.fromJson(reader, Session.class);
        } catch (JsonParseException e) {
            throw new IOException("Editor recovery data is invalid.", e);
        }
        if (session == null || session.version != 1 || session.fingerprints == null || session.tabs == null) {
            throw new IOException("Editor recovery data is incomplete.");
        }
        if (!session.fingerprints.equals(tree.getRecoveryFingerprints())) {
            File stale = new File(file.getAbsolutePath() + ".stale." + System.currentTimeMillis());
            if (!file.renameTo(stale)) throw new IOException("Could not preserve editor recovery for a different DEX version.");
            return null;
        }
        return session;
    }

    public static final class Session {
        private int version;
        private Map<String, String> fingerprints;
        private List<TabState> tabs;
        private int selectedTab;
        private boolean editorVisible;
        private List<Integer> navigationHistory;

        public List<EditorTab> createTabs(ClassTree tree) {
            List<EditorTab> restored = new ArrayList<>();
            for (TabState state : tabs) {
                if (state == null || state.className == null || state.title == null
                        || state.type < 0 || state.type > 2) continue;
                if (state.type == 0 && !tree.containsClass("L" + state.className + ";")) continue;
                EditorTab tab = new EditorTab(state.id, state.className, state.title, state.subtitle,
                        state.content, state.type);
                tab.originalContent = state.originalContent == null ? state.content : state.originalContent;
                tab.isModified = state.modified;
                tab.isReadOnly = state.readOnly;
                tab.pendingLine = state.pendingLine;
                tab.pendingColumn = state.pendingColumn;
                tab.pendingQuery = state.pendingQuery;
                tab.pendingMethodName = state.pendingMethodName;
                restored.add(tab);
            }
            return restored;
        }

        public int getSelectedTab() { return selectedTab; }
        public boolean isEditorVisible() { return editorVisible; }
        public List<Integer> getNavigationHistory() {
            return navigationHistory == null ? new ArrayList<Integer>() : navigationHistory;
        }
    }

    private static final class TabState {
        long id;
        String className;
        String title;
        String subtitle;
        String content;
        String originalContent;
        int type;
        boolean modified;
        boolean readOnly;
        int pendingLine;
        int pendingColumn;
        String pendingQuery;
        String pendingMethodName;

        TabState(EditorTab tab) {
            id = tab.id;
            className = tab.className;
            title = tab.title;
            subtitle = tab.subtitle;
            content = tab.content;
            originalContent = tab.originalContent;
            type = tab.type;
            modified = tab.isModified;
            readOnly = tab.isReadOnly;
            pendingLine = tab.pendingLine;
            pendingColumn = tab.pendingColumn;
            pendingQuery = tab.pendingQuery;
            pendingMethodName = tab.pendingMethodName;
        }
    }
}
