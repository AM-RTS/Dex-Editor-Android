package modder.hub.dexeditor.utils;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SmaliNavigationParserTest {
    @Test
    public void extractsClassFieldMethodAndStringLineNumbers() throws Exception {
        File smali = Files.createTempFile("smali-navigation", ".smali").toFile();
        try {
            Files.write(smali.toPath(), (".class public Ltest/Nav;\n"
                    + ".super Ljava/lang/Object;\n"
                    + ".field private value:Ljava/lang/String;\n"
                    + ".method public static run()V\n"
                    + "    const-string v0, \"hello\"\n"
                    + "    return-void\n"
                    + ".end method\n").getBytes(StandardCharsets.UTF_8));

            SmaliNavigationParser.Result result = SmaliNavigationParser.parse(smali, () -> false);
            assertEquals("Ltest/Nav;", result.getClassName());
            assertEquals(1, result.getData().get("ClassInfo").get(0).get("StartLineNumber"));
            assertEquals("Ljava/lang/Object;", result.getData().get("ClassInfo").get(0).get("SuperClass"));
            assertEquals("value:Ljava/lang/String;", result.getData().get("FieldInfo").get(0).get("MethodOrFieldName"));
            assertEquals(4, result.getData().get("MethodInfo").get(0).get("StartLineNumber"));
            assertEquals("run()V", result.getData().get("MethodInfo").get(0).get("MethodOrFieldName"));
            assertEquals(5, result.getData().get("StringInfo").get(0).get("StartLineNumber"));
            assertEquals("hello", result.getData().get("StringInfo").get(0).get("StringName"));
        } finally {
            Files.deleteIfExists(smali.toPath());
        }
    }

    @Test
    public void cancellationStopsParsingWithoutReturningPartialNavigationData() throws Exception {
        File smali = Files.createTempFile("smali-navigation", ".smali").toFile();
        try {
            Files.write(smali.toPath(), ".class public Ltest/Nav;\n".getBytes(StandardCharsets.UTF_8));
            assertNull(SmaliNavigationParser.parse(smali, () -> true));
        } finally {
            Files.deleteIfExists(smali.toPath());
        }
    }
}
