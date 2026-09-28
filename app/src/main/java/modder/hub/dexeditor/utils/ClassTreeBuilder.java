package modder.hub.dexeditor.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import modder.hub.dexeditor.model.TreeNode;

/** Builds the compact package tree shared by the full and edited class views. */
final class ClassTreeBuilder {
    private ClassTreeBuilder() {}

    static List<TreeNode> build(List<String> classNames) {
        Map<String, TreeNode> allNodes = new HashMap<>();
        List<TreeNode> roots = new ArrayList<>();
        for (String type : classNames) {
            String[] parts = type.split("/");
            TreeNode parent = null;
            String path = "";
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                path = path.isEmpty() ? part : path + "/" + part;
                boolean isLast = i == parts.length - 1;
                TreeNode node = allNodes.get(path);
                if (node == null) {
                    node = new TreeNode(part, path, i, !isLast);
                    allNodes.put(path, node);
                    if (parent == null) roots.add(node);
                    else parent.addChild(node);
                } else if (!isLast) {
                    node.setDirectory(true);
                }
                parent = node;
            }
        }
        sortNodes(roots);
        compactTree(roots);
        return roots;
    }

    private static void sortNodes(List<TreeNode> nodes) {
        nodes.sort(Comparator.comparing(TreeNode::isDirectory).reversed()
                .thenComparing(TreeNode::getName, String.CASE_INSENSITIVE_ORDER));
        for (TreeNode node : nodes) sortNodes(node.getChildren());
    }

    private static void compactTree(List<TreeNode> nodes) {
        for (TreeNode node : nodes) {
            if (!node.isDirectory()) continue;
            List<TreeNode> children = node.getChildren();
            while (children.size() == 1 && children.get(0).isDirectory()) {
                TreeNode child = children.get(0);
                node.setName(node.getName() + "." + child.getName());
                node.setFullName(child.getFullName());
                node.setChildren(child.getChildren());
                children = node.getChildren();
            }
            compactTree(children);
        }
    }
}
