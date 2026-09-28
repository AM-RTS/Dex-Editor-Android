package modder.hub.dexeditor.utils;

import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.tools.smali.smali2.Smali;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Applies string-literal replacements to a class snapshot and commits only valid classes. */
public final class SmaliLiteralReplacementTransaction {
    private SmaliLiteralReplacementTransaction() {}

    public interface ProgressListener {
        void onProgress(int processed, int total);
    }

    public static Result applyAndCommit(ClassTree tree, ClassTree.EditSnapshot snapshot,
                                        Map<String, String> openEditorText,
                                        Map<String, String> exactReplacements,
                                        SmaliTextReplacer.Rule substringRule,
                                        BooleanSupplier cancelled,
                                        ProgressListener progress) {
        List<ClassDef> replacements = new ArrayList<>();
        Map<String, String> updatedOpenText = new HashMap<>();
        List<String> errors = new ArrayList<>();
        int replacedCount = 0;
        int affectedClasses = 0;
        int processed = 0;
        Map<String, ClassDef> classes = snapshot.getClassDefs();

        try {
            for (Map.Entry<String, ClassDef> entry : classes.entrySet()) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    return new Result(true, replacedCount, affectedClasses, updatedOpenText, errors, null);
                }
                String className = entry.getKey();
                try {
                    String original = openEditorText.get(className);
                    if (original == null) original = tree.getSmaliByType(entry.getValue());
                    SmaliTextReplacer.Result transformed = SmaliTextReplacer.transformStringLiterals(
                            original, literal -> {
                        String replaced = literal;
                        if (exactReplacements != null) {
                            String exact = exactReplacements.get(literal);
                            if (exact != null) replaced = exact;
                        }
                        if (substringRule != null) replaced = substringRule.apply(replaced).getText();
                        return replaced;
                    });
                    int changedLiterals = transformed.getMatches();

                    if (changedLiterals > 0) {
                        String updated = transformed.getText();
                        ClassDef replacement = Smali.assemble(updated, new SmaliOptions(),
                                tree.getDexVersionForClass(className));
                        if (!entry.getValue().getType().equals(replacement.getType())) {
                            throw new IllegalArgumentException("Edited class descriptor does not match " + className);
                        }
                        replacements.add(replacement);
                        replacedCount += changedLiterals;
                        affectedClasses++;
                        if (openEditorText.containsKey(className)) updatedOpenText.put(className, updated);
                    }
                } catch (Exception e) {
                    errors.add(className + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                } finally {
                    processed++;
                    if (progress != null) progress.onProgress(processed, classes.size());
                }
            }

            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return new Result(true, replacedCount, affectedClasses, updatedOpenText, errors, null);
            }
            if (errors.isEmpty()) {
                try {
                    tree.commitClassDefs(snapshot.getRevision(), replacements);
                } catch (RuntimeException e) {
                    return new Result(false, replacedCount, affectedClasses, updatedOpenText, errors,
                            e.getMessage());
                }
            }
            return new Result(false, replacedCount, affectedClasses, updatedOpenText, errors, null);
        } catch (CancellationException e) {
            return new Result(true, replacedCount, affectedClasses, updatedOpenText, errors, null);
        }
    }

    public static final class Result {
        private final boolean cancelled;
        private final int replacedCount;
        private final int affectedClasses;
        private final Map<String, String> updatedOpenText;
        private final List<String> errors;
        private final String transactionError;

        private Result(boolean cancelled, int replacedCount, int affectedClasses,
                       Map<String, String> updatedOpenText, List<String> errors, String transactionError) {
            this.cancelled = cancelled;
            this.replacedCount = replacedCount;
            this.affectedClasses = affectedClasses;
            this.updatedOpenText = Collections.unmodifiableMap(new HashMap<>(updatedOpenText));
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
            this.transactionError = transactionError;
        }

        public boolean isCancelled() { return cancelled; }
        public int getReplacedCount() { return replacedCount; }
        public int getAffectedClasses() { return affectedClasses; }
        public Map<String, String> getUpdatedOpenText() { return updatedOpenText; }
        public List<String> getErrors() { return errors; }
        public String getTransactionError() { return transactionError; }
    }
}
