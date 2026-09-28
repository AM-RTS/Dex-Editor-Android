package modder.hub.dexeditor.utils;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import modder.hub.dexeditor.model.TreeNode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClassTreeBuilderTest {
    @Test
    public void groupsAndCompactsPackagePaths() {
        List<TreeNode> roots = ClassTreeBuilder.build(Arrays.asList(
                "org/sample/Only", "com/example/B", "com/example/A"));

        assertEquals(Arrays.asList("com.example", "org.sample"),
                roots.stream().map(TreeNode::getName).collect(Collectors.toList()));
        assertTrue(roots.get(0).isDirectory());
        assertEquals("com/example", roots.get(0).getFullName());
        assertEquals(Arrays.asList("A", "B"), roots.get(0).getChildren().stream()
                .map(TreeNode::getName).collect(Collectors.toList()));
        assertEquals("Only", roots.get(1).getChildren().get(0).getName());
    }
}
