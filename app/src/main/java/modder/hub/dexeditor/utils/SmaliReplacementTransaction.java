package modder.hub.dexeditor.utils;

import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.tools.smali.smali2.Smali;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies a GUI replacement to a snapshot and commits only if every changed class assembles. */
public final class SmaliReplacementTransaction {
    private static final int MAX_WORKERS = 4;
    private static final Pattern ERROR_POSITION = Pattern.compile("\\[(\\d+),(\\d+)]");

    private SmaliReplacementTransaction() {}

    public interface ProgressListener {
        void onProgress(int processed, int total);
    }

    public static Result applyAndCommit(ClassTree tree, long revision,
                                        Map<String, ClassDef> classSnapshot,
                                        Map<String, String> openEditorText,
                                        Collection<String> scope,
                                        SmaliTextReplacer.Rule rule,
                                        BooleanSupplier cancelled,
                                        ProgressListener progress) throws InterruptedException {
        LinkedHashSet<String> uniqueClasses = new LinkedHashSet<>(
                scope == null ? classSnapshot.keySet() : scope);
        List<String> classes = new ArrayList<>(uniqueClasses);
        Map<String, ClassDef> replacements = new ConcurrentHashMap<>();
        Map<String, String> replacedOpenText = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        AtomicReference<Failure> firstFailure = new AtomicReference<>();
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger replacedCount = new AtomicInteger();
        AtomicInteger affectedClasses = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(1, Math.min(MAX_WORKERS, Runtime.getRuntime().availableProcessors())));

        try {
            for (String className : classes) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) break;
                executor.execute(() -> {
                    try {
                        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
                        ClassDef originalClass = classSnapshot.get(className);
                        if (originalClass == null) return;
                        String originalText = openEditorText.get(className);
                        if (originalText == null) originalText = tree.getSmaliByType(originalClass);
                        SmaliTextReplacer.Result replaced = rule.apply(originalText);
                        if (replaced.getMatches() == 0) return;

                        ClassDef replacement = Smali.assemble(replaced.getText(), new SmaliOptions(),
                                tree.getDexVersionForClass(className));
                        if (!("L" + className + ";").equals(replacement.getType())) {
                            throw new IllegalArgumentException("Edited class descriptor does not match " + className);
                        }
                        replacements.put(className, replacement);
                        if (openEditorText.containsKey(className)) {
                            replacedOpenText.put(className, replaced.getText());
                        }
                        replacedCount.addAndGet(replaced.getMatches());
                        affectedClasses.incrementAndGet();
                    } catch (Exception e) {
                        String message = e.getMessage() == null ? e.toString() : e.getMessage();
                        errors.add(className + ": " + message);
                        Matcher matcher = ERROR_POSITION.matcher(message);
                        int line = -1, column = -1;
                        if (matcher.find()) {
                            try {
                                line = Integer.parseInt(matcher.group(1)) - 1;
                                column = Integer.parseInt(matcher.group(2)) - 1;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        firstFailure.compareAndSet(null, new Failure(className, line, column));
                    } finally {
                        int done = processed.incrementAndGet();
                        if (progress != null) progress.onProgress(done, classes.size());
                    }
                });
            }
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                executor.shutdownNow();
                throw new IllegalStateException("Timed out replacing Smali classes.");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw e;
        }

        Failure first = firstFailure.get();
        String transactionError = null;
        boolean committed = false;
        if (!cancelled.getAsBoolean() && errors.isEmpty()) {
            try {
                tree.commitClassDefs(revision, new ArrayList<>(replacements.values()));
                committed = true;
            } catch (RuntimeException e) {
                transactionError = e.getMessage();
            }
        }
        return new Result(!committed && transactionError == null && errors.isEmpty()
                        && cancelled.getAsBoolean(), replacedCount.get(), affectedClasses.get(),
                new ArrayList<>(replacements.values()), replacedOpenText, new ArrayList<>(errors),
                transactionError, first == null ? null : first.className,
                first == null ? -1 : first.line, first == null ? -1 : first.column);
    }

    private static final class Failure {
        final String className;
        final int line;
        final int column;

        Failure(String className, int line, int column) {
            this.className = className;
            this.line = line;
            this.column = column;
        }
    }

    public static final class Result {
        private final boolean cancelled;
        private final int replacedCount;
        private final int affectedClasses;
        private final List<ClassDef> replacements;
        private final Map<String, String> replacedOpenText;
        private final List<String> errors;
        private final String transactionError;
        private final String firstErrorClass;
        private final int firstErrorLine;
        private final int firstErrorColumn;

        private Result(boolean cancelled, int replacedCount, int affectedClasses,
                       List<ClassDef> replacements, Map<String, String> replacedOpenText,
                       List<String> errors, String transactionError, String firstErrorClass,
                       int firstErrorLine, int firstErrorColumn) {
            this.cancelled = cancelled;
            this.replacedCount = replacedCount;
            this.affectedClasses = affectedClasses;
            this.replacements = Collections.unmodifiableList(replacements);
            this.replacedOpenText = Collections.unmodifiableMap(replacedOpenText);
            this.errors = Collections.unmodifiableList(errors);
            this.transactionError = transactionError;
            this.firstErrorClass = firstErrorClass;
            this.firstErrorLine = firstErrorLine;
            this.firstErrorColumn = firstErrorColumn;
        }

        public boolean isCancelled() { return cancelled; }
        public int getReplacedCount() { return replacedCount; }
        public int getAffectedClasses() { return affectedClasses; }
        public List<ClassDef> getReplacements() { return replacements; }
        public Map<String, String> getReplacedOpenText() { return replacedOpenText; }
        public List<String> getErrors() { return errors; }
        public String getTransactionError() { return transactionError; }
        public String getFirstErrorClass() { return firstErrorClass; }
        public int getFirstErrorLine() { return firstErrorLine; }
        public int getFirstErrorColumn() { return firstErrorColumn; }
    }
}
