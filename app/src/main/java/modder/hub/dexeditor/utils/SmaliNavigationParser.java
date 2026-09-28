package modder.hub.dexeditor.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Parses the declarations and line offsets used by the Smali navigation dialog. */
public final class SmaliNavigationParser {
    private SmaliNavigationParser() {}

    public static Result parse(File file, BooleanSupplier cancelled) throws IOException {
        List<HashMap<String, Object>> methods = new ArrayList<>();
        List<HashMap<String, Object>> fields = new ArrayList<>();
        List<HashMap<String, Object>> classes = new ArrayList<>();
        List<HashMap<String, Object>> strings = new ArrayList<>();
        String className = "???";
        int pendingClassLine = -1;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;
            boolean insideMethod = false;
            String methodName = "";
            String methodSignature = "";
            int methodStartLine = -1;

            while ((line = reader.readLine()) != null) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return null;
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                if (trimmed.startsWith("const-string")) {
                    int start = trimmed.indexOf('"');
                    int end = trimmed.lastIndexOf('"');
                    if (start >= 0 && end > start) {
                        HashMap<String, Object> entry = new HashMap<>();
                        entry.put("StringName", trimmed.substring(start + 1, end));
                        entry.put("StartLineNumber", lineNumber);
                        strings.add(entry);
                    }
                }

                String[] tokens = trimmed.split("\\s+");
                if (tokens[0].equals(".method")) {
                    insideMethod = true;
                    methodName = tokens[tokens.length - 1];
                    methodSignature = trimmed;
                    methodStartLine = lineNumber;
                } else if (tokens[0].equals(".end") && tokens.length > 1
                        && tokens[1].equals("method")) {
                    if (insideMethod && methodStartLine != -1) {
                        HashMap<String, Object> entry = new HashMap<>();
                        entry.put("MethodOrFieldName", methodName);
                        entry.put("FullMethodOrField", methodSignature);
                        entry.put("StartLineNumber", methodStartLine);
                        entry.put("EndLineNumber", lineNumber);
                        methods.add(entry);
                    }
                    insideMethod = false;
                    methodName = "";
                    methodSignature = "";
                    methodStartLine = -1;
                } else if (tokens[0].equals(".field")) {
                    int declarationStart = trimmed.indexOf(".field") + ".field".length();
                    String signature = trimmed.substring(declarationStart).trim();
                    int colon = signature.indexOf(':');
                    if (colon >= 0) {
                        String fieldName = signature.substring(0, colon).trim();
                        HashMap<String, Object> entry = new HashMap<>();
                        entry.put("MethodOrFieldName", fieldName.substring(fieldName.lastIndexOf(' ') + 1)
                                + ":" + signature.substring(colon + 1).trim());
                        entry.put("FullMethodOrField", trimmed);
                        entry.put("StartLineNumber", lineNumber);
                        fields.add(entry);
                    }
                } else if (tokens[0].equals(".class") && trimmed.endsWith(";")) {
                    className = tokens[tokens.length - 1];
                    pendingClassLine = lineNumber;
                } else if (tokens[0].equals(".super") && pendingClassLine != -1) {
                    HashMap<String, Object> entry = new HashMap<>();
                    entry.put("MethodOrFieldName", className);
                    entry.put("StartLineNumber", pendingClassLine);
                    entry.put("SuperClass", trimmed.substring(".super".length()).trim());
                    classes.add(entry);
                    pendingClassLine = -1;
                }
            }
        }

        Map<String, List<HashMap<String, Object>>> data = new HashMap<>();
        data.put("MethodInfo", methods);
        data.put("FieldInfo", fields);
        data.put("ClassInfo", classes);
        data.put("StringInfo", strings);
        return new Result(className, data);
    }

    public static final class Result {
        private final String className;
        private final Map<String, List<HashMap<String, Object>>> data;

        private Result(String className, Map<String, List<HashMap<String, Object>>> data) {
            this.className = className;
            this.data = data;
        }

        public String getClassName() { return className; }
        public Map<String, List<HashMap<String, Object>>> getData() { return data; }
    }
}
