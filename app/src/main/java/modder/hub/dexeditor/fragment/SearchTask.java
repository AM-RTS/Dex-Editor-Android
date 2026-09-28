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
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
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

import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.tools.smali.smali2.Smali;
import com.android.tools.smali.smali2.SmaliCatchErrFlexLexer;
import com.android.tools.smali.smali.smaliParser;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.StringWriter;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.Token;

import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.iface.value.*;

import modder.hub.dexeditor.R;
import modder.hub.dexeditor.activity.DexEditorActivity;
import modder.hub.dexeditor.adapter.TreeAdapter;
import modder.hub.dexeditor.model.TreeNode;
import modder.hub.dexeditor.utils.ClassTree;
import modder.hub.dexeditor.utils.Notify_MT;
import modder.hub.dexeditor.utils.SketchwareUtil;
import modder.hub.dexeditor.utils.SmaliTextReplacer;
import modder.hub.dexeditor.utils.TreeHelper;
import modder.hub.dexeditor.utils.UIHelper;
import modder.hub.dexeditor.views.AlertProgress;
import modder.hub.dexeditor.views.FastScrollerRecyclerView;

// Author : @developer-krushna
// Got some LOGICS from AI but thought , idea other resources copied from mt manager interface
// Thanks @Bin

/**
 * SearchFragment: Handles all search and replace operations in the DEX editor.
 * It supports Smali code, class names, methods, fields, and even raw integers/hex.
 */
final class SearchTask {
        private static final ThreadLocal<BaksmaliOptions> OPTIONS_THREAD_LOCAL = new ThreadLocal<BaksmaliOptions>() {
            @Override protected BaksmaliOptions initialValue() { return new BaksmaliOptions(); }
        };
        private static final ThreadLocal<StringBuilder> BUFFER_THREAD_LOCAL = new ThreadLocal<StringBuilder>() {
            @Override protected StringBuilder initialValue() { return new StringBuilder(64 * 1024); }
        };

        private final WeakReference<SearchFragment> fragmentRef;
        private final String query, path, type, highlightQuery;
        private final boolean searchSubfolders, matchCase, isRegex, exactlyMatch, finalIsNumberValid;
        private final List<String> scopeClasses, excludeList = new ArrayList<>();
        private final Pattern compiledPattern;
        private final SmaliTextReplacer.SearchMatcher compiledSearchMatcher;
        private final long finalTargetValue;
        private final AtomicInteger foundCount = new AtomicInteger(0), processedCount = new AtomicInteger(0);
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final Map<String, String> openEditorsContent = new HashMap<>();
        private volatile WeakReference<DexEditorActivity> activityRef = new WeakReference<>(null);
        private volatile ClassTree classTree;
        private AlertProgress progressDialog;
        private volatile boolean isStopped = false, warningShown = false, hasConfirmedLargeSearch = false;
        private volatile boolean viewDestroyed = false;
        private final AtomicBoolean isFinalized = new AtomicBoolean(false);
        private long lastProgressUpdateTime = 0;
        private ExecutorService executor;
        private volatile Thread searchThread;
        private volatile Thread finalizeThread;

        SearchTask(SearchFragment fragment, String query, String path, String type, boolean searchSubfolders, boolean matchCase, boolean isRegex, boolean exactlyMatch, boolean isHex, List<String> scopeClasses, boolean useExcludeList) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.query = query;
            this.path = path.startsWith("/") ? path : "/" + path;
            this.type = type;
            this.searchSubfolders = searchSubfolders;
            this.matchCase = matchCase;
            this.isRegex = isRegex;
            this.exactlyMatch = exactlyMatch;
            this.scopeClasses = scopeClasses;

            long targetValue = 0;
            boolean isNumberValid = false;
            String hq = query;
            if (type.equals("Integer")) {
                try {
                    String q = query.trim();
                    if (isHex) {
                        if (q.startsWith("0x")) q = q.substring(2);
                        targetValue = Long.parseLong(q, 16);
                        hq = "0x" + q.toLowerCase(Locale.ROOT);
                    } else {
                        targetValue = Long.parseLong(q);
                        hq = "0x" + (targetValue >= Integer.MIN_VALUE && targetValue <= Integer.MAX_VALUE ? Integer.toHexString((int) targetValue) : Long.toHexString(targetValue)).toLowerCase(Locale.ROOT);
                    }
                    isNumberValid = true;
                } catch (Exception ignored) {}
            }
            this.finalTargetValue = targetValue;
            this.finalIsNumberValid = isNumberValid;
            this.highlightQuery = hq;

            SearchFragment frag = fragmentRef.get();
            if (frag != null && useExcludeList && (this.path.equals("/") || this.path.isEmpty())) {
                String savedExcludes = frag.requireContext().getSharedPreferences("search_prefs", android.content.Context.MODE_PRIVATE).getString("exclude_list", "");
                if (!savedExcludes.isEmpty()) {
                    for (String s : savedExcludes.split("\n")) {
                        String t = s.trim();
                        if (!t.isEmpty()) excludeList.add(t);
                    }
                }
            }
            this.compiledPattern = isRegex ? Pattern.compile(query, matchCase ? 0 : Pattern.CASE_INSENSITIVE) : null;
            this.compiledSearchMatcher = SmaliTextReplacer.compileSearchMatcher(query, isRegex, matchCase, exactlyMatch);
        }

        void start() {
            SearchFragment fragment = fragmentRef.get();
            if (fragment == null) return;
            DexEditorActivity activity = (DexEditorActivity) fragment.getActivity();
            if (activity == null) return;
            SearchTask previousTask = fragment.getActiveSearchTask();
            if (previousTask != null && previousTask != this) previousTask.cancelForViewDestroyed();
            fragment.setActiveSearchTask(this);
            activityRef = new WeakReference<>(activity);
            classTree = activity.getClassTree();

            final List<TreeNode> results = Collections.synchronizedList(new ArrayList<>());

            // Prepare the progress dialog
            progressDialog = new AlertProgress(activity);
            progressDialog.setTitle("Searching...");
            progressDialog.setMessage("Found: 0");
            progressDialog.setCancelable(false);
            progressDialog.setOnCancelListener(new AlertProgress.OnCancelListener() {
                @Override
                public void onCancel() {
                    if (isFinalized.compareAndSet(false, true)) {
                        isStopped = true;
                        if (executor != null) executor.shutdownNow();
                        // On cancel/back press, show what was found so far
                        finalizeThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                finalizeResults(results);
                            }
                        }, "dex-editor-search-results");
                        finalizeThread.start();
                    }
                }
            });
            progressDialog.show();

            // Cache the content of open editors to search unsaved changes too
            List<EditorTab> openTabs = activity.getOpenTabsSnapshot();
            for (int i = 0; i < openTabs.size(); i++) {
                EditorTab tab = openTabs.get(i);
                if (tab.type == 0) {
                    EditorFragment editorFrag = activity.getFragmentAtIndex(i);
                    if (editorFrag != null && editorFrag.getEditor() != null) {
                        openEditorsContent.put(tab.className, editorFrag.getEditor().getText().toString());
                    } else if (tab.isModified) {
                        openEditorsContent.put(tab.className, tab.content);
                    }
                }
            }

            searchThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    if (classTree == null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                onPostExecute(new ArrayList<>());
                            }
                        });
                        return;
                    }

                    // Building the list of classes to search based on the scope
                    List<ClassDef> classesToSearch = new ArrayList<>();
                    if (scopeClasses != null) {
                        for (String className : scopeClasses) {
                            synchronized (classTree) {
                                ClassDef classDef = classTree.getClassDef(className);
                                if (classDef != null) classesToSearch.add(classDef);
                            }
                        }
                    } else {
                        synchronized (classTree) {
                            classesToSearch.addAll(classTree.getClassDefsSnapshot());
                        }
                    }

                    int total = classesToSearch.size();
                    String tempFilterPath = path.isEmpty() ? "" : path.substring(1);
                    if (tempFilterPath.endsWith("/"))
                        tempFilterPath = tempFilterPath.substring(0, tempFilterPath.length() - 1);
                    final String filterPath = tempFilterPath;

                    int numThreads = Math.max(1, Math.min(SearchFragment.MAX_CLASS_WORKERS, Runtime.getRuntime().availableProcessors() - 1));
                    executor = Executors.newFixedThreadPool(numThreads);
                    final int finalTotal = total;

                    for (final ClassDef classDef : classesToSearch) {
                        if (isStopped) break;
                        executor.execute(new Runnable() {
                            @Override
                            public void run() {
                                if (isStopped || Thread.currentThread().isInterrupted()) return;
                                if (type.equals("Integer") && !finalIsNumberValid) {
                                    updateProgress(processedCount.incrementAndGet(), finalTotal);
                                    return;
                                }

                                if (foundCount.get() >= 250000) {
                                    stopSearchWithLimit();
                                    return;
                                }

                                if (foundCount.get() >= 1000 && !hasConfirmedLargeSearch) {
                                    synchronized (SearchTask.this) {
                                        if (!hasConfirmedLargeSearch) {
                                            if (!warningShown) {
                                                warningShown = true;
                                                mainHandler.post(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        showWarningDialog();
                                                    }
                                                });
                                            }
                                            while (warningShown && !isStopped) {
                                                try {
                                                    SearchTask.this.wait();
                                                } catch (InterruptedException e) {
                                                    isStopped = true;
                                                }
                                            }
                                        }
                                    }
                                }
                                if (isStopped) return;

                                String fullType = classDef.getType();
                                String className = fullType.substring(1, fullType.length() - 1);

                                if (!excludeList.isEmpty()) {
                                    for (String excludePath : excludeList) {
                                        if (className.startsWith(excludePath)) {
                                            updateProgress(processedCount.incrementAndGet(), finalTotal);
                                            return;
                                        }
                                    }
                                }

                                if (scopeClasses == null && !filterPath.isEmpty()) {
                                    boolean inPath = false;
                                    if (searchSubfolders) {
                                        if (className.startsWith(filterPath)) inPath = true;
                                    } else {
                                        String parentPath = className.contains("/") ? className.substring(0, className.lastIndexOf('/')) : "";
                                        if (parentPath.equals(filterPath)) inPath = true;
                                    }
                                    if (!inPath) {
                                        updateProgress(processedCount.incrementAndGet(), finalTotal);
                                        return;
                                    }
                                }

                                // Class name is simple search
                                // only search the class node
                                // so its always faster
                                if (type.equals("Class name")) {
                                    String clsNamePart = className.contains("/") ? className.substring(className.lastIndexOf('/') + 1) : className;
                                    boolean match = false;
                                    if (query.contains(".") || query.contains("/")) {
                                        if (checkMatch(className) || checkMatch(className.replace('/', '.')))
                                            match = true;
                                    } else {
                                        if (checkMatch(clsNamePart)) match = true;
                                    }
                                    if (match) {
                                        if (foundCount.incrementAndGet() >= 250000) {
                                    stopSearchWithLimit();
                                            return;
                                        }
                                        results.add(new TreeNode(clsNamePart, className, 0, false));
                                    }
                                    updateProgress(processedCount.incrementAndGet(), finalTotal);
                                    return;
                                }

                                List<TreeNode> snippets = new ArrayList<>();
                                boolean match = false;
                                String smali = null;

                                switch (type) {
                                    case "Smali":
                                        try {
                                            String openContent = openEditorsContent.get(className);

                                            if (openContent != null) {
                                                smali = openContent;
                                            } else {
                                                smali = generateSmali(classDef);
                                            }

                                            if (!isRegex && !query.contains("\n")) {
                                                if (!checkMatch(smali)) {
                                                    updateProgress(processedCount.incrementAndGet(), finalTotal);
                                                    return;
                                                }
                                            }

                                            if (isRegex || query.contains("\n")) {
                                                    Pattern pattern = isRegex ? compiledPattern : Pattern.compile(Pattern.quote(query), matchCase ? 0 : Pattern.CASE_INSENSITIVE);
                                                    java.util.regex.Matcher matcher = pattern.matcher(smali);
                                                    while (matcher.find()) {
                                                        if (foundCount.incrementAndGet() >= 250000) {
                                    stopSearchWithLimit();
                                                            return;
                                                        }
                                                        int startPos = matcher.start();
                                                        int lineStart = smali.lastIndexOf('\n', startPos) + 1;
                                                        int lineEnd = smali.indexOf('\n', startPos);
                                                        if (lineEnd == -1) lineEnd = smali.length();
                                                        String lineText = smali.substring(lineStart, lineEnd);
                                                        int lineIdx = countLinesBefore(smali, lineStart);
                                                        TreeNode snippet = new TreeNode(lineText.trim(), className, 0, false);
                                                        snippet.setSnippet(true);
                                                        snippet.setLineNumber(lineIdx);
                                                        snippets.add(snippet);
                                                        match = true;
                                                    }
                                                } else {
                                                    int start = 0, lineIdx = 0, end;
                                                    while ((end = smali.indexOf('\n', start)) != -1) {
                                                        if (checkMatchInRange(smali, start, end)) {
                                                            if (foundCount.incrementAndGet() >= 250000) {
                                    stopSearchWithLimit();
                                                                return;
                                                            }
                                                            String lineText = smali.substring(start, end);
                                                            TreeNode snippet = new TreeNode(lineText.trim(), className, 0, false);
                                                            snippet.setSnippet(true);
                                                            snippet.setLineNumber(lineIdx);
                                                            snippets.add(snippet);
                                                            match = true;
                                                        }
                                                        start = end + 1;
                                                        lineIdx++;
                                                    }
                                                    if (start < smali.length() && checkMatchInRange(smali, start, smali.length())) {
                                                        if (foundCount.incrementAndGet() >= 250000) {
                                                            stopSearchWithLimit();
                                                            return;
                                                        }
                                                        String lineText = smali.substring(start);
                                                        TreeNode snippet = new TreeNode(lineText.trim(), className, 0, false);
                                                        snippet.setSnippet(true);
                                                        snippet.setLineNumber(lineIdx);
                                                        snippets.add(snippet);
                                                        match = true;
                                                }
                                            }
                                        } catch (Exception ignored) {}
                                        break;
                                    case "Field name":
                                        for (Field field : classDef.getFields()) {
                                            if (checkMatch(field.getName())) {
                                    if (foundCount.incrementAndGet() >= 250000) { stopSearchWithLimit(); return; }
                                                if (smali == null) smali = generateSmaliSafe(classDef);
                                                String snippetText = ".field " + AccessFlags.formatAccessFlagsForField(field.getAccessFlags()) + " " + field.getName() + ":" + field.getType();
                                                int lineIdx = findLineOfText(smali, field.getName());
                                                TreeNode snippet = new TreeNode(snippetText, className, 0, false);
                                                snippet.setSnippet(true);
                                                snippet.setLineNumber(lineIdx != -1 ? lineIdx : 0);
                                                preHighlightSnippet(snippet, highlightQuery);
                                                snippets.add(snippet);
                                                match = true;
                                            }
                                        }
                                        break;
                                    case "Method name":
                                        for (Method method : classDef.getMethods()) {
                                            if (checkMatch(method.getName())) {
                                    if (foundCount.incrementAndGet() >= 250000) { stopSearchWithLimit(); return; }
                                                if (smali == null) smali = generateSmaliSafe(classDef);
                                                StringBuilder desc = new StringBuilder("(");
                                                for (MethodParameter param : method.getParameters()) desc.append(param.getType());
                                                desc.append(")").append(method.getReturnType());
                                                String snippetText = ".method " + AccessFlags.formatAccessFlagsForMethod(method.getAccessFlags()) + " " + method.getName() + desc;
                                                int lineIdx = findLineOfText(smali, ".method " + AccessFlags.formatAccessFlagsForMethod(method.getAccessFlags()) + " " + method.getName());
                                                TreeNode snippet = new TreeNode(snippetText, className, 0, false);
                                                snippet.setSnippet(true);
                                                snippet.setLineNumber(lineIdx != -1 ? lineIdx : 0);
                                                preHighlightSnippet(snippet, highlightQuery);
                                                snippets.add(snippet);
                                                match = true;
                                            }
                                        }
                                        break;
                                    case "String":
                                        String openContentStr = openEditorsContent.get(className);
                                        boolean hasPendingStr = classTree.hasPendingSmali(className);
                                        if (openContentStr != null || hasPendingStr) {
                                            try {
                                                smali = openContentStr != null ? openContentStr : classTree.getSmaliByType(classDef);
                                                Set<Integer> matchingLines = new java.util.LinkedHashSet<>();
                                                SmaliCatchErrFlexLexer lexer = new SmaliCatchErrFlexLexer(new StringReader(smali), Integer.MAX_VALUE);
                                                Token token;
                                                while ((token = lexer.nextToken()).getType() != Token.EOF) {
                                                    if (token.getType() != smaliParser.STRING_LITERAL) continue;
                                                    CommonToken stringToken = (CommonToken) token;
                                                    int tokenStart = stringToken.getStartIndex();
                                                    int tokenEnd = stringToken.getStopIndex() + 1;
                                                    if (tokenStart < 0 || tokenEnd > smali.length() || tokenEnd - tokenStart < 2) continue;
                                                    String literal = SmaliTextReplacer.decodeStringLiteralContent(
                                                            smali.substring(tokenStart + 1, tokenEnd - 1));
                                                    int lineStart = smali.lastIndexOf('\n', tokenStart) + 1;
                                                    if (checkMatch(literal)) matchingLines.add(lineStart);
                                                }
                                                for (int lineStart : matchingLines) {
                                                    int lineEnd = smali.indexOf('\n', lineStart);
                                                    if (lineEnd == -1) lineEnd = smali.length();
                                                    if (foundCount.incrementAndGet() >= 250000) {
                                                        stopSearchWithLimit();
                                                        return;
                                                    }
                                                    TreeNode snippet = new TreeNode(smali.substring(lineStart, lineEnd).trim(), className, 0, false);
                                                    snippet.setSnippet(true);
                                                    snippet.setLineNumber(countLinesBefore(smali, lineStart));
                                                    preHighlightSnippet(snippet, highlightQuery);
                                                    snippets.add(snippet);
                                                    match = true;
                                                }
                                            } catch (Exception ignored) {
                                            }
                                        } else {
                                            for (Method method : classDef.getMethods()) {
                                                MethodImplementation impl = method.getImplementation();
                                                if (impl != null) {
                                                    for (Instruction inst : impl.getInstructions()) {
                                                        if (inst instanceof ReferenceInstruction) {
                                                            Reference ref = ((ReferenceInstruction) inst).getReference();
                                                            if (ref instanceof StringReference) {
                                                                String str = ((StringReference) ref).getString();
                                                                if (checkMatch(str)) {
                                    if (foundCount.incrementAndGet() >= 250000) { stopSearchWithLimit(); return; }
                                                                    if (smali == null) smali = generateSmaliSafe(classDef);
                                                                    String snippetText = inst.getOpcode().name + " ..., \"" + str + "\"";
                                                                    int lineIdx = findLineOfText(smali, "\"" + str + "\"");
                                                                    TreeNode snippet = new TreeNode(snippetText, className, 0, false);
                                                                    snippet.setSnippet(true);
                                                                    snippet.setLineNumber(lineIdx != -1 ? lineIdx : 0);
                                                                    preHighlightSnippet(snippet, highlightQuery);
                                                                    snippets.add(snippet);
                                                                    match = true;
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            for (Field field : classDef.getFields()) {
                                                EncodedValue initialValue = field.getInitialValue();
                                                if (initialValue != null && collectAnnotationMatches(field.getName(), initialValue,
                                                        className, snippets, smali, classDef)) match = true;
                                            }
                                            if (searchInAnnotations(classDef.getAnnotations(), className, snippets, smali, classDef)) match = true;
                                            for (Field field : classDef.getFields()) {
                                                if (searchInAnnotations(field.getAnnotations(), className, snippets, smali, classDef)) match = true;
                                            }
                                            for (Method method : classDef.getMethods()) {
                                                if (searchInAnnotations(method.getAnnotations(), className, snippets, smali, classDef)) match = true;
                                                for (MethodParameter parameter : method.getParameters()) {
                                                    if (searchInAnnotations(parameter.getAnnotations(), className, snippets, smali, classDef)) match = true;
                                                }
                                            }
                                        }
                                        break;
                                    case "Integer":
                                        boolean hasMatched = false;
                                        for (Method method : classDef.getMethods()) {
                                            MethodImplementation impl = method.getImplementation();
                                            if (impl != null) {
                                                for (Instruction inst : impl.getInstructions()) {
                                                    if (inst instanceof NarrowLiteralInstruction) {
                                                        if (finalTargetValue >= Integer.MIN_VALUE && finalTargetValue <= Integer.MAX_VALUE
                                                                && ((NarrowLiteralInstruction) inst).getNarrowLiteral() == (int) finalTargetValue) { hasMatched = true; break; }
                                                    } else if (inst instanceof WideLiteralInstruction) {
                                                        if (((WideLiteralInstruction) inst).getWideLiteral() == finalTargetValue) { hasMatched = true; break; }
                                                    } else if (inst instanceof ArrayPayload) {
                                                        for (Number value : ((ArrayPayload) inst).getArrayElements()) {
                                                            if (value.longValue() == finalTargetValue) { hasMatched = true; break; }
                                                        }
                                                        if (hasMatched) break;
                                                    }
                                                }
                                            }
                                            if (!hasMatched && annotationsContainNumber(method.getAnnotations(), finalTargetValue)) hasMatched = true;
                                            if (!hasMatched) {
                                                for (MethodParameter parameter : method.getParameters()) {
                                                    if (annotationsContainNumber(parameter.getAnnotations(), finalTargetValue)) {
                                                        hasMatched = true;
                                                        break;
                                                    }
                                                }
                                            }
                                            if (hasMatched) break;
                                        }
                                        if (!hasMatched) {
                                            for (Field field : classDef.getFields()) {
                                                if (containsNumericValue(field.getInitialValue(), finalTargetValue)
                                                        || annotationsContainNumber(field.getAnnotations(), finalTargetValue)) {
                                                    hasMatched = true;
                                                    break;
                                                }
                                            }
                                        }
                                        if (!hasMatched) hasMatched = annotationsContainNumber(classDef.getAnnotations(), finalTargetValue);

                                        if (hasMatched) {
                                            try {
                                                smali = generateSmali(classDef);
                                                String[] lines = smali.split("\n");
                                                String pattern = highlightQuery;
                                                for (int i = 0; i < lines.length; i++) {
                                                    String line = lines[i];
                                                    if (line.toLowerCase(Locale.ROOT).contains(pattern)) {
                                                        int idx = line.toLowerCase(Locale.ROOT).indexOf(pattern);
                                                        char next = (idx + pattern.length() < line.length()) ? line.charAt(idx + pattern.length()) : ' ';
                                                        if (Character.isLetterOrDigit(next))
                                                            continue;
                                                        if (foundCount.incrementAndGet() >= 250000) {
                                    stopSearchWithLimit();
                                                            return;
                                                        }
                                                        TreeNode snippet = new TreeNode(line.trim(), className, 0, false);
                                                        snippet.setSnippet(true);
                                                        snippet.setLineNumber(i);
                                                        preHighlightSnippet(snippet, highlightQuery);
                                                        snippets.add(snippet);
                                                        match = true;
                                                    }
                                                }
                                            } catch (Exception ignored) {
                                            }
                                        }
                                        break;
                                }

                                if (match) {
                                    TreeNode classNode = new TreeNode(className.substring(className.lastIndexOf('/') + 1), className, 0, false);
                                    if (!snippets.isEmpty()) {
                                        classNode.setChildren(snippets);
                                        classNode.setExpanded(true);
                                    }
                                    results.add(classNode);
                                }
                                updateProgress(processedCount.incrementAndGet(), finalTotal);
                            }
                        });
                    }

                    executor.shutdown();
                    try {
                        executor.awaitTermination(1, TimeUnit.HOURS);
                    } catch (InterruptedException ignored) {
                    }

                    if (isFinalized.compareAndSet(false, true)) {
                        finalizeResults(results);
                    }
                }
            }, "dex-editor-search");
            searchThread.start();
        }

        private void finalizeResults(List<TreeNode> results) {
            if (viewDestroyed || Thread.currentThread().isInterrupted()) return;
            // Sort and build tree in background
            List<TreeNode> tree;
            if (results.isEmpty()) {
                tree = new ArrayList<>();
            } else {
                // To avoid ConcurrentModificationException if a thread is still finishing
                List<TreeNode> snapshot;
                synchronized (results) {
                    snapshot = new ArrayList<>(results);
                }
                
                snapshot.sort(new Comparator<TreeNode>() {
                    @Override
                    public int compare(TreeNode n1, TreeNode n2) {
                        return n1.getFullName().compareTo(n2.getFullName());
                    }
                });

                tree = buildTreeStructure(snapshot);
                tree.sort(new Comparator<TreeNode>() {
                    @Override
                    public int compare(TreeNode n1, TreeNode n2) {
                        if (n1.isDirectory() != n2.isDirectory()) return n1.isDirectory() ? -1 : 1;
                        return n1.getName().compareTo(n2.getName());
                    }
                });
            }

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (viewDestroyed) return;
                    onPostExecute(tree);
                }
            });
        }

        void cancelForViewDestroyed() {
            viewDestroyed = true;
            isStopped = true;
            isFinalized.set(true);
            synchronized (this) {
                warningShown = false;
                notifyAll();
            }
            if (executor != null) executor.shutdownNow();
            Thread search = searchThread;
            if (search != null) search.interrupt();
            Thread finalizer = finalizeThread;
            if (finalizer != null) finalizer.interrupt();
            AlertProgress dialog = progressDialog;
            progressDialog = null;
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        }

        private void stopSearchWithLimit() {
            if (!isStopped) {
                isStopped = true;
                if (executor != null) executor.shutdownNow();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        AlertProgress dialog = progressDialog;
                        progressDialog = null;
                        if (dialog != null && dialog.isShowing()) dialog.dismiss();
                        DexEditorActivity activity = activityRef.get();
                        if (!viewDestroyed && activity != null && !activity.isDestroyed()) {
                            SketchwareUtil.showMessage(activity, "The number of search results exceeds the limit; the search has been stopped");
                        }
                    }
                });
            }
        }

        private void updateProgress(int processed, int total) {
            long now = System.currentTimeMillis();
            if (now - lastProgressUpdateTime > 100 || processed == total || isStopped) {
                lastProgressUpdateTime = now;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (progressDialog != null && progressDialog.isShowing()) {
                            progressDialog.setMax(total);
                            progressDialog.setProgress(processed);
                            progressDialog.setMessage("Found: " + foundCount.get());
                        }
                    }
                });
            }
        }

        private boolean checkMatchInRange(String text, int start, int end) {
            if (isRegex) return compiledPattern != null && compiledPattern.matcher(text.substring(start, end)).find();
            boolean ignoreCase = !matchCase;
            if (exactlyMatch) return (end - start == query.length()) && text.regionMatches(ignoreCase, start, query, 0, query.length());
            for (int i = start; i <= end - query.length(); i++)
                if (text.regionMatches(ignoreCase, i, query, 0, query.length())) return true;
            return false;
        }

        private boolean checkMatch(String text) {
            return compiledSearchMatcher.matches(text);
        }

        private String generateSmaliOptimized(ClassDef classDef) throws Exception {
            StringBuilder sb = BUFFER_THREAD_LOCAL.get();
            if (sb == null) return "";
            sb.setLength(0);

            // Consistent with ClassTree.getSmaliByType header
            String dexFileName = "unknown.dex";
            if (classTree != null) {
                dexFileName = classTree.findDexFileNameForClass(classDef);
            }
            sb.append("# ").append(dexFileName).append("\n\n");

            java.io.Writer writer = new java.io.Writer() {
                @Override public void write(@NonNull char[] c, int o, int l) { sb.append(c, o, l); }
                @Override public void flush() {}
                @Override public void close() {}
            };

            BaksmaliWriter bw = new BaksmaliWriter(writer);
            BaksmaliOptions options = OPTIONS_THREAD_LOCAL.get();
            if (options == null) options = new BaksmaliOptions();

            new com.android.tools.smali.baksmali.Adaptors.ClassDefinition(options, classDef).writeTo(bw);
            bw.close();
            return sb.toString();
        }

        private String generateSmali(ClassDef classDef) throws Exception {
            if (classTree != null) {
                String type = classDef.getType();
                String typeKey = type.substring(1, type.length() - 1);
                String pending = classTree.getPendingSmali(typeKey);
                if (pending != null) {
                    // Ensure header consistency even for pending smali
                    if (pending.trim().startsWith("# ")) {
                        pending = pending.replaceFirst("(?s)^#.*?\\n\\n", "");
                    }
                    String dexFileName = classTree.findDexFileNameForClass(classDef);
                    return "# " + dexFileName + "\n\n" + pending;
                }
            }
            return generateSmaliOptimized(classDef);
        }

        private String generateSmaliSafe(ClassDef classDef) {
            try {
                return generateSmali(classDef);
            } catch (Exception e) {
                return "";
            }
        }

        private int countLinesBefore(String text, int index) {
            int count = 0;
            for (int i = 0; i < index && i < text.length(); i++)
                if (text.charAt(i) == '\n') count++;
            return count;
        }

        // get the line of the target text
        private int findLineOfText(String smali, String searchText) {
            if (smali == null || searchText == null || smali.isEmpty()) return -1;
            int pos = matchCase ? smali.indexOf(searchText) : smali.toLowerCase(Locale.ROOT).indexOf(searchText.toLowerCase(Locale.ROOT));
            if (pos == -1) return -1;
            return countLinesBefore(smali, pos);
        }

        private boolean searchInAnnotations(java.util.Set<? extends com.android.tools.smali.dexlib2.iface.Annotation> annotations, String className, List<TreeNode> snippets, String smali, ClassDef classDef) {
            if (annotations == null) return false;
            boolean matched = false;
            for (com.android.tools.smali.dexlib2.iface.Annotation annotation : annotations) {
                for (com.android.tools.smali.dexlib2.iface.AnnotationElement element : annotation.getElements()) {
                    if (collectAnnotationMatches(element.getName(), element.getValue(), className, snippets, smali, classDef))
                        matched = true;
                }
            }
            return matched;
        }

        private boolean collectAnnotationMatches(String name, com.android.tools.smali.dexlib2.iface.value.EncodedValue value, String className, List<TreeNode> snippets, String smali, ClassDef classDef) {
            boolean matched = false;
            if (value instanceof com.android.tools.smali.dexlib2.iface.value.StringEncodedValue) {
                String str = ((com.android.tools.smali.dexlib2.iface.value.StringEncodedValue) value).getValue();
                if (checkMatch(str)) {
                    if (foundCount.incrementAndGet() >= 250000) return true;
                    String currentSmali = smali;
                    if (currentSmali == null) currentSmali = generateSmaliSafe(classDef);
                    int lineIdx = findLineOfText(currentSmali, "\"" + str + "\"");
                    TreeNode snippet = new TreeNode(name + " = \"" + str + "\"", className, 0, false);
                    snippet.setSnippet(true);
                    snippet.setLineNumber(lineIdx != -1 ? lineIdx : 0);
                    preHighlightSnippet(snippet, highlightQuery);
                    snippets.add(snippet);
                    matched = true;
                }
            } else if (value instanceof com.android.tools.smali.dexlib2.iface.value.AnnotationEncodedValue) {
                for (com.android.tools.smali.dexlib2.iface.AnnotationElement element : ((com.android.tools.smali.dexlib2.iface.value.AnnotationEncodedValue) value).getElements()) {
                    if (collectAnnotationMatches(element.getName(), element.getValue(), className, snippets, smali, classDef))
                        matched = true;
                }
            } else if (value instanceof com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue) {
                for (com.android.tools.smali.dexlib2.iface.value.EncodedValue subValue : ((com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue) value).getValue()) {
                    if (collectAnnotationMatches(name, subValue, className, snippets, smali, classDef))
                        matched = true;
                }
            }
            return matched;
        }

        private void preHighlightSnippet(TreeNode node, String query) {
            if (query == null || query.isEmpty()) return;
            String text = node.getName();
            String lowerText = text.toLowerCase(Locale.ROOT);
            String lowerQuery = query.toLowerCase(Locale.ROOT);
            int firstMatch = lowerText.indexOf(lowerQuery);

            if (firstMatch != -1) {
                // Priority to highlighted part: center it in the snippet
                int contextBefore = 40;
                int contextAfter = 60;
                int startLimit = Math.max(0, firstMatch - contextBefore);
                int endLimit = Math.min(text.length(), firstMatch + query.length() + contextAfter);

                String prefix = (startLimit > 0) ? "..." : "";
                String suffix = (endLimit < text.length()) ? "..." : "";
                String displayText = prefix + text.substring(startLimit, endLimit).replace("\n", " ").replace("\r", " ") + suffix;
                
                SpannableString spannable = new SpannableString(displayText);
                String lowerDisplay = displayText.toLowerCase(Locale.ROOT);
                int s = 0;
                while ((s = lowerDisplay.indexOf(lowerQuery, s)) != -1) {
                    spannable.setSpan(new BackgroundColorSpan(0xFFB3E5FC), s, s + query.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s += query.length();
                }
                node.setCachedSpannedName(spannable);
            } else {
                String cleanText = text.replace("\n", " ").replace("\r", " ");
                node.setCachedSpannedName(cleanText);
            }
        }

        private boolean annotationsContainNumber(Set<? extends Annotation> annotations, long target) {
            if (annotations == null) return false;
            for (Annotation annotation : annotations) {
                for (AnnotationElement element : annotation.getElements()) {
                    if (containsNumericValue(element.getValue(), target)) return true;
                }
            }
            return false;
        }

        private boolean containsNumericValue(EncodedValue value, long target) {
            if (value instanceof IntEncodedValue) return ((IntEncodedValue) value).getValue() == target;
            if (value instanceof LongEncodedValue) return ((LongEncodedValue) value).getValue() == target;
            if (value instanceof com.android.tools.smali.dexlib2.iface.value.ByteEncodedValue)
                return ((com.android.tools.smali.dexlib2.iface.value.ByteEncodedValue) value).getValue() == target;
            if (value instanceof com.android.tools.smali.dexlib2.iface.value.ShortEncodedValue)
                return ((com.android.tools.smali.dexlib2.iface.value.ShortEncodedValue) value).getValue() == target;
            if (value instanceof com.android.tools.smali.dexlib2.iface.value.CharEncodedValue)
                return ((com.android.tools.smali.dexlib2.iface.value.CharEncodedValue) value).getValue() == target;
            if (value instanceof ArrayEncodedValue) {
                for (EncodedValue nested : ((ArrayEncodedValue) value).getValue()) {
                    if (containsNumericValue(nested, target)) return true;
                }
            } else if (value instanceof AnnotationEncodedValue) {
                for (AnnotationElement element : ((AnnotationEncodedValue) value).getElements()) {
                    if (containsNumericValue(element.getValue(), target)) return true;
                }
            }
            return false;
        }

        private void showWarningDialog() {
            SearchFragment fragment = fragmentRef.get();
            if (viewDestroyed || fragment == null || fragment.getView() == null) return;
            new MaterialAlertDialogBuilder(fragment.requireContext())
                    .setTitle("Warning")
                    .setMessage("1000+ results found so far. Are you sure you wish to continue?")
                    .setPositiveButton("CONTINUE", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            synchronized (SearchTask.this) {
                                hasConfirmedLargeSearch = true;
                                warningShown = false;
                                SearchTask.this.notifyAll();
                            }
                        }
                    })
                    .setNegativeButton("STOP", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            isStopped = true;
                            synchronized (SearchTask.this) {
                                warningShown = false;
                                SearchTask.this.notifyAll();
                            }
                        }
                    })
                    .setCancelable(false)
                    .show();
        }

        private void onPostExecute(final List<TreeNode> tree) {
            if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
            progressDialog = null;
            if (viewDestroyed) return;
            SearchFragment fragment = fragmentRef.get();
            if (fragment == null) return;
            fragment.onSearchTaskFinished(this, query, highlightQuery, tree);
        }

        /**
         * Rebuilds the search result list into a tree-like package structure
         * to make it easier for users to navigate results.
         */
        private List<TreeNode> buildTreeStructure(List<TreeNode> classNodes) {
            Map<String, TreeNode> packageMap = new HashMap<>();
            List<TreeNode> roots = new ArrayList<>();
            for (TreeNode classNode : classNodes) {
                String fullName = classNode.getFullName();
                String pkgName = fullName.contains("/") ? fullName.substring(0, fullName.lastIndexOf('/')) : "";
                if (pkgName.isEmpty()) {
                    roots.add(classNode);
                } else {
                    TreeNode pkgNode = packageMap.get(pkgName);
                    if (pkgNode == null) {
                        pkgNode = new TreeNode(pkgName.replace('/', '.'), pkgName, 0, true);
                        pkgNode.setExpanded(true);
                        packageMap.put(pkgName, pkgNode);
                        roots.add(pkgNode);
                    }
                    pkgNode.addChild(classNode);
                }
            }
            for (TreeNode root : roots) sortChildrenRecursive(root);
            return roots;
        }

        
        private void sortChildrenRecursive(TreeNode node) {
            if (node.isDirectory()) {
                node.getChildren().sort(new Comparator<TreeNode>() {
                    @Override
                    public int compare(TreeNode n1, TreeNode n2) {
                        if (n1.isDirectory() != n2.isDirectory()) return n1.isDirectory() ? -1 : 1;
                        return n1.getName().compareTo(n2.getName());
                    }
                });
                for (TreeNode child : node.getChildren()) sortChildrenRecursive(child);
            }
        }
    }
