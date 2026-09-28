package modder.hub.dexeditor;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.appcompat.widget.Toolbar;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.smali2.Smali;
import com.android.tools.smali.smali.SmaliOptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

import org.junit.Test;
import org.junit.runner.RunWith;

import modder.hub.dexeditor.activity.DexEditorActivity;
import modder.hub.dexeditor.fragment.EditorFragment;
import modder.hub.dexeditor.utils.ClassTree;
import modder.hub.dexeditor.utils.SmaliPatchEngine;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    @Test
    public void mainScreenLaunches() throws Exception {
        grantAllFilesAccessForTest(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.open_dex));
                assertNotNull(activity.findViewById(R.id.edit_path));
            });
        }
    }

    @Test
    public void editorLoadsNavigatesSavesPatchesAndReopensDexOnDevice() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        grantAllFilesAccessForTest(context);

        File downloadsDirectory = getDownloadsDirectory();
        assertTrue("Could not create the shared Downloads directory",
                downloadsDirectory.isDirectory() || downloadsDirectory.mkdirs());
        File dexFile = File.createTempFile("dex-editor-instrumented-", ".dex", downloadsDirectory);
        try {
            writeFixtureDex(dexFile);
            Intent intent = new Intent(context, DexEditorActivity.class);
            intent.putStringArrayListExtra("SelectedDexFiles",
                    new ArrayList<>(Collections.singletonList(dexFile.getAbsolutePath())));

            try (ActivityScenario<DexEditorActivity> scenario = ActivityScenario.launch(intent)) {
                boolean[] loaded = {false};
                long deadline = SystemClock.uptimeMillis() + 15000;
                while (!loaded[0] && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity(activity -> {
                        ClassTree tree = activity.getClassTree();
                        loaded[0] = tree != null && tree.containsClass("instrumented/SmokeTarget");
                    });
                    if (!loaded[0]) SystemClock.sleep(100);
                }
                assertTrue("DexEditorActivity did not load the fixture DEX", loaded[0]);

                scenario.onActivity(activity -> activity.openClass("instrumented/SmokeTarget"));
                boolean[] editorOpened = {false};
                deadline = SystemClock.uptimeMillis() + 15000;
                while (!editorOpened[0] && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity(activity -> {
                        EditorFragment fragment = activity.getCurrentFragment();
                        editorOpened[0] = fragment != null && fragment.getEditor() != null
                                && fragment.getEditor().getText().toString()
                                        .contains(".class public Linstrumented/SmokeTarget;");
                    });
                    if (!editorOpened[0]) SystemClock.sleep(100);
                }
                assertTrue("Smali editor did not open the loaded class", editorOpened[0]);

                scenario.onActivity(activity -> activity.goTo(
                        "Linstrumented/SmokeTarget;->target()V", "Linstrumented/Caller;"));
                boolean[] methodOpened = {false};
                deadline = SystemClock.uptimeMillis() + 15000;
                while (!methodOpened[0] && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity(activity -> {
                        EditorFragment fragment = activity.getCurrentFragment();
                        if (fragment == null || fragment.getView() == null) return;
                        TextView methodName = fragment.getView().findViewById(R.id.methodName);
                        methodOpened[0] = methodName != null
                                && methodName.getText().toString().contains(": target()");
                    });
                    if (!methodOpened[0]) SystemClock.sleep(100);
                }
                assertTrue("Method navigation did not reach the requested Smali method", methodOpened[0]);

                scenario.onActivity(activity -> {
                    EditorFragment fragment = activity.getCurrentFragment();
                    assertNotNull(fragment);
                    String currentSmali = fragment.getEditor().getText().toString();
                    String editedSmali = currentSmali.replace("target()V", "renamed()V");
                    assertNotEquals("The fixture method was not present in the editor",
                            currentSmali, editedSmali);
                    fragment.getEditor().setText(editedSmali);
                });
                scenario.onActivity(activity -> {
                    Toolbar toolbar = activity.findViewById(R.id._toolbar);
                    assertNotNull(toolbar);
                    MenuItem saveItem = toolbar.getMenu().findItem(R.id.save);
                    assertNotNull("Editor Save action is missing", saveItem);
                    assertTrue("Editor Save action was not handled",
                            activity.onOptionsItemSelected(saveItem));
                });

                boolean[] classSaved = {false};
                deadline = SystemClock.uptimeMillis() + 15000;
                while (!classSaved[0] && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity(activity -> {
                        ClassTree tree = activity.getClassTree();
                        ClassDef classDef = tree == null ? null
                                : tree.getClassDef("instrumented/SmokeTarget");
                        classSaved[0] = containsMethod(classDef, "renamed");
                    });
                    if (!classSaved[0]) SystemClock.sleep(100);
                }
                assertTrue("Saving the edited tab did not update the live DEX model", classSaved[0]);

                ClassTree[] treeToWrite = {null};
                scenario.onActivity(activity -> treeToWrite[0] = activity.getClassTree());
                assertNotNull("The editor lost its DEX model before compilation", treeToWrite[0]);
                ClassTree tree = treeToWrite[0];
                String patchJson = "{\"format\":\"dex-editor-patch\",\"version\":2,"
                        + "\"name\":\"instrumented smoke patch\",\"rules\":[{"
                        + "\"id\":\"rename-method\",\"target\":\"smali\","
                        + "\"mode\":\"literal\",\"matchCase\":true,"
                        + "\"classes\":[\"Linstrumented/SmokeTarget;\"],"
                        + "\"find\":\"renamed()V\",\"replace\":\"patched()V\","
                        + "\"expectedMatches\":1}]}";
                SmaliPatchEngine.Document patch = SmaliPatchEngine.read(new ByteArrayInputStream(
                        patchJson.getBytes(StandardCharsets.UTF_8)));
                SmaliPatchEngine.Plan plan = SmaliPatchEngine.prepare(
                        tree, patch, Collections.emptyMap(), null);
                assertEquals("The patch should change one class", 1, plan.getChangedClassCount());
                plan.commit(tree);
                assertTrue("Applying the patch plan did not update the live DEX model",
                        containsMethod(tree.getClassDef("instrumented/SmokeTarget"), "patched"));

                tree.saveAllDexFiles(new ClassTree.DexSaveProgress() {
                    @Override public void onProgress(int progress, int total) {}
                    @Override public void onMessage(String message) {}
                    @Override public void onTitle(String title) {}
                });

                File reloadCache = new File(context.getCacheDir(),
                        "dex-editor-reload-" + System.nanoTime());
                ClassTree reloadedTree = new ClassTree(
                        Collections.singletonList(dexFile.getAbsolutePath()), reloadCache.getAbsolutePath());
                try {
                    assertTrue("The saved DEX did not retain the edited method",
                            containsMethod(reloadedTree.getClassDef("instrumented/SmokeTarget"), "patched"));
                } finally {
                    reloadedTree.clearAll();
                }
            }
        } finally {
            dexFile.delete();
        }
    }

    @SuppressWarnings("deprecation")
    private static File getDownloadsDirectory() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    private static void grantAllFilesAccessForTest(Context context) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        String command = "cmd appops set " + context.getPackageName()
                + " MANAGE_EXTERNAL_STORAGE allow";
        ParcelFileDescriptor output = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        try (FileInputStream stream = new FileInputStream(output.getFileDescriptor())) {
            byte[] buffer = new byte[256];
            while (stream.read(buffer) != -1) {
                // Drain the shell command output before checking the app-op.
            }
        } finally {
            output.close();
        }
        assertTrue("Could not grant all-files access to the test app",
                Environment.isExternalStorageManager());
    }

    private static void writeFixtureDex(File dexFile) throws Exception {
        String smali = ".class public Linstrumented/SmokeTarget;\n"
                + ".super Ljava/lang/Object;\n"
                + ".method public target()V\n"
                + "    .registers 1\n"
                + "    return-void\n"
                + ".end method\n";
        DexPool pool = new DexPool(Opcodes.forDexVersion(35));
        pool.internClass(Smali.assemble(smali, new SmaliOptions(), 35));
        FileDataStore store = new FileDataStore(dexFile);
        try {
            pool.writeTo(store);
        } finally {
            store.close();
        }
    }

    private static boolean containsMethod(ClassDef classDef, String methodName) {
        if (classDef == null) return false;
        for (Method method : classDef.getMethods()) {
            if (methodName.equals(method.getName())) return true;
        }
        return false;
    }
}
