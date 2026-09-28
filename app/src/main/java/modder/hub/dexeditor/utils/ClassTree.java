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


 *     Please contact Krushna by email mt.modder.hub@gmail.com if you need
 *     additional information or have any questions
 */

package modder.hub.dexeditor.utils;

import android.annotation.SuppressLint;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;

import androidx.annotation.NonNull;

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.tools.smali.smali2.Smali;
import com.android.tools.smali.dexlib2.util.DexUtil;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import modder.hub.dexeditor.model.TreeNode;

public class ClassTree {
	
	/*
	Author @developer-krushna
	Orginally replicate from Flying-Yu AE Manager on github
	*/

    /*
     * There are some major advancement and enhancement are made by me. From loading of multi dex to
     faster batch class deletion and even really  fatser dex compilatin .

     * There is so many usefull tricks for advancing your smali assembly and disaembly knowledge
     * Here I have made significant improvement in loading/ compiling/ editing of dexes
     */


    private String DELETED_CLASSES_JSON;
    private final String workDir;
    private File recoveryJsonFile;
    private File sourceManifestFile;
    private final Map<String, String> recoveryFingerprints = new HashMap<>();
    private final Map<String, String> recoverySmaliByType = new HashMap<>();
    private volatile boolean changed;
    private final Map<String, java.util.HashSet<String>> editedClassMap = new HashMap<>();
    private final Map<String, String> pendingSmaliMap = new ConcurrentHashMap<>();
    private HashMap<String, ClassDef> classMap;
    private final List<ClassDef> classDefList = new ArrayList<ClassDef>();
    private final List<String> sourceDexPaths;
    private int dexVersion;
    private final Map<String, List<String>> dexClassMap = new LinkedHashMap<>();

    private Map<String, java.util.HashSet<String>> deletedClassJson = new HashMap<>();
    private final Map<String, String> typeToDexMap = new HashMap<>();
    private final Map<String, Integer> dexVersionByFile = new HashMap<>();
    private final Map<String, String> sourceFingerprintByFile = new HashMap<>();
    private final Map<String, Integer> dexVersionByType = new HashMap<>();
    private final Map<String, Integer> classIndexByType = new HashMap<>();
    private long editRevision;

    public static class CompilationOptions {
        public String dexVersion = "Keep the same";
        public boolean removeAllDebug = false;
        public boolean removeDebugSource = false;
        public boolean removeDebugLine = false;
        public boolean removeDebugParam = false;
        public boolean removeDebugPrologue = false;
        public boolean removeDebugLocal = false;
    }

    private CompilationOptions compilationOptions = new CompilationOptions();

    public void setCompilationOptions(CompilationOptions options) {
        this.compilationOptions = options;
    }

    public ClassTree(List<String> mPaths, String cacheDir) throws Exception {
        this.sourceDexPaths = new ArrayList<>(mPaths);
        this.workDir = cacheDir;
        validateSourceDexPaths();
        DexFilePublisher.recoverPendingPublication(sourceDexPaths);
        initPaths();
        validateRecoverySources();
        loadDeletedClasses();
        initMultiDex();
        verifyLoadedRecoverySources();
        loadStagedEdits();
    }

    private void validateSourceDexPaths() throws IOException {
        Set<String> fileNames = new HashSet<>();
        String commonParent = null;
        for (String path : sourceDexPaths) {
            File source = new File(path).getCanonicalFile();
            String name = source.getName();
            if (!fileNames.add(name)) {
                throw new IOException("Selected DEX files have the same name: " + name);
            }
            String parent = source.getParent();
            if (commonParent == null) commonParent = parent;
            else if (!Objects.equals(commonParent, parent)) {
                throw new IOException("Selected DEX files must be in the same folder so compiled files are saved beside their sources.");
            }
        }
    }

    private void initPaths() {
        File dir = new File(workDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        DELETED_CLASSES_JSON = new File(dir, "deletedclasses.json").getAbsolutePath();
        recoveryJsonFile = new File(dir, "staged-smali.json");
        sourceManifestFile = new File(dir, "source-fingerprints.json");
    }

    private void validateRecoverySources() throws IOException {
        restoreAtomicBackup(recoveryJsonFile);
        restoreAtomicBackup(sourceManifestFile);
        Map<String, String> current = new LinkedHashMap<>();
        for (String path : sourceDexPaths) {
            File source = new File(path).getCanonicalFile();
            current.put(source.getAbsolutePath(), sha256(source));
        }

        boolean hasSavedState = recoveryJsonFile.exists() || new File(DELETED_CLASSES_JSON).exists()
                || new File(DELETED_CLASSES_JSON + ".bak").exists();
        if (hasSavedState) {
            if (!sourceManifestFile.exists()) {
                archiveStaleRecoveryFiles();
            } else {
                SourceManifest saved;
                try (InputStream input = new FileInputStream(sourceManifestFile)) {
                    saved = new Gson().fromJson(new java.io.InputStreamReader(input, StandardCharsets.UTF_8),
                            SourceManifest.class);
                } catch (RuntimeException e) {
                    throw new IOException("Unable to read DEX recovery manifest.", e);
                }
                if (saved == null || saved.version != 1 || !current.equals(saved.fingerprints)) {
                    archiveStaleRecoveryFiles();
                }
            }
        }
        setRecoveryFingerprints(current);
        writeJsonAtomically(sourceManifestFile, new SourceManifest(current));
    }

    private void archiveStaleRecoveryFiles() throws IOException {
        long stamp = System.currentTimeMillis();
        File[] candidates = {recoveryJsonFile, new File(DELETED_CLASSES_JSON),
                new File(DELETED_CLASSES_JSON + ".bak")};
        for (File candidate : candidates) {
            if (candidate.exists() && !candidate.renameTo(new File(candidate.getPath() + ".stale." + stamp))) {
                throw new IOException("Could not preserve recovery data for a different DEX version: " + candidate.getName());
            }
        }
    }

    private void loadStagedEdits() throws IOException {
        if (!recoveryJsonFile.exists()) return;
        final Map<String, String> staged;
        try (InputStream input = new FileInputStream(recoveryJsonFile)) {
            staged = new Gson().fromJson(new java.io.InputStreamReader(input, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() {}.getType());
        } catch (RuntimeException e) {
            throw new IOException("Unable to read staged Smali recovery data.", e);
        }
        if (staged == null) throw new IOException("Staged Smali recovery data is empty.");
        synchronized (classMap) {
            for (Map.Entry<String, String> entry : staged.entrySet()) {
                String type = entry.getKey();
                String smali = entry.getValue();
                if (type == null || smali == null) throw new IOException("Staged Smali recovery data has an invalid entry.");
                if (!classMap.containsKey(type)) continue; // Deleted classes remain governed by deletedclasses.json.
                pendingSmaliMap.put(type, smali);
                recoverySmaliByType.put(type, smali);
                recordEditedClass(type);
                changed = true;
                editRevision++;
            }
        }
    }

    private void verifyLoadedRecoverySources() throws IOException {
        Map<String, String> loaded = new LinkedHashMap<>();
        for (String path : sourceDexPaths) {
            File source = new File(path).getCanonicalFile();
            String fingerprint = sourceFingerprintByFile.get(source.getName());
            loaded.put(source.getAbsolutePath(), fingerprint);
        }
        if (!recoveryFingerprints.equals(loaded)) {
            throw new IOException("A source DEX changed while the workspace was loading. Reopen the DEX to avoid applying stale recovery data.");
        }
    }

    private static final class SourceManifest {
        int version = 1;
        final Map<String, String> fingerprints;

        SourceManifest(Map<String, String> fingerprints) {
            this.fingerprints = new LinkedHashMap<>(fingerprints);
        }
    }

    private static void writeJsonAtomically(File target, Object value) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create recovery directory.");
        }
        File temp = new File(target.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp);
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            new Gson().toJson(value, writer);
            writer.flush();
            output.getFD().sync();
        } catch (IOException e) {
            temp.delete();
            throw e;
        }
        try {
            Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException | RuntimeException e) {
            replaceWithBackup(temp, target, e);
        }
    }

    private static void replaceWithBackup(File temp, File target, Exception cause) throws IOException {
        File backup = new File(target.getPath() + ".bak");
        if (backup.exists() && !backup.delete()) {
            temp.delete();
            throw new IOException("Could not clear the old recovery backup.", cause);
        }
        boolean movedTarget = target.exists();
        if (movedTarget && !target.renameTo(backup)) {
            temp.delete();
            throw new IOException("Could not back up the previous recovery data.", cause);
        }
        if (!temp.renameTo(target)) {
            if (movedTarget) backup.renameTo(target);
            temp.delete();
            throw new IOException("Could not publish recovery data.", cause);
        }
        if (backup.exists()) backup.delete();
    }

    private static void restoreAtomicBackup(File target) throws IOException {
        File backup = new File(target.getPath() + ".bak");
        if (!backup.exists()) return;
        if (target.exists()) {
            if (!backup.delete()) throw new IOException("Could not clear a completed recovery backup.");
        } else if (!backup.renameTo(target)) {
            throw new IOException("Could not restore the previous recovery data.");
        }
    }

    private void persistStagedEdits(Map<String, String> staged) throws IOException {
        if (staged.isEmpty()) {
            if (recoveryJsonFile.exists() && !recoveryJsonFile.delete()) {
                throw new IOException("Could not clear staged Smali recovery data.");
            }
        } else {
            writeJsonAtomically(recoveryJsonFile, staged);
        }
        recoverySmaliByType.clear();
        recoverySmaliByType.putAll(staged);
    }

    private void setRecoveryFingerprints(Map<String, String> fingerprints) {
        synchronized (recoveryFingerprints) {
            recoveryFingerprints.clear();
            recoveryFingerprints.putAll(fingerprints);
        }
    }

    private void initMultiDex() throws Exception {
        classDefList.clear();
        typeToDexMap.clear();
        dexVersionByFile.clear();
        sourceFingerprintByFile.clear();
        dexVersionByType.clear();
        classIndexByType.clear();
        dexClassMap.clear();
        if (classMap == null) {
            classMap = new HashMap<>();
        } else {
            classMap.clear();
        }

        Set<String> seenDescriptors = new HashSet<>();
        for (int i = 0; i < sourceDexPaths.size(); i++) {
            String path = sourceDexPaths.get(i);
            File sourceFile = new File(path);
            String sourceFingerprint = sha256(sourceFile);
            int verifyDexHeader;
            DexBackedDexFile file;
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(path))) {
                verifyDexHeader = DexUtil.verifyDexHeader(input);
                file = DexBackedDexFile.fromInputStream(Opcodes.forDexVersion(verifyDexHeader), input);
            }
            if (!sourceFingerprint.equals(sha256(sourceFile))) {
                throw new IOException("DEX file changed while it was being loaded: " + sourceFile);
            }
            this.dexVersion = verifyDexHeader;
            dexVersionByFile.put(fileNameForPath(path), verifyDexHeader);
            sourceFingerprintByFile.put(fileNameForPath(path), sourceFingerprint);

            List<String> classNames = new ArrayList<>();
            String fileName = new File(path).getName();
            for (ClassDef classDef : file.getClasses()) {
                classDefList.add(classDef);
                String type = classDef.getType();
                if (!seenDescriptors.add(type)) {
                    throw new IOException("Class descriptor appears in more than one selected DEX: " + type);
                }
                typeToDexMap.put(type, fileNameForPath(path));
                dexVersionByType.put(type, verifyDexHeader);
                classIndexByType.put(typeName(type), classDefList.size() - 1);
                
                String typeName = type.substring(1, type.length() - 1);
                if (!isClassDeleted(typeName)) {
                    classMap.put(typeName, classDef);
                }
                classNames.add(type);
            }
            dexClassMap.put(fileName, classNames);
        }

    }

    // loading the deleted classes from JSON list
    private void loadDeletedClasses() throws IOException {
        File file = new File(DELETED_CLASSES_JSON);
        File backupFile = new File(DELETED_CLASSES_JSON + ".bak");
        if (!file.exists() && backupFile.exists() && !backupFile.renameTo(file)) {
            throw new IOException("Unable to restore deleted-class journal backup");
        }
        if (!file.exists()) {
            deletedClassJson = new HashMap<>();
            return;
        }

        try {
            String json = new String(read(DELETED_CLASSES_JSON), StandardCharsets.UTF_8);
            Map<String, List<String>> loaded = new Gson().fromJson(
                    json, new TypeToken<Map<String, List<String>>>() {}.getType());
            if (loaded == null) throw new IOException("Deleted-class journal is empty");

            Map<String, HashSet<String>> restored = new HashMap<>();
            for (Entry<String, List<String>> entry : loaded.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IOException("Deleted-class journal contains an invalid entry");
                }
                restored.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            deletedClassJson = restored;
        } catch (RuntimeException e) {
            throw new IOException("Unable to read deleted-class journal", e);
        }
    }

    // saving deleted classes as JSON so that will be excluded during the dex compilation
    private void saveDeletedClasses() throws IOException {
        File file = new File(DELETED_CLASSES_JSON);
        File tempFile = new File(DELETED_CLASSES_JSON + ".tmp");
        File backupFile = new File(DELETED_CLASSES_JSON + ".bak");
        try {
            // Convert HashSet to List for JSON
            Map<String, List<String>> toSave = new HashMap<>();
            for (Entry<String, HashSet<String>> entry : deletedClassJson.entrySet()) {
                toSave.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            try (FileOutputStream output = new FileOutputStream(tempFile);
                 OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                new Gson().toJson(toSave, writer);
                writer.flush();
                output.getFD().sync();
            }
            if (backupFile.exists() && !backupFile.delete()) {
                throw new IOException("Unable to clear old deleted-class journal backup");
            }
            if (file.exists() && !file.renameTo(backupFile)) {
                throw new IOException("Unable to back up deleted-class journal");
            }
            if (!tempFile.renameTo(file)) {
                if (backupFile.exists()) backupFile.renameTo(file);
                throw new IOException("Unable to publish deleted-class journal");
            }
            if (backupFile.exists()) backupFile.delete();
        } catch (IOException e) {
            tempFile.delete();
            if (!file.exists() && backupFile.exists()) backupFile.renameTo(file);
            throw e;
        }
    }

    // checking if the class is beigh deleted usefull in case of searching
    private boolean isClassDeleted(String type) {
        String fileName = typeToDexMap.get("L" + type + ";");
        if (fileName != null) {
            java.util.HashSet<String> deleted = deletedClassJson.get(fileName);
            return deleted != null && deleted.contains(type);
        }
        return false;
    }

    // removal of batch or single classes from tree node
    public void removeClasses(List<String> classNames) throws IOException {
        if (classNames == null || classNames.isEmpty()) return;
        synchronized (classMap) {
            removeClassesLocked(classNames);
            changed = true;
        }
    }

    private void removeClassesLocked(List<String> classNames) throws IOException {
        List<String> folderPrefixes = new ArrayList<String>();
        Set<String> individualClasses = new HashSet<String>();

        for (String name : classNames) {
            if (name.endsWith("/")) {
                folderPrefixes.add(name);
            } else {
                individualClasses.add(name);
            }
        }

        List<String> removedClassNames = new ArrayList<>();
        for (String className : classMap.keySet()) {
            if (shouldRemove(className, individualClasses, folderPrefixes)) {
                removedClassNames.add(className);
            }
        }
        for (String className : removedClassNames) recordRemovedClass(className);
        try {
            saveDeletedClasses();
        } catch (IOException e) {
            for (String className : removedClassNames) unrecordRemovedClass(className);
            throw e;
        }

        // Optimization: Use a single pass over the map when folders are involved (O(N))
        if (!folderPrefixes.isEmpty()) {
            synchronized (classMap) {
                Iterator<Map.Entry<String, ClassDef>> it = classMap.entrySet().iterator();
                while (it.hasNext()) {
                    String className = it.next().getKey();
                    if (shouldRemove(className, individualClasses, folderPrefixes)) {
                        it.remove();
                        pendingSmaliMap.remove(className);
                        unrecordEditedClass(className);
                    }
                }
            }

            // Cleanup for classes that might only exist in edited maps
            for (HashSet<String> set : editedClassMap.values()) {
                Iterator<String> setIt = set.iterator();
                while (setIt.hasNext()) {
                    if (shouldRemove(setIt.next(), individualClasses, folderPrefixes)) {
                        setIt.remove();
                    }
                }
            }

            Iterator<String> pendingIt = pendingSmaliMap.keySet().iterator();
            while (pendingIt.hasNext()) {
                if (shouldRemove(pendingIt.next(), individualClasses, folderPrefixes)) {
                    pendingIt.remove();
                }
            }
        } else {
            // High-performance path for individual class removals (O(1) lookups)
            synchronized (classMap) {
                for (String name : individualClasses) {
                    classMap.remove(name);
                    pendingSmaliMap.remove(name);
                    unrecordEditedClass(name);
                }
            }
        }

        synchronized (classMap) {
            editRevision++;
        }
        Map<String, String> remainingRecovery = new HashMap<>(recoverySmaliByType);
        for (String type : new ArrayList<>(remainingRecovery.keySet())) {
            if (shouldRemove(type, individualClasses, folderPrefixes)) remainingRecovery.remove(type);
        }
        persistStagedEdits(remainingRecovery);
    }

    private boolean shouldRemove(String className, Set<String> individualClasses, List<String> folderPrefixes) {
        if (individualClasses.contains(className)) return true;
        for (String prefix : folderPrefixes) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    // remove single class only
    public void removeClass(String className) throws IOException {
        removeClasses(ImmutableList.of(className));
    }

    // the remove classes will be recorded during batch and preserve in json
    private void recordRemovedClass(String type) {
        String fileName = typeToDexMap.get("L" + type + ";");
        if (fileName != null) {
            HashSet<String> deleted = deletedClassJson.get(fileName);
            if (deleted == null) {
                deleted = new HashSet<>();
                deletedClassJson.put(fileName, deleted);
            }
            deleted.add(type);
        }
    }

    private void unrecordRemovedClass(String type) {
        String fileName = typeToDexMap.get("L" + type + ";");
        if (fileName == null) return;
        HashSet<String> deleted = deletedClassJson.get(fileName);
        if (deleted == null) return;
        deleted.remove(type);
        if (deleted.isEmpty()) deletedClassJson.remove(fileName);
    }

    // save smali according to the class and its content
    // it's just a mapping way to preserve  smali data in memeory, but it's not a good way it may cause self kill app like situation and mt manager never do like this
    public void saveSmali(String type, String smali) {
        synchronized (classMap) {
            Map<String, String> updatedRecovery = new HashMap<>(recoverySmaliByType);
            updatedRecovery.put(type, smali);
            try {
                persistStagedEdits(updatedRecovery);
            } catch (IOException e) {
                throw new IllegalStateException("Could not preserve the unsaved Smali edit.", e);
            }
            pendingSmaliMap.put(type, smali);
            recordEditedClass(type);
            editRevision++;
            changed = true;
        }
    }

    // save class def to the main node o the dex and record the changes classes
    public void saveClassDef(ClassDef classDef) {
        commitClassDefs(getEditRevision(), Collections.singletonList(classDef));
    }

    private void saveClassDefLocked(ClassDef classDef) {
        String type = classDef.getType().substring(1, classDef.getType().length() - 1);
        pendingSmaliMap.remove(type);
        classMap.put(type, classDef);
        Integer classIndex = classIndexByType.get(type);
        synchronized (classDefList) {
            if (classIndex != null && classIndex >= 0 && classIndex < classDefList.size()) {
                classDefList.set(classIndex, classDef);
            }
        }
        recordEditedClass(type);
        editRevision++;
        changed = true;
    }

    public boolean hasUnsavedChanges() {
        return changed;
    }

    public void clearUnsavedChanges() {
        changed = false;
    }

    public long getEditRevision() {
        HashMap<String, ClassDef> currentClassMap = classMap;
        if (currentClassMap == null) throw new IllegalStateException("No DEX is open.");
        synchronized (currentClassMap) {
            if (classMap != currentClassMap) throw new IllegalStateException("No DEX is open.");
            return editRevision;
        }
    }

    public EditSnapshot snapshotEditState() {
        HashMap<String, ClassDef> currentClassMap = classMap;
        if (currentClassMap == null) throw new IllegalStateException("No DEX is open.");
        synchronized (currentClassMap) {
            if (classMap != currentClassMap) throw new IllegalStateException("No DEX is open.");
            return new EditSnapshot(editRevision, currentClassMap);
        }
    }

    public ClassDef getClassDef(String type) {
        HashMap<String, ClassDef> currentClassMap = classMap;
        if (currentClassMap == null) return null;
        synchronized (currentClassMap) {
            return classMap == currentClassMap ? currentClassMap.get(type) : null;
        }
    }

    public boolean containsClass(String type) {
        HashMap<String, ClassDef> currentClassMap = classMap;
        if (currentClassMap == null) return false;
        synchronized (currentClassMap) {
            return classMap == currentClassMap && currentClassMap.containsKey(type);
        }
    }

    public List<ClassDef> getClassDefsSnapshot() {
        return new ArrayList<>(snapshotEditState().getClassDefs().values());
    }

    public static final class EditSnapshot {
        private final long revision;
        private final Map<String, ClassDef> classDefs;

        private EditSnapshot(long revision, Map<String, ClassDef> classDefs) {
            this.revision = revision;
            this.classDefs = Collections.unmodifiableMap(new HashMap<>(classDefs));
        }

        public long getRevision() { return revision; }
        public Map<String, ClassDef> getClassDefs() { return classDefs; }
    }

    public void commitClassDefs(long expectedRevision, List<ClassDef> classDefs) {
        if (classDefs == null) throw new IllegalArgumentException("Replacement classes cannot be null.");
        HashMap<String, ClassDef> currentClassMap = classMap;
        if (currentClassMap == null) throw new IllegalStateException("No DEX is open.");
        synchronized (currentClassMap) {
            if (classMap != currentClassMap) throw new IllegalStateException("No DEX is open.");
            if (editRevision != expectedRevision) {
                throw new IllegalStateException("The DEX changed after patch preview. Preview the patch again before applying it.");
            }
            Set<String> replacementTypes = new HashSet<>();
            for (ClassDef classDef : classDefs) {
                if (classDef == null) throw new IllegalArgumentException("A replacement class cannot be null.");
                String descriptor = classDef.getType();
                if (descriptor == null || !descriptor.startsWith("L") || !descriptor.endsWith(";")) {
                    throw new IllegalArgumentException("Invalid replacement class descriptor: " + descriptor);
                }
                String type = descriptor.substring(1, descriptor.length() - 1);
                if (!currentClassMap.containsKey(type)) {
                    throw new IllegalArgumentException("Cannot replace class that is not in the open DEX: " + descriptor);
                }
                if (!replacementTypes.add(type)) {
                    throw new IllegalArgumentException("Replacement contains duplicate class: " + descriptor);
                }
            }
            Map<String, String> updatedRecovery = new HashMap<>(recoverySmaliByType);
            try {
                for (ClassDef classDef : classDefs) {
                    String type = typeName(classDef.getType());
                    updatedRecovery.put(type, getPureSmaliFromClassDef(classDef));
                }
                persistStagedEdits(updatedRecovery);
            } catch (Exception e) {
                throw new IllegalStateException("Could not preserve the staged DEX edits.", e);
            }
            for (ClassDef classDef : classDefs) saveClassDefLocked(classDef);
        }
    }

    public String getSmaliByType(ClassDef classDef) throws Exception {
        String type = classDef.getType();
        String typeKey = type.substring(1, type.length() - 1);
        String smali;
        
        // Check if we have unsaved smali in memory
        // MEMEORY WORKS are really not good especially for android. MT Manager never do this it save all as files and retrive them from
        // files only and there will be IO exception and even background kill
        if (pendingSmaliMap.containsKey(typeKey)) {
            smali = pendingSmaliMap.get(typeKey);
        } else {
            smali = getPureSmaliFromClassDef(classDef);
        }

        // We strip any existing header to avoid "# classes.dex" appearing multiple times
        // This usually happens during search and replace operations
        if (smali != null && smali.trim().startsWith("# ")) {
            smali = smali.replaceFirst("(?s)^#.*?\\n\\n", "");
        }

        String dexFileName = findDexFileNameForClass(classDef);
        return "# " + dexFileName + "\n\n" + smali;
    }

    // This returns the smali code without any informative headers
    public String getPureSmaliFromClassDef(ClassDef classDef) throws Exception {
        StringWriter stringWriter = new StringWriter();
        BaksmaliWriter baksmaliWriter = new BaksmaliWriter(stringWriter);
        new ClassDefinition(new BaksmaliOptions(), classDef).writeTo(baksmaliWriter);
        baksmaliWriter.close();
        return stringWriter.toString();
    }

    public String findDexFileNameForClass(ClassDef classDef) {
        String fileName = typeToDexMap.get(classDef.getType());
        if (fileName != null) {
            return fileName;
        }
        return "unknown.dex";
    }

    public int getDexVersionForClass(String className) {
        Integer version = dexVersionByType.get("L" + className + ";");
        return version == null ? dexVersion : version;
    }

    private static String typeName(String descriptor) {
        return descriptor.substring(1, descriptor.length() - 1);
    }

    private static String fileNameForPath(String path) {
        return new File(path).getName();
    }

    // record the edited classes
    private void recordEditedClass(String type) {
        String fileName = typeToDexMap.get("L" + type + ";");
        if (fileName != null) {
            java.util.HashSet<String> edited = editedClassMap.get(fileName);
            if (edited == null) {
                edited = new java.util.HashSet<>();
                editedClassMap.put(fileName, edited);
            }
            edited.add(type);
        }
    }

    private void unrecordEditedClass(String type) {
        String fileName = typeToDexMap.get("L" + type + ";");
        if (fileName != null) {
            HashSet<String> edited = editedClassMap.get(fileName);
            if (edited != null) {
                edited.remove(type);
            }
        }
    }

    // save all loaded dexes
    @SuppressLint("SdCardPath")
    public void saveAllDexFiles(DexSaveProgress dexSaveProgress) throws Exception {
        if (dexClassMap.isEmpty()) {
            changed = false;
            return;
        }

        List<PreparedDex> preparedDexes = new ArrayList<>();
        boolean forceCompileAll = requiresFullDexRewrite();
        try {
            int current = 1;
            int total = dexClassMap.size();
            for (Entry<String, List<String>> entry : dexClassMap.entrySet()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DEX save cancelled.");
                String fileName = entry.getKey();
                dexSaveProgress.onTitle(fileName + " (" + current + "/" + total + ")");
                PreparedDex prepared = prepareDexFile(fileName, entry.getValue(), forceCompileAll, dexSaveProgress);
                if (prepared != null) preparedDexes.add(prepared);
                current++;
            }

            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DEX save cancelled.");
            if (!preparedDexes.isEmpty()) {
                verifySourceDexesUnchanged();
                DexFilePublisher.publishAtomically(preparedDexes);
                commitPreparedDexes(preparedDexes);
            }
            synchronized (classMap) {
                pendingSmaliMap.clear();
                editedClassMap.clear();
                deletedClassJson.clear();
                saveDeletedClasses();
                persistStagedEdits(Collections.<String, String>emptyMap());
                Map<String, String> currentSources = new LinkedHashMap<>();
                for (String path : sourceDexPaths) {
                    File source = new File(path).getCanonicalFile();
                    currentSources.put(source.getAbsolutePath(), sha256(source));
                }
                setRecoveryFingerprints(currentSources);
                writeJsonAtomically(sourceManifestFile, new SourceManifest(currentSources));
            }
            changed = false;
        } finally {
            for (PreparedDex prepared : preparedDexes) {
                if (prepared.tempFile.exists()) prepared.tempFile.delete();
            }
        }
    }

    private boolean requiresFullDexRewrite() {
        return compilationOptions.removeAllDebug || compilationOptions.removeDebugSource
                || compilationOptions.removeDebugLine || compilationOptions.removeDebugParam
                || compilationOptions.removeDebugPrologue || compilationOptions.removeDebugLocal
                || !compilationOptions.dexVersion.equals("Keep the same");
    }

    private void verifySourceDexesUnchanged() throws IOException {
        for (String path : sourceDexPaths) {
            String fileName = fileNameForPath(path);
            String expected = sourceFingerprintByFile.get(fileName);
            if (expected == null || !expected.equals(sha256(new File(path)))) {
                throw new IOException("Source DEX changed after it was opened; reload it before saving: " + fileName);
            }
        }
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        char[] hex = new char[64];
        char[] digits = "0123456789abcdef".toCharArray();
        int offset = 0;
        for (byte value : digest.digest()) {
            int unsigned = value & 0xff;
            hex[offset++] = digits[unsigned >>> 4];
            hex[offset++] = digits[unsigned & 0x0f];
        }
        return new String(hex);
    }

    private PreparedDex prepareDexFile(String fileName, List<String> classNames,
                                       boolean forceCompileAll, DexSaveProgress progress) throws Exception {
        if (!forceCompileAll && !deletedClassJson.containsKey(fileName) && !editedClassMap.containsKey(fileName)) {
            return null;
        }

        Integer originalVersion = dexVersionByFile.get(fileName);
        int targetVersion = compilationOptions.dexVersion.equals("Keep the same")
                ? (originalVersion == null ? dexVersion : originalVersion)
                : Integer.parseInt(compilationOptions.dexVersion);
        Set<String> deleted = deletedClassJson.get(fileName);
        Map<String, String> pendingForDex = collectPendingSmali(classNames, deleted);
        Map<String, ClassDef> assembledPending = assemblePendingSmali(pendingForDex, targetVersion, fileName, progress);
        DexPool dexPool = new DexPool(Opcodes.forDexVersion(targetVersion));
        internDexClasses(dexPool, classNames, deleted, assembledPending, progress);
        return stageDexFile(fileName, targetVersion, dexPool, assembledPending, pendingForDex, progress);
    }

    private Map<String, String> collectPendingSmali(List<String> classNames, Set<String> deleted) {
        Map<String, String> pendingForDex = new HashMap<>();
        for (String rawType : classNames) {
            String type = typeName(rawType);
            if (deleted != null && deleted.contains(type)) continue;
            String smali = pendingSmaliMap.get(type);
            if (smali != null) pendingForDex.put(type, smali);
        }
        return pendingForDex;
    }

    private Map<String, ClassDef> assemblePendingSmali(Map<String, String> pendingForDex, int targetVersion,
                                                        String fileName, DexSaveProgress progress) throws Exception {
        ConcurrentMap<String, ClassDef> assembledPending = new ConcurrentHashMap<>();
        if (pendingForDex.isEmpty()) return assembledPending;

        AtomicReference<Exception> compileError = new AtomicReference<>();
        // ponytail: cap parallel assembly to bound ClassDef memory; benchmark before raising the ceiling.
        int workerCount = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        for (Map.Entry<String, String> pending : pendingForDex.entrySet()) {
            if (Thread.currentThread().isInterrupted()) {
                executor.shutdownNow();
                throw new InterruptedException("DEX save cancelled.");
            }
            executor.execute(() -> {
                if (compileError.get() != null || Thread.currentThread().isInterrupted()) return;
                try {
                    progress.onMessage("Assembling " + pending.getKey() + "...");
                    ClassDef assembled = Smali.assemble(pending.getValue(), new SmaliOptions(), targetVersion);
                    if (!("L" + pending.getKey() + ";").equals(assembled.getType())) {
                        throw new IllegalArgumentException("Edited class descriptor does not match " + pending.getKey());
                    }
                    assembledPending.put(pending.getKey(), assembled);
                } catch (Exception e) {
                    if ("CANCELLED".equals(e.getMessage()) || e instanceof InterruptedException) {
                        compileError.compareAndSet(null, e);
                    } else {
                        compileError.compareAndSet(null,
                                new Exception("COMPILE_ERROR:" + pending.getKey() + ":" + e.getMessage(), e));
                    }
                }
            });
        }
        executor.shutdown();
        boolean terminated;
        try {
            terminated = executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!terminated) {
            executor.shutdownNow();
            throw new IOException("Timed out assembling edited classes in " + fileName);
        }
        if (compileError.get() != null) throw compileError.get();
        return assembledPending;
    }

    private void internDexClasses(DexPool dexPool, List<String> classNames, Set<String> deleted,
                                  Map<String, ClassDef> assembledPending, DexSaveProgress progress)
            throws InterruptedException {
        int processed = 0;
        for (String rawType : classNames) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DEX save cancelled.");
            String type = typeName(rawType);
            if (deleted == null || !deleted.contains(type)) {
                ClassDef classDef = assembledPending.get(type);
                if (classDef == null) classDef = classMap.get(type);
                if (classDef != null) {
                    if (hasDebugStrippingOptions()) classDef = new DebugInfoStripper(classDef, compilationOptions);
                    dexPool.internClass(classDef);
                }
            }
            processed++;
            if (processed % 100 == 0 || processed == classNames.size()) {
                progress.onMessage("Compiling...");
                progress.onProgress(processed, classNames.size());
            }
        }
    }

    private boolean hasDebugStrippingOptions() {
        return compilationOptions.removeAllDebug || compilationOptions.removeDebugSource
                || compilationOptions.removeDebugLine || compilationOptions.removeDebugParam
                || compilationOptions.removeDebugPrologue || compilationOptions.removeDebugLocal;
    }

    private PreparedDex stageDexFile(String fileName, int targetVersion, DexPool dexPool,
                                     Map<String, ClassDef> assembledPending, Map<String, String> pendingForDex,
                                     DexSaveProgress progress) throws IOException {
        progress.onMessage("Writing file...");
        String outputDir = sourceDexPaths != null && !sourceDexPaths.isEmpty()
                ? new File(sourceDexPaths.get(0)).getParent()
                : Environment.getExternalStorageDirectory().getPath();
        File outputDirectory = new File(outputDir);
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("Cannot create output directory: " + outputDir);
        }
        File outFile = new File(outputDirectory, fileName);
        File tempFile = File.createTempFile(fileName + ".", ".partial", outputDirectory);
        boolean staged = false;
        try {
            FileDataStore store = new FileDataStore(tempFile);
            try {
                dexPool.writeTo(store);
            } finally {
                store.close();
            }
            staged = true;
            return new PreparedDex(fileName, targetVersion, outFile, tempFile, sha256(tempFile),
                    assembledPending, pendingForDex);
        } finally {
            if (!staged && tempFile.exists()) tempFile.delete();
        }
    }

    private void commitPreparedDexes(List<PreparedDex> preparedDexes) {
        synchronized (classMap) {
            synchronized (classDefList) {
                for (PreparedDex prepared : preparedDexes) {
                    for (Map.Entry<String, ClassDef> assembled : prepared.assembledPending.entrySet()) {
                        String type = assembled.getKey();
                        classMap.put(type, assembled.getValue());
                        Integer index = classIndexByType.get(type);
                        if (index != null) classDefList.set(index, assembled.getValue());
                        if (Objects.equals(pendingSmaliMap.get(type), prepared.pendingSmali.get(type))) {
                            pendingSmaliMap.remove(type);
                        }
                        dexVersionByType.put("L" + type + ";", prepared.targetVersion);
                    }
                    if (!prepared.assembledPending.isEmpty()) editRevision++;
                    dexVersionByFile.put(prepared.fileName, prepared.targetVersion);
                    sourceFingerprintByFile.put(prepared.fileName, prepared.fingerprint);
                }
            }
        }
    }

    private static final class PreparedDex extends DexFilePublisher.StagedFile {
        final int targetVersion;
        final String fingerprint;
        final Map<String, ClassDef> assembledPending;
        final Map<String, String> pendingSmali;

        PreparedDex(String fileName, int targetVersion, File outputFile, File tempFile, String fingerprint,
                    Map<String, ClassDef> assembledPending, Map<String, String> pendingSmali) {
            super(fileName, outputFile, tempFile);
            this.targetVersion = targetVersion;
            this.fingerprint = fingerprint;
            this.assembledPending = assembledPending;
            this.pendingSmali = pendingSmali;
        }
    }

    public List<TreeNode> buildFullTree() {
        List<String> sortedKeys;
        synchronized (classMap) {
            sortedKeys = new ArrayList<>(classMap.keySet());
        }
        Collections.sort(sortedKeys);
        return ClassTreeBuilder.build(sortedKeys);
    }

    public boolean hasPendingSmali(String type) { return pendingSmaliMap.containsKey(type); }

    public String getPendingSmali(String type) { return pendingSmaliMap.get(type); }

    public Map<String, String> getRecoveryFingerprints() {
        synchronized (recoveryFingerprints) {
            return Collections.unmodifiableMap(new HashMap<>(recoveryFingerprints));
        }
    }

    public List<TreeNode> buildEditedFullTree() {
        List<String> editedClasses = new ArrayList<>();
        for (java.util.HashSet<String> classes : editedClassMap.values()) {
            editedClasses.addAll(classes);
        }
        Collections.sort(editedClasses);
        return ClassTreeBuilder.build(editedClasses);
    }

    public void clearAll() {
        changed = false;
        if (classMap != null) {
            HashMap<String, ClassDef> currentClassMap = classMap;
            synchronized (currentClassMap) {
                currentClassMap.clear();
                editRevision++;
                if (classMap == currentClassMap) classMap = null;
            }
        } else {
            editRevision++;
        }
        classDefList.clear();
        pendingSmaliMap.clear();
        classIndexByType.clear();
        typeToDexMap.clear();
        dexVersionByFile.clear();
        sourceFingerprintByFile.clear();
        dexVersionByType.clear();
        System.gc();
    }

    public byte[] read(String fileName) throws IOException {
        try (InputStream input = new FileInputStream(fileName);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    public int getOpenedDexVersion() {
        return dexVersion;
    }

    public List<String> getAllStrings() {
        synchronized (classMap) {
            return DexStringCollector.collect(classMap.values());
        }
    }

    public interface DexSaveProgress {
        void onProgress(int progress, int total);

        void onMessage(String name);

        void onTitle(String title);
    }

    // helper classe for debug info Striping

}
