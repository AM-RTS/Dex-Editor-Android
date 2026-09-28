package modder.hub.dexeditor.model;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Mutable session state for one open editor tab. */
public final class EditorTab {
    private static long nextId = 1;

    public final long id = nextId++;
    public String className;
    public String title;
    public String subtitle;
    public String content;
    public String originalContent;
    public int type; // 0: Smali, 1: Java, 2: Graph
    public boolean isModified;
    private final AtomicLong contentRevision = new AtomicLong();
    public boolean isReadOnly;
    public int pendingLine = -1;
    public int pendingColumn = -1;
    public String pendingQuery;
    public String pendingMethodName;

    public EditorTab(String className, String title, String subtitle, String content, int type) {
        this.className = className;
        this.title = title;
        this.subtitle = subtitle;
        this.content = content;
        this.originalContent = content;
        this.type = type;
    }

    public void updateDraft(String content) {
        if (!Objects.equals(this.content, content)) contentRevision.incrementAndGet();
        this.content = content;
        this.isModified = !Objects.equals(content, originalContent);
    }

    public void markCommitted(String content) {
        if (!Objects.equals(this.content, content)) contentRevision.incrementAndGet();
        this.content = content;
        this.originalContent = content;
        this.isModified = false;
    }

    public long getContentRevision() {
        return contentRevision.get();
    }
}
