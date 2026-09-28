package modder.hub.dexeditor.utils;

import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.tools.smali.smali2.Smali;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses and stages versioned JSON patch plans stored as .txt files. */
public final class SmaliPatchEngine {
    public static final String FORMAT = "dex-editor-patch";
    private static final int VERSION = 2;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_RULES = 128;
    private static final Gson GSON = new Gson();

    private SmaliPatchEngine() {}

    public static Document read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int length;
        while ((length = input.read(buffer)) != -1) {
            total += length;
            if (total > MAX_FILE_BYTES) throw new IOException("Patch file exceeds 1 MiB.");
            bytes.write(buffer, 0, length);
        }

        final String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Patch file must be UTF-8.", e);
        }

        final Document document;
        try {
            document = GSON.fromJson(json, Document.class);
        } catch (JsonParseException e) {
            throw new IOException("Patch file is not valid JSON: " + e.getMessage(), e);
        }
        validate(document);
        return document;
    }

    public static Plan prepare(ClassTree classTree, Document document, Map<String, String> openTabs,
                               ProgressListener progress) throws Exception {
        if (classTree == null) throw new PatchException("No DEX is open.");
        return prepare(classTree, document, openTabs, classTree.getEditRevision(), progress);
    }

    public static Plan prepare(ClassTree classTree, Document document, Map<String, String> openTabs,
                               long baseRevision, ProgressListener progress) throws Exception {
        validate(document);
        if (classTree == null) throw new PatchException("No DEX is open.");
        final ClassTree.EditSnapshot snapshot;
        try {
            snapshot = classTree.snapshotEditState();
        } catch (IllegalStateException e) {
            throw new PatchException("No DEX is open.", e);
        }
        if (snapshot.getRevision() != baseRevision) {
            throw new PatchException("The DEX changed after patch preview started. Preview the patch again.");
        }

        List<CompiledRule> rules = new ArrayList<>();
        long[] counts = new long[document.rules.size()];
        for (int i = 0; i < document.rules.size(); i++) {
            Rule rule = document.rules.get(i);
            rules.add(new CompiledRule(rule, i));
        }

        List<ClassDef> classes = new ArrayList<>(snapshot.getClassDefs().values());
        Collections.sort(classes, Comparator.comparing(ClassDef::getType));
        Map<String, String> currentTabs = openTabs == null ? Collections.<String, String>emptyMap() : openTabs;
        Map<String, Candidate> changedClasses = new LinkedHashMap<>();

        for (int classIndex = 0; classIndex < classes.size(); classIndex++) {
            if (progress != null && progress.isCancelled()) throw new CancelledException();
            ClassDef originalClass = classes.get(classIndex);
            String descriptor = originalClass.getType();
            String className = descriptor.substring(1, descriptor.length() - 1);
            List<CompiledRule> applicable = new ArrayList<>();
            for (CompiledRule rule : rules) {
                if (rule.matchesClass(descriptor)) applicable.add(rule);
            }

            if (!applicable.isEmpty()) {
                String text = currentTabs.get(className);
                if (text == null) text = classTree.getSmaliByType(originalClass);
                boolean modified = false;
                for (CompiledRule rule : applicable) {
                    RuleApplication result;
                    try {
                        result = applyRule(text, rule);
                    } catch (RuntimeException e) {
                        throw new PatchException("Rule '" + rule.source.id + "' failed in " + descriptor + ": " + e.getMessage(), e);
                    }
                    counts[rule.index] += result.matches;
                    text = result.text;
                    modified |= result.matches > 0;
                }

                if (modified) {
                    final ClassDef patched;
                    try {
                        patched = Smali.assemble(text, new SmaliOptions(), classTree.getDexVersionForClass(className));
                    } catch (Exception e) {
                        throw new PatchException("Patched Smali does not assemble for " + descriptor + ": " + e.getMessage(), e);
                    }
                    if (!descriptor.equals(patched.getType())) {
                        throw new PatchException("Rule changed the class descriptor " + descriptor + " to " + patched.getType() + ". Class renaming is not supported by text patches.");
                    }
                    changedClasses.put(className, new Candidate(patched, text));
                }
            }

            if (progress != null) progress.onProgress(classIndex + 1, classes.size(), descriptor);
        }

        List<RuleResult> results = new ArrayList<>();
        for (int i = 0; i < document.rules.size(); i++) {
            Rule rule = document.rules.get(i);
            long actual = counts[i];
            if (actual != rule.expectedMatches) {
                throw new PatchException("Rule '" + rule.id + "' expected " + rule.expectedMatches + " match(es), found " + actual + ". No changes were applied.");
            }
            results.add(new RuleResult(rule.id, rule.expectedMatches, actual));
        }
        if (changedClasses.isEmpty()) throw new PatchException("Patch produced no class changes.");
        return new Plan(classTree, document.name, changedClasses, results, baseRevision);
    }

    private static RuleApplication applyRule(String text, CompiledRule rule) {
        if (rule.methodSelector == null) {
            SmaliTextReplacer.Result result = rule.replacer.apply(text);
            return new RuleApplication(result.getText(), result.getMatches());
        }

        StringBuilder output = null;
        int copiedThrough = 0;
        int scan = 0;
        int matches = 0;
        while (scan < text.length()) {
            int lineEnd = lineEnd(text, scan);
            String line = text.substring(scan, contentEnd(text, scan, lineEnd)).trim();
            if (!isMethodStart(line)) {
                scan = lineEnd;
                continue;
            }

            String signature = parseMethodSignature(line);
            int blockEnd = lineEnd;
            boolean closed = false;
            while (blockEnd < text.length()) {
                int nextEnd = lineEnd(text, blockEnd);
                String nextLine = text.substring(blockEnd, contentEnd(text, blockEnd, nextEnd)).trim();
                if (isMethodStart(nextLine)) throw new IllegalArgumentException("Nested .method directive before .end method.");
                if (isMethodEnd(nextLine)) {
                    blockEnd = nextEnd;
                    closed = true;
                    break;
                }
                blockEnd = nextEnd;
            }
            if (!closed) throw new IllegalArgumentException("Method " + signature + " has no .end method.");

            String selectedValue = "signature".equals(rule.methodSelector.field)
                    ? signature : signature.substring(0, signature.indexOf('('));
            if (rule.methodSelector.matcher.matches(selectedValue)) {
                SmaliTextReplacer.Result result = rule.replacer.apply(text.substring(scan, blockEnd));
                if (result.getMatches() > 0) {
                    if (output == null) output = new StringBuilder(text.length());
                    output.append(text, copiedThrough, scan).append(result.getText());
                    copiedThrough = blockEnd;
                    matches += result.getMatches();
                }
            }
            scan = blockEnd;
        }

        if (matches == 0) return new RuleApplication(text, 0);
        output.append(text, copiedThrough, text.length());
        return new RuleApplication(output.toString(), matches);
    }

    private static int lineEnd(String text, int start) {
        int newline = text.indexOf('\n', start);
        return newline < 0 ? text.length() : newline + 1;
    }

    private static int contentEnd(String text, int start, int lineEnd) {
        int end = lineEnd;
        if (end > start && text.charAt(end - 1) == '\n') end--;
        if (end > start && text.charAt(end - 1) == '\r') end--;
        return end;
    }

    private static boolean isMethodStart(String line) {
        return line.startsWith(".method ") || line.startsWith(".method\t");
    }

    private static boolean isMethodEnd(String line) {
        return line.equals(".end method") || line.startsWith(".end method #") || line.startsWith(".end method\t#");
    }

    private static String parseMethodSignature(String header) {
        String[] tokens = header.split("\\s+");
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            int argsStart = token.indexOf('(');
            if (argsStart > 0 && token.indexOf(')', argsStart) > argsStart) return token;
        }
        throw new IllegalArgumentException("Could not read method signature from: " + header);
    }

    private static final class RuleApplication {
        final String text;
        final int matches;

        RuleApplication(String text, int matches) {
            this.text = text;
            this.matches = matches;
        }
    }

    private static void validate(Document document) throws IOException {
        if (document == null) throw new IOException("Patch file is empty.");
        if (!FORMAT.equals(document.format)) throw new IOException("Unsupported patch format. Expected '" + FORMAT + "'.");
        if (document.version == null || document.version < 1 || document.version > VERSION) throw new IOException("Unsupported patch version: " + document.version + ".");
        if (document.rules == null || document.rules.isEmpty()) throw new IOException("Patch must contain at least one rule.");
        if (document.rules.size() > MAX_RULES) throw new IOException("Patch may contain at most " + MAX_RULES + " rules.");
        if (document.name == null || document.name.trim().isEmpty()) document.name = "Unnamed patch";

        Set<String> ids = new HashSet<>();
        for (Rule rule : document.rules) {
            if (rule == null || rule.id == null || !rule.id.matches("[A-Za-z0-9_.-]+")) throw new IOException("Each rule needs a unique id using letters, digits, '.', '_' or '-'.");
            if (!ids.add(rule.id)) throw new IOException("Duplicate rule id: " + rule.id);
            if (!"smali".equals(rule.target) && !"strings".equals(rule.target)) throw new IOException("Rule '" + rule.id + "' target must be 'smali' or 'strings'.");
            if (!"literal".equals(rule.mode) && !"regex".equals(rule.mode)) throw new IOException("Rule '" + rule.id + "' mode must be 'literal' or 'regex'.");
            if (rule.matchCase == null) throw new IOException("Rule '" + rule.id + "' must define matchCase.");
            if (rule.find == null || rule.find.isEmpty()) throw new IOException("Rule '" + rule.id + "' find text cannot be empty.");
            if (rule.replace == null) throw new IOException("Rule '" + rule.id + "' must define replace (use an empty string to delete matches).");
            if (rule.expectedMatches == null || rule.expectedMatches < 1) throw new IOException("Rule '" + rule.id + "' expectedMatches must be a positive integer.");
            if (rule.classes != null && rule.classes.isEmpty()) {
                throw new IOException("Rule '" + rule.id + "' classes must not be empty; omit it to target all classes.");
            }
            if (rule.classes != null) {
                for (String descriptor : rule.classes) {
                    if (descriptor == null || !descriptor.startsWith("L") || !descriptor.endsWith(";") || descriptor.length() < 3 || descriptor.indexOf('.') >= 0 || descriptor.indexOf(';') != descriptor.length() - 1) {
                        throw new IOException("Rule '" + rule.id + "' has an invalid class descriptor: " + descriptor);
                    }
                }
            }
            if (document.version < 2 && (rule.classSelector != null || rule.methodSelector != null)) {
                throw new IOException("Rule '" + rule.id + "' selectors require patch version 2.");
            }
            try {
                rule.compiled = SmaliTextReplacer.compile(rule.find, rule.replace, "regex".equals(rule.mode), rule.matchCase, "strings".equals(rule.target));
                if (rule.classSelector != null) validateSelector(rule.classSelector, rule.id, true);
                if (rule.methodSelector != null) validateSelector(rule.methodSelector, rule.id, false);
            } catch (RuntimeException e) {
                throw new IOException("Rule '" + rule.id + "' has an invalid pattern: " + e.getMessage(), e);
            }
        }
    }

    private static void validateSelector(Selector selector, String ruleId, boolean classSelector) {
        if (selector.pattern == null || selector.pattern.isEmpty()) {
            throw new IllegalArgumentException("Rule '" + ruleId + "' selector pattern cannot be empty.");
        }
        if (selector.mode == null) selector.mode = "literal";
        if (!"literal".equals(selector.mode) && !"regex".equals(selector.mode)) {
            throw new IllegalArgumentException("Rule '" + ruleId + "' selector mode must be 'literal' or 'regex'.");
        }
        if (selector.field == null) selector.field = classSelector ? "simpleName" : "name";
        boolean validField = classSelector
                ? "simpleName".equals(selector.field) || "fullName".equals(selector.field) || "descriptor".equals(selector.field)
                : "name".equals(selector.field) || "signature".equals(selector.field);
        if (!validField) {
            throw new IllegalArgumentException("Rule '" + ruleId + "' has an invalid "
                    + (classSelector ? "class" : "method") + " selector field: " + selector.field);
        }
        if (selector.matchCase == null) selector.matchCase = false;
        if (selector.exactlyMatch == null) selector.exactlyMatch = false;
        selector.matcher = SmaliTextReplacer.compileSearchMatcher(selector.pattern,
                "regex".equals(selector.mode), selector.matchCase, selector.exactlyMatch);
    }

    public interface ProgressListener {
        boolean isCancelled();
        void onProgress(int processed, int total, String currentClass);
    }

    public static class Document {
        public String format;
        public Integer version;
        public String name;
        public List<Rule> rules;
    }

    public static class Rule {
        public String id;
        public String target;
        public String mode;
        public Boolean matchCase;
        public List<String> classes;
        public Selector classSelector;
        public Selector methodSelector;
        public String find;
        public String replace;
        public Integer expectedMatches;
        private transient SmaliTextReplacer.Rule compiled;
    }

    public static class Selector {
        public String field;
        public String pattern;
        public String mode;
        public Boolean matchCase;
        public Boolean exactlyMatch;
        private transient SmaliTextReplacer.SearchMatcher matcher;
    }

    private static final class CompiledRule {
        final Rule source;
        final int index;
        final Set<String> classes;
        final SmaliTextReplacer.Rule replacer;
        final Selector classSelector;
        final Selector methodSelector;

        CompiledRule(Rule source, int index) {
            this.source = source;
            this.index = index;
            this.classes = source.classes == null ? Collections.<String>emptySet() : new HashSet<>(source.classes);
            this.replacer = source.compiled;
            this.classSelector = source.classSelector;
            this.methodSelector = source.methodSelector;
        }

        boolean matchesClass(String descriptor) {
            if (!classes.isEmpty() && !classes.contains(descriptor)) return false;
            if (classSelector == null) return true;
            String fullName = descriptor.substring(1, descriptor.length() - 1);
            String candidate;
            switch (classSelector.field) {
                case "descriptor": candidate = descriptor; break;
                case "fullName": candidate = fullName; break;
                default:
                    int slash = fullName.lastIndexOf('/');
                    candidate = slash < 0 ? fullName : fullName.substring(slash + 1);
                    break;
            }
            return classSelector.matcher.matches(candidate);
        }
    }

    private static final class Candidate {
        final ClassDef classDef;
        final String smali;

        Candidate(ClassDef classDef, String smali) {
            this.classDef = classDef;
            this.smali = smali;
        }
    }

    public static final class Plan {
        private final ClassTree owner;
        private final String name;
        private final Map<String, Candidate> candidates;
        private final List<RuleResult> results;
        private final long baseRevision;
        private boolean committed;

        Plan(ClassTree owner, String name, Map<String, Candidate> candidates, List<RuleResult> results, long baseRevision) {
            this.owner = owner;
            this.name = name;
            this.candidates = candidates;
            this.results = Collections.unmodifiableList(new ArrayList<>(results));
            this.baseRevision = baseRevision;
        }

        public String getName() { return name; }
        public int getChangedClassCount() { return candidates.size(); }
        public List<RuleResult> getRuleResults() { return results; }

        public Map<String, String> getUpdatedSmali() {
            Map<String, String> updated = new HashMap<>();
            for (Map.Entry<String, Candidate> entry : candidates.entrySet()) updated.put(entry.getKey(), entry.getValue().smali);
            return updated;
        }

        public void commit(ClassTree classTree) {
            if (committed) throw new IllegalStateException("Patch plan was already applied.");
            if (classTree != owner) throw new IllegalStateException("The DEX changed after patch preview. Preview the patch again before applying it.");
            List<ClassDef> classDefs = new ArrayList<>();
            for (Candidate candidate : candidates.values()) classDefs.add(candidate.classDef);
            classTree.commitClassDefs(baseRevision, classDefs);
            committed = true;
        }
    }

    public static final class RuleResult {
        private final String id;
        private final int expected;
        private final long actual;

        RuleResult(String id, int expected, long actual) {
            this.id = id;
            this.expected = expected;
            this.actual = actual;
        }

        public String getId() { return id; }
        public int getExpected() { return expected; }
        public long getActual() { return actual; }
    }

    public static class PatchException extends Exception {
        PatchException(String message) { super(message); }
        PatchException(String message, Throwable cause) { super(message, cause); }
    }

    public static class CancelledException extends Exception {
        CancelledException() { super("Patch preview cancelled."); }
    }
}
