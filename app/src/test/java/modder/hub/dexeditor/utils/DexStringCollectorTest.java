package modder.hub.dexeditor.utils;

import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.tools.smali.smali2.Smali;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class DexStringCollectorTest {
    @Test
    public void collectsDistinctStringsFromFieldsMethodsAndNestedAnnotationsInOrder() throws Exception {
        String smali = ".class public Ltest/Strings;\n"
                + ".super Ljava/lang/Object;\n"
                + ".annotation runtime Ltest/Marker;\n"
                + "    value = {\"annotation\", {\"nested\"}}\n"
                + ".end annotation\n"
                + ".field public static final FIELD:Ljava/lang/String; = \"field\"\n"
                + ".method public static load()V\n"
                + "    .registers 1\n"
                + "    const-string v0, \"instruction\"\n"
                + "    const-string v0, \"field\"\n"
                + "    return-void\n"
                + ".end method\n";
        ClassDef classDef = Smali.assemble(smali, new SmaliOptions(), 35);

        assertEquals(java.util.Arrays.asList("annotation", "field", "instruction", "nested"),
                DexStringCollector.collect(Collections.singletonList(classDef)));
    }
}
