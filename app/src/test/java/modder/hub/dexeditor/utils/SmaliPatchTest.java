package modder.hub.dexeditor.utils;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.tools.smali.smali2.Smali;

import modder.hub.dexeditor.smali.Smali2Java;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SmaliPatchTest {
    @Test
    public void literalReplacementDoesNotInterpretRegexReplacementSyntax() {
        SmaliTextReplacer.Rule rule = SmaliTextReplacer.compile("old", "$1\\path", false, true, false);
        SmaliTextReplacer.Result result = rule.apply("const-string v0, \"old\"");

        assertEquals("const-string v0, \"$1\\path\"", result.getText());
        assertEquals(1, result.getMatches());
    }

    @Test
    public void regexReplacementExpandsCaptureGroups() {
        SmaliTextReplacer.Rule rule = SmaliTextReplacer.compile("v([0-9]+)", "p$1", true, true, false);
        SmaliTextReplacer.Result result = rule.apply("move v1, v12");

        assertEquals("move p1, p12", result.getText());
        assertEquals(2, result.getMatches());
    }

    @Test
    public void stringTargetLeavesUnquotedSmaliAlone() {
        SmaliTextReplacer.Rule rule = SmaliTextReplacer.compile("old", "new", false, true, true);
        SmaliTextReplacer.Result result = rule.apply("# old\nconst-string v0, \"old\"");

        assertEquals("# old\nconst-string v0, \"new\"", result.getText());
        assertEquals(1, result.getMatches());
    }

    @Test
    public void stringTargetDoesNotTreatQuotedCommentTextAsALiteral() {
        SmaliTextReplacer.Rule rule = SmaliTextReplacer.compile("old", "new", false, true, true);
        String input = "# const-string v0, \"old\"\nconst-string v0, \"old\"";

        SmaliTextReplacer.Result result = rule.apply(input);

        assertEquals("# const-string v0, \"old\"\nconst-string v0, \"new\"", result.getText());
        assertEquals(1, result.getMatches());
    }

    @Test
    public void literalBatchIgnoresQuotedCommentText() {
        String input = "# const-string v0, \"old\"\nconst-string v0, \"old\"";

        SmaliTextReplacer.Result result = SmaliTextReplacer.transformStringLiterals(input,
                literal -> literal.equals("old") ? "new" : literal);

        assertEquals("# const-string v0, \"old\"\nconst-string v0, \"new\"", result.getText());
        assertEquals(1, result.getMatches());
    }

    @Test
    public void patchFileRequiresExactExpectedMatchCount() throws Exception {
        String json = "{\"format\":\"dex-editor-patch\",\"version\":1,\"name\":\"test\",\"rules\":[{" +
                "\"id\":\"one\",\"target\":\"smali\",\"mode\":\"literal\",\"matchCase\":true," +
                "\"find\":\"old\",\"replace\":\"new\",\"expectedMatches\":1}]}";
        SmaliPatchEngine.Document document = SmaliPatchEngine.read(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals("test", document.name);
        assertEquals(1, document.rules.size());
        assertEquals(Integer.valueOf(1), document.rules.get(0).expectedMatches);
    }

    @Test
    public void patchReaderRejectsMalformedEncodingAndOversizedFiles() {
        assertThrows(IOException.class, () -> SmaliPatchEngine.read(
                new ByteArrayInputStream(new byte[] {(byte) 0xc3, (byte) 0x28})));
        assertThrows(IOException.class, () -> SmaliPatchEngine.read(
                new ByteArrayInputStream(new byte[1024 * 1024 + 1])));
        assertThrows(IOException.class, () -> readPatchJson("{"));
        assertThrows(IOException.class, () -> readPatchJson(""));
    }

    @Test
    public void patchReaderRejectsSchemaErrorsAndUnsafeEmptyAllowList() {
        String validRule = "\"id\":\"r1\",\"target\":\"smali\",\"mode\":\"literal\","
                + "\"matchCase\":true,\"find\":\"old\",\"replace\":\"new\",\"expectedMatches\":1";
        assertThrows(IOException.class, () -> readPatchJson("{\"format\":\"wrong\",\"version\":1,\"rules\":[{" + validRule + "}]}"));
        assertThrows(IOException.class, () -> readPatchJson("{\"format\":\"dex-editor-patch\",\"version\":3,\"rules\":[{" + validRule + "}]}"));
        assertThrows(IOException.class, () -> readPatchJson("{\"format\":\"dex-editor-patch\",\"version\":1,\"rules\":[]}"));
        assertThrows(IOException.class, () -> readPatchJson("{\"format\":\"dex-editor-patch\",\"version\":1,\"rules\":[{" + validRule.replace("\"expectedMatches\":1", "\"expectedMatches\":0") + "}]}"));
        assertThrows(IOException.class, () -> readPatchJson("{\"format\":\"dex-editor-patch\",\"version\":1,\"rules\":[{" + validRule.replace("\"id\":\"r1\"", "\"id\":\"bad id\"") + "}]}"));
        assertThrows(IOException.class, () -> readPatchJson("{\"format\":\"dex-editor-patch\",\"version\":1,\"rules\":[{" + validRule + ",\"classes\":[]}]}"));
        assertThrows(IOException.class, () -> readPatchJson("{\"format\":\"dex-editor-patch\",\"version\":1,\"rules\":[{" + validRule + ",\"classes\":[\"Ltest.Bad;\"]}]}"));
        assertThrows(IOException.class, () -> readPatchJson("{\"format\":\"dex-editor-patch\",\"version\":1,\"rules\":[{\"id\":\"bad-regex\",\"target\":\"smali\",\"mode\":\"regex\",\"matchCase\":true,\"find\":\"[\",\"replace\":\"x\",\"expectedMatches\":1}]}"));
        assertThrows(IOException.class, () -> readPatchJson("{\"format\":\"dex-editor-patch\",\"version\":1,\"rules\":[{" + validRule + ",\"methodSelector\":{\"pattern\":\"value\"}}]}"));
        assertThrows(IOException.class, () -> readPatchJson("{\"format\":\"dex-editor-patch\",\"version\":1,\"rules\":[{" + validRule + "},{" + validRule + "}]}"));
    }

    @Test
    public void regexCannotMatchZeroWidthText() {
        SmaliTextReplacer.Rule rule = SmaliTextReplacer.compile("a*", "x", true, true, false);

        assertThrows(IllegalArgumentException.class, () -> rule.apply("bbb"));
    }

    @Test
    public void patchPlanStagesThenCommitsAssembledClasses() throws Exception {
        File root = Files.createTempDirectory("smali-patch-test").toFile();
        try {
            ClassTree classTree = openFixture(root);
            ClassDef classDef = classTree.getClassDef("test/PatchTarget");
            String original = classTree.getPureSmaliFromClassDef(classDef);
            long originalRevision = classTree.getEditRevision();
            SmaliPatchEngine.Plan plan = SmaliPatchEngine.prepare(
                    classTree, readStringPatch(1), Collections.<String, String>emptyMap(), null);

            assertEquals(1, plan.getChangedClassCount());
            assertEquals(classTree.getSmaliByType(classDef).replace("old", "new"),
                    plan.getUpdatedSmali().get("test/PatchTarget"));
            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
            assertEquals(originalRevision, classTree.getEditRevision());
            assertFalse(classTree.hasUnsavedChanges());

            plan.commit(classTree);

            String committed = classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget"));
            assertEquals(original.replace("old", "new"), committed);
            assertTrue(classTree.getEditRevision() > originalRevision);
            assertTrue(classTree.hasUnsavedChanges());
            assertThrows(IllegalStateException.class, () -> plan.commit(classTree));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void patchCountMismatchLeavesClassTreeUntouched() throws Exception {
        File root = Files.createTempDirectory("smali-patch-mismatch").toFile();
        try {
            ClassTree classTree = openFixture(root);
            String original = classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget"));
            long originalRevision = classTree.getEditRevision();

            assertThrows(SmaliPatchEngine.PatchException.class, () ->
                    SmaliPatchEngine.prepare(classTree, readStringPatch(2), Collections.<String, String>emptyMap(), null));

            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
            assertEquals(originalRevision, classTree.getEditRevision());
            assertFalse(classTree.hasUnsavedChanges());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void stalePlanCannotOverwriteAChangedClassTree() throws Exception {
        File root = Files.createTempDirectory("smali-patch-stale-plan").toFile();
        try {
            ClassTree classTree = openFixture(root);
            ClassDef originalDef = classTree.getClassDef("test/PatchTarget");
            String original = classTree.getPureSmaliFromClassDef(originalDef);
            long originalRevision = classTree.getEditRevision();
            SmaliPatchEngine.Plan plan = SmaliPatchEngine.prepare(
                    classTree, readStringPatch(1), Collections.<String, String>emptyMap(), null);

            classTree.saveClassDef(originalDef);

            assertThrows(IllegalStateException.class, () -> plan.commit(classTree));
            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
            assertTrue(classTree.getEditRevision() > originalRevision);
            assertTrue(classTree.hasUnsavedChanges());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void stagedPatchUsesOpenEditorTextAndCommitMatchesPreview() throws Exception {
        File root = Files.createTempDirectory("smali-patch-open-tab").toFile();
        try {
            ClassTree classTree = openFixture(root);
            String original = classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget"));
            String editorText = original.replace("\"old\"", "\"editor\"");
            Map<String, String> openTabs = Collections.singletonMap("test/PatchTarget", editorText);
            String json = "{\"format\":\"dex-editor-patch\",\"version\":1,\"rules\":["
                    + "{\"id\":\"replace\",\"target\":\"strings\",\"mode\":\"literal\",\"matchCase\":true,"
                    + "\"classes\":[\"Ltest/PatchTarget;\"],\"find\":\"editor\",\"replace\":\"new\",\"expectedMatches\":1}]}";
            SmaliPatchEngine.Plan plan = SmaliPatchEngine.prepare(
                    classTree, readPatchJson(json), openTabs, null);
            String preview = editorText.replace("\"editor\"", "\"new\"");

            assertEquals(preview, plan.getUpdatedSmali().get("test/PatchTarget"));
            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
            plan.commit(classTree);
            assertEquals(preview, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void laterRuleMismatchDoesNotCommitEarlierRuleChanges() throws Exception {
        File root = Files.createTempDirectory("smali-patch-later-rule-failure").toFile();
        try {
            ClassTree classTree = openFixture(root);
            String original = classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget"));
            long revision = classTree.getEditRevision();
            String json = "{\"format\":\"dex-editor-patch\",\"version\":1,\"rules\":["
                    + "{\"id\":\"first\",\"target\":\"strings\",\"mode\":\"literal\",\"matchCase\":true,"
                    + "\"find\":\"old\",\"replace\":\"middle\",\"expectedMatches\":1},"
                    + "{\"id\":\"second\",\"target\":\"strings\",\"mode\":\"literal\",\"matchCase\":true,"
                    + "\"find\":\"middle\",\"replace\":\"new\",\"expectedMatches\":2}]}";

            assertThrows(SmaliPatchEngine.PatchException.class, () -> SmaliPatchEngine.prepare(
                    classTree, readPatchJson(json), Collections.<String, String>emptyMap(), null));
            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
            assertEquals(revision, classTree.getEditRevision());
            assertFalse(classTree.hasUnsavedChanges());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void classRenameIsRejectedWithoutChangingTheTree() throws Exception {
        File root = Files.createTempDirectory("smali-patch-class-rename").toFile();
        try {
            ClassTree classTree = openFixture(root);
            String original = classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget"));
            long revision = classTree.getEditRevision();
            String json = "{\"format\":\"dex-editor-patch\",\"version\":1,\"rules\":["
                    + "{\"id\":\"rename\",\"target\":\"smali\",\"mode\":\"literal\",\"matchCase\":true,"
                    + "\"find\":\"PatchTarget\",\"replace\":\"Renamed\",\"expectedMatches\":1}]}";

            assertThrows(SmaliPatchEngine.PatchException.class, () -> SmaliPatchEngine.prepare(
                    classTree, readPatchJson(json), Collections.<String, String>emptyMap(), null));
            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
            assertEquals(revision, classTree.getEditRevision());
            assertFalse(classTree.hasUnsavedChanges());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void malformedSelectedMethodFromOpenTabFailsWithoutCommit() throws Exception {
        File root = Files.createTempDirectory("smali-patch-malformed-method").toFile();
        try {
            ClassTree classTree = openTwoMethodFixture(root);
            String original = classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget"));
            String malformed = original.replace(".end method", "");
            assertFalse(malformed.contains(".end method"));
            assertTrue(malformed.contains(".method "));
            long revision = classTree.getEditRevision();

            assertThrows(SmaliPatchEngine.PatchException.class, () -> SmaliPatchEngine.prepare(
                    classTree, readMethodScopedPatch("PatchTarget", "value", false, false),
                    Collections.singletonMap("test/PatchTarget", malformed), null));
            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
            assertEquals(revision, classTree.getEditRevision());
            assertFalse(classTree.hasUnsavedChanges());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void cancelledPatchPreviewDoesNotCommit() throws Exception {
        File root = Files.createTempDirectory("smali-patch-cancel").toFile();
        try {
            ClassTree classTree = openFixture(root);
            String original = classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget"));
            long revision = classTree.getEditRevision();
            SmaliPatchEngine.ProgressListener cancelled = new SmaliPatchEngine.ProgressListener() {
                @Override public boolean isCancelled() { return true; }
                @Override public void onProgress(int processed, int total, String currentClass) {}
            };

            assertThrows(SmaliPatchEngine.CancelledException.class, () -> SmaliPatchEngine.prepare(
                    classTree, readStringPatch(1), Collections.<String, String>emptyMap(), cancelled));
            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
            assertEquals(revision, classTree.getEditRevision());
            assertFalse(classTree.hasUnsavedChanges());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void classDefCommitValidatesAllTypesBeforeChangingAnyClass() throws Exception {
        File root = Files.createTempDirectory("smali-patch-transaction").toFile();
        try {
            ClassTree classTree = openFixture(root);
            ClassDef originalDef = classTree.getClassDef("test/PatchTarget");
            String original = classTree.getPureSmaliFromClassDef(originalDef);
            ClassDef replacement = Smali.assemble(original.replace("\"old\"", "\"new\""), new SmaliOptions(), 35);
            ClassDef missingClass = Smali.assemble(
                    ".class public Ltest/Missing;\n.super Ljava/lang/Object;\n",
                    new SmaliOptions(), 35);

            assertThrows(IllegalArgumentException.class, () -> classTree.commitClassDefs(
                    classTree.getEditRevision(), Arrays.asList(replacement, missingClass)));

            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
            assertTrue(!classTree.containsClass("test/Missing"));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void directClassSaveRejectsDescriptorChangesWithoutMutatingTheTree() throws Exception {
        File root = Files.createTempDirectory("smali-class-save").toFile();
        try {
            ClassTree classTree = openFixture(root);
            ClassTree.EditSnapshot before = classTree.snapshotEditState();
            String original = classTree.getPureSmaliFromClassDef(
                    before.getClassDefs().get("test/PatchTarget"));
            ClassDef renamed = Smali.assemble(
                    original.replace("Ltest/PatchTarget;", "Ltest/Renamed;"), new SmaliOptions(), 35);

            assertThrows(IllegalArgumentException.class, () -> classTree.saveClassDef(renamed));

            assertEquals(before.getRevision(), classTree.getEditRevision());
            assertTrue(classTree.containsClass("test/PatchTarget"));
            assertFalse(classTree.containsClass("test/Renamed"));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void classTreeOwnsDirtyStateAcrossEditsAndReset() throws Exception {
        File root = Files.createTempDirectory("smali-dirty-state").toFile();
        try {
            ClassTree classTree = openFixture(root);
            String current = classTree.getPureSmaliFromClassDef(
                    classTree.getClassDef("test/PatchTarget"));

            assertFalse(classTree.hasUnsavedChanges());
            classTree.saveSmali("test/PatchTarget", current);
            assertTrue(classTree.hasUnsavedChanges());

            classTree.clearUnsavedChanges();
            assertFalse(classTree.hasUnsavedChanges());
            classTree.removeClass("test/PatchTarget");
            assertTrue(classTree.hasUnsavedChanges());

            classTree.clearAll();
            assertFalse(classTree.hasUnsavedChanges());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void deletedClassesSurviveWorkspaceReopenAndRecoverFromBackup() throws Exception {
        File root = Files.createTempDirectory("smali-deleted-class-journal").toFile();
        try {
            ClassTree classTree = openFixture(root);
            classTree.removeClass("test/PatchTarget");

            File journal = new File(new File(root, "cache"), "deletedclasses.json");
            File backup = new File(journal.getAbsolutePath() + ".bak");
            assertTrue(journal.isFile());
            assertTrue(journal.renameTo(backup));

            ClassTree reopened = new ClassTree(
                    Collections.singletonList(new File(root, "classes.dex").getAbsolutePath()),
                    new File(root, "cache").getAbsolutePath());
            assertFalse(reopened.containsClass("test/PatchTarget"));
            assertTrue(journal.isFile());
            assertFalse(backup.exists());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void corruptDeletedClassJournalFailsWorkspaceLoad() throws Exception {
        File root = Files.createTempDirectory("smali-corrupt-deleted-class-journal").toFile();
        try {
            openFixture(root).removeClass("test/PatchTarget");
            File journal = new File(new File(root, "cache"), "deletedclasses.json");
            Files.write(journal.toPath(), "{ invalid".getBytes(StandardCharsets.UTF_8));

            assertThrows(IOException.class, () -> new ClassTree(
                    Collections.singletonList(new File(root, "classes.dex").getAbsolutePath()),
                    new File(root, "cache").getAbsolutePath()));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void failedDeletedClassJournalWriteLeavesClassTreeUntouched() throws Exception {
        File root = Files.createTempDirectory("smali-failed-deleted-class-journal").toFile();
        try {
            ClassTree classTree = openFixture(root);
            File journalTemp = new File(new File(root, "cache"), "deletedclasses.json.tmp");
            assertTrue(journalTemp.mkdir());

            assertThrows(IOException.class, () -> classTree.removeClass("test/PatchTarget"));

            assertTrue(classTree.containsClass("test/PatchTarget"));
            assertFalse(classTree.hasUnsavedChanges());
            assertFalse(new File(new File(root, "cache"), "deletedclasses.json").exists());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void deletingPackagePrefixRemovesContainedClasses() throws Exception {
        File root = Files.createTempDirectory("smali-delete-package-prefix").toFile();
        try {
            ClassTree classTree = openFixture(root);
            classTree.removeClass("test/");

            assertFalse(classTree.containsClass("test/PatchTarget"));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void editSnapshotDoesNotExposeTheMutableClassMap() throws Exception {
        File root = Files.createTempDirectory("smali-edit-snapshot").toFile();
        try {
            ClassTree classTree = openFixture(root);
            ClassTree.EditSnapshot snapshot = classTree.snapshotEditState();

            assertEquals(1, snapshot.getClassDefs().size());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.getClassDefs().clear());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void failedLaterDexCompilationLeavesEveryOutputUntouched() throws Exception {
        File root = Files.createTempDirectory("smali-multidex-transaction").toFile();
        try {
            File firstDex = new File(root, "classes.dex");
            File secondDex = new File(root, "classes2.dex");
            writeDex(firstDex, "Ltest/First;", "first");
            writeDex(secondDex, "Ltest/Second;", "second");
            byte[] firstBefore = Files.readAllBytes(firstDex.toPath());
            byte[] secondBefore = Files.readAllBytes(secondDex.toPath());
            ClassTree classTree = new ClassTree(Arrays.asList(
                    firstDex.getAbsolutePath(), secondDex.getAbsolutePath()),
                    new File(root, "cache").getAbsolutePath());

            classTree.saveSmali("test/First", classTree.getPureSmaliFromClassDef(
                    classTree.getClassDef("test/First")).replace("first", "updated"));
            classTree.saveSmali("test/Second", ".class public Ltest/Second;\n"
                    + ".super Ljava/lang/Object;\n"
                    + ".method public static value()Ljava/lang/String;\n"
                    + "    .registers 1\n"
                    + "    const-string v0, \"broken\"\n");

            assertThrows(Exception.class, () -> classTree.saveAllDexFiles(new ClassTree.DexSaveProgress() {
                @Override public void onProgress(int progress, int total) {}
                @Override public void onMessage(String message) {}
                @Override public void onTitle(String title) {}
            }));

            assertArrayEquals(firstBefore, Files.readAllBytes(firstDex.toPath()));
            assertArrayEquals(secondBefore, Files.readAllBytes(secondDex.toPath()));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void guiReplacementTransactionDoesNotCommitWhenOneClassIsInvalid() throws Exception {
        File root = Files.createTempDirectory("smali-gui-replace-transaction").toFile();
        try {
            ClassTree classTree = openReplacementFailureFixture(root);
            ClassTree.EditSnapshot snapshot = classTree.snapshotEditState();
            String original = classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget"));
            SmaliTextReplacer.Rule rule = SmaliTextReplacer.compile("old", "new", false, true, false);

            SmaliReplacementTransaction.Result result = SmaliReplacementTransaction.applyAndCommit(
                    classTree, snapshot.getRevision(), snapshot.getClassDefs(), Collections.<String, String>emptyMap(),
                    null, rule, () -> false, (processed, total) -> {});

            assertTrue(!result.getErrors().isEmpty());
            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void cancelledGuiReplacementTransactionDoesNotCommit() throws Exception {
        File root = Files.createTempDirectory("smali-gui-replace-cancel").toFile();
        try {
            ClassTree classTree = openFixture(root);
            ClassTree.EditSnapshot snapshot = classTree.snapshotEditState();
            String original = classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget"));
            SmaliTextReplacer.Rule rule = SmaliTextReplacer.compile("old", "new", false, true, false);

            SmaliReplacementTransaction.Result result = SmaliReplacementTransaction.applyAndCommit(
                    classTree, snapshot.getRevision(), snapshot.getClassDefs(), Collections.<String, String>emptyMap(),
                    null, rule, () -> true, (processed, total) -> {});

            assertTrue(result.isCancelled());
            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void cancellationAfterGuiReplacementCommitReportsSuccess() throws Exception {
        File root = Files.createTempDirectory("smali-gui-replace-cancel-after-commit").toFile();
        try {
            ClassTree classTree = openFixture(root);
            ClassTree.EditSnapshot snapshot = classTree.snapshotEditState();
            String original = classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget"));
            SmaliTextReplacer.Rule rule = SmaliTextReplacer.compile("old", "new", false, true, false);

            SmaliReplacementTransaction.Result result = SmaliReplacementTransaction.applyAndCommit(
                    classTree, snapshot.getRevision(), snapshot.getClassDefs(), Collections.<String, String>emptyMap(),
                    null, rule, () -> classTree.getEditRevision() != snapshot.getRevision(),
                    (processed, total) -> {});

            assertTrue(!result.isCancelled());
            assertEquals(original.replace("old", "new"),
                    classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void literalBatchInvalidClassLeavesEveryClassUnchanged() throws Exception {
        File root = Files.createTempDirectory("smali-literal-batch-failure").toFile();
        try {
            ClassTree classTree = openReplacementFailureFixture(root);
            ClassTree.EditSnapshot snapshot = classTree.snapshotEditState();
            Map<String, String> exact = new HashMap<>();
            exact.put("old", "bad\"quote");
            String original = classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget"));

            SmaliLiteralReplacementTransaction.Result result =
                    SmaliLiteralReplacementTransaction.applyAndCommit(classTree, snapshot,
                            Collections.<String, String>emptyMap(), exact, null, () -> false, null);

            assertTrue(!result.getErrors().isEmpty());
            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void cancelledLiteralBatchDoesNotCommit() throws Exception {
        File root = Files.createTempDirectory("smali-literal-batch-cancel").toFile();
        try {
            ClassTree classTree = openFixture(root);
            ClassTree.EditSnapshot snapshot = classTree.snapshotEditState();
            Map<String, String> exact = new HashMap<>();
            exact.put("old", "new");
            String original = classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget"));

            SmaliLiteralReplacementTransaction.Result result =
                    SmaliLiteralReplacementTransaction.applyAndCommit(classTree, snapshot,
                            Collections.<String, String>emptyMap(), exact, null, () -> true, null);

            assertTrue(result.isCancelled());
            assertEquals(original, classTree.getPureSmaliFromClassDef(classTree.getClassDef("test/PatchTarget")));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void versionTwoSelectorsUseGuiDefaultsAndLimitReplacementToMatchingMethods() throws Exception {
        File root = Files.createTempDirectory("smali-patch-method-scope").toFile();
        try {
            ClassTree classTree = openTwoMethodFixture(root);
            SmaliPatchEngine.Plan plan = SmaliPatchEngine.prepare(classTree, readMethodScopedPatch(
                    "patch", "VALUE", null, null), Collections.<String, String>emptyMap(), null);
            String updated = plan.getUpdatedSmali().get("test/PatchTarget");

            assertEquals(1, countOccurrences(updated, "\"new\""));
            assertEquals(1, countOccurrences(updated, "\"old\""));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void versionTwoSelectorsSupportExactCaseSensitiveSignatures() throws Exception {
        File root = Files.createTempDirectory("smali-patch-exact-selector").toFile();
        try {
            ClassTree classTree = openTwoMethodFixture(root);
            SmaliPatchEngine.Plan plan = SmaliPatchEngine.prepare(classTree, readMethodScopedPatch(
                    "Ltest/PatchTarget;", "value()Ljava/lang/String;", true, true),
                    Collections.<String, String>emptyMap(), null);

            assertEquals(1, countOccurrences(plan.getUpdatedSmali().get("test/PatchTarget"), "\"new\""));
            assertThrows(SmaliPatchEngine.PatchException.class, () -> SmaliPatchEngine.prepare(
                    classTree, readMethodScopedPatch("ltest/patchTarget;", "value()Ljava/lang/String;", true, true),
                    Collections.<String, String>emptyMap(), null));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void versionTwoSelectorsSupportJavaRegexSearch() throws Exception {
        File root = Files.createTempDirectory("smali-patch-regex-selector").toFile();
        try {
            ClassTree classTree = openTwoMethodFixture(root);
            String json = "{\"format\":\"dex-editor-patch\",\"version\":2,\"name\":\"regex scoped test\",\"rules\":["
                    + "{\"id\":\"scoped\",\"target\":\"strings\",\"mode\":\"literal\",\"matchCase\":true,"
                    + "\"classSelector\":{\"field\":\"fullName\",\"pattern\":\"^test/Patch.*$\",\"mode\":\"regex\"},"
                    + "\"methodSelector\":{\"field\":\"signature\",\"pattern\":\"^value.*Ljava/lang/String;$\",\"mode\":\"regex\"},"
                    + "\"find\":\"old\",\"replace\":\"new\",\"expectedMatches\":1}]}";
            SmaliPatchEngine.Document document = SmaliPatchEngine.read(
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            SmaliPatchEngine.Plan plan = SmaliPatchEngine.prepare(
                    classTree, document, Collections.<String, String>emptyMap(), null);

            assertEquals(1, countOccurrences(plan.getUpdatedSmali().get("test/PatchTarget"), "\"new\""));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void jadxSmaliDecompilationStillWorks() throws Exception {
        String smali = ".class public Ltest/DecompileTarget;\n"
                + ".super Ljava/lang/Object;\n"
                + ".method public static value()Ljava/lang/String;\n"
                + "    .registers 1\n"
                + "    const-string v0, \"ok\"\n"
                + "    return-object v0\n"
                + ".end method\n";

        assertTrue(Smali2Java.translate(smali, 35).contains("class DecompileTarget"));
    }

    private static ClassTree openFixture(File root) throws Exception {
        File dexFile = new File(root, "classes.dex");
        String smali = ".class public Ltest/PatchTarget;\n"
                + ".super Ljava/lang/Object;\n"
                + ".method public static value()Ljava/lang/String;\n"
                + "    .registers 1\n"
                + "    const-string v0, \"old\"\n"
                + "    return-object v0\n"
                + ".end method\n";
        ClassDef classDef = Smali.assemble(smali, new SmaliOptions(), 35);
        DexPool pool = new DexPool(Opcodes.forDexVersion(35));
        pool.internClass(classDef);
        FileDataStore store = new FileDataStore(dexFile);
        try {
            pool.writeTo(store);
        } finally {
            store.close();
        }
        return new ClassTree(Collections.singletonList(dexFile.getAbsolutePath()), new File(root, "cache").getAbsolutePath());
    }

    private static void writeDex(File dexFile, String descriptor, String value) throws Exception {
        String smali = ".class public " + descriptor + "\n"
                + ".super Ljava/lang/Object;\n"
                + ".method public static value()Ljava/lang/String;\n"
                + "    .registers 1\n"
                + "    const-string v0, \"" + value + "\"\n"
                + "    return-object v0\n"
                + ".end method\n";
        ClassDef classDef = Smali.assemble(smali, new SmaliOptions(), 35);
        DexPool pool = new DexPool(Opcodes.forDexVersion(35));
        pool.internClass(classDef);
        FileDataStore store = new FileDataStore(dexFile);
        try {
            pool.writeTo(store);
        } finally {
            store.close();
        }
    }

    private static ClassTree openTwoMethodFixture(File root) throws Exception {
        File dexFile = new File(root, "classes.dex");
        String smali = ".class public Ltest/PatchTarget;\n"
                + ".super Ljava/lang/Object;\n"
                + ".method public static value()Ljava/lang/String;\n"
                + "    .registers 1\n"
                + "    const-string v0, \"old\"\n"
                + "    return-object v0\n"
                + ".end method\n"
                + ".method public static other()Ljava/lang/String;\n"
                + "    .registers 1\n"
                + "    const-string v0, \"old\"\n"
                + "    return-object v0\n"
                + ".end method\n";
        ClassDef classDef = Smali.assemble(smali, new SmaliOptions(), 35);
        DexPool pool = new DexPool(Opcodes.forDexVersion(35));
        pool.internClass(classDef);
        FileDataStore store = new FileDataStore(dexFile);
        try {
            pool.writeTo(store);
        } finally {
            store.close();
        }
        return new ClassTree(Collections.singletonList(dexFile.getAbsolutePath()), new File(root, "cache").getAbsolutePath());
    }

    private static ClassTree openReplacementFailureFixture(File root) throws Exception {
        File dexFile = new File(root, "classes.dex");
        String first = ".class public Ltest/PatchTarget;\n"
                + ".super Ljava/lang/Object;\n"
                + ".method public static value()Ljava/lang/String;\n"
                + "    .registers 1\n"
                + "    const-string v0, \"old\"\n"
                + "    return-object v0\n"
                + ".end method\n";
        String second = ".class public Ltest/old;\n.super Ljava/lang/Object;\n";
        DexPool pool = new DexPool(Opcodes.forDexVersion(35));
        pool.internClass(Smali.assemble(first, new SmaliOptions(), 35));
        pool.internClass(Smali.assemble(second, new SmaliOptions(), 35));
        FileDataStore store = new FileDataStore(dexFile);
        try {
            pool.writeTo(store);
        } finally {
            store.close();
        }
        return new ClassTree(Collections.singletonList(dexFile.getAbsolutePath()),
                new File(root, "cache").getAbsolutePath());
    }

    private static SmaliPatchEngine.Document readMethodScopedPatch(String classPattern,
                                                                    String methodPattern,
                                                                    Boolean exactlyMatch,
                                                                    Boolean matchCase) throws IOException {
        String classOptions = (exactlyMatch == null ? "" : "\"exactlyMatch\":" + exactlyMatch + ",")
                + (matchCase == null ? "" : "\"matchCase\":" + matchCase);
        String classOptionsWithComma = classOptions.isEmpty() ? "" : "," + classOptions;
        String methodOptions = classOptions;
        String methodOptionsWithComma = methodOptions.isEmpty() ? "" : "," + methodOptions;
        String json = "{\"format\":\"dex-editor-patch\",\"version\":2,\"name\":\"scoped test\",\"rules\":["
                + "{\"id\":\"scoped\",\"target\":\"strings\",\"mode\":\"literal\",\"matchCase\":true,"
                + "\"classSelector\":{\"field\":\"descriptor\",\"pattern\":\"" + classPattern + "\""
                + classOptionsWithComma + "},"
                + "\"methodSelector\":{\"field\":\"signature\",\"pattern\":\"" + methodPattern + "\""
                + methodOptionsWithComma + "},"
                + "\"find\":\"old\",\"replace\":\"new\",\"expectedMatches\":1}]}";
        return SmaliPatchEngine.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static int countOccurrences(String text, String value) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(value, from)) >= 0) {
            count++;
            from += value.length();
        }
        return count;
    }

    private static SmaliPatchEngine.Document readStringPatch(int expectedMatches) throws IOException {
        String json = "{\"format\":\"dex-editor-patch\",\"version\":1,\"name\":\"test patch\",\"rules\":["
                + "{\"id\":\"replace\",\"target\":\"strings\",\"mode\":\"literal\",\"matchCase\":true,"
                + "\"classes\":[\"Ltest/PatchTarget;\"],\"find\":\"old\",\"replace\":\"new\","
                + "\"expectedMatches\":" + expectedMatches + "}]}";
        return SmaliPatchEngine.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static SmaliPatchEngine.Document readPatchJson(String json) throws IOException {
        return SmaliPatchEngine.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
