package com.jujin.freeway.commons.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/** The immutable tree value: shape, traversal orders and the arithmetic over them. */
class TreeNodeTest {

    /** root / a / b(c, d) / e(f) — three levels, seven nodes. */
    private static TreeNode<String> tree() {
        return TreeNode.of("root",
            TreeNode.leaf("a"),
            TreeNode.of("b", TreeNode.leaf("c"), TreeNode.leaf("d")),
            TreeNode.of("e", TreeNode.leaf("f")));
    }

    private static List<String> values(Iterable<TreeNode<String>> nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.value()));
        return values;
    }

    @Test
    void valueAndChildrenAreCopiedAndImmutable() {
        List<TreeNode<String>> children = new ArrayList<>(List.of(TreeNode.leaf("a")));
        TreeNode<String> node = new TreeNode<>("root", children);
        children.add(TreeNode.leaf("b"));
        assertEquals(List.of("a"), values(node.children()), "the constructor copies the list");
        assertThrows(UnsupportedOperationException.class, () -> node.children().add(TreeNode.leaf("c")));
    }

    @Test
    void nullsAreRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> TreeNode.leaf(null));
        assertThrows(NullPointerException.class, () -> new TreeNode<>("x", null));
        assertThrows(NullPointerException.class,
            () -> new TreeNode<>("x", java.util.Arrays.asList(TreeNode.leaf("a"), null)),
            "a tree with a hole is a construction mistake");
    }

    @Test
    void preOrderIsParentsFirstAndSiblingsInOrder() {
        assertIterableEquals(List.of("root", "a", "b", "c", "d", "e", "f"),
            values(tree().preOrder()));
    }

    @Test
    void levelOrderIsBreadthFirstOverTheSameNodes() {
        assertIterableEquals(List.of("root", "a", "b", "e", "c", "d", "f"),
            values(tree().levelOrder()));
        assertEquals(tree().size(), values(tree().levelOrder()).size(),
            "both orders cover the same nodes");
    }

    @Test
    void traversalIncludesTheStartingNode() {
        TreeNode<String> subtree = tree().children().get(1);
        assertIterableEquals(List.of("b", "c", "d"), values(subtree.preOrder()));
        assertIterableEquals(List.of("b", "c", "d"), values(subtree.levelOrder()));
    }

    @Test
    void exhaustedIteratorsThrowInsteadOfReturningNull() {
        var iterator = TreeNode.leaf("only").preOrder().iterator();
        assertEquals("only", iterator.next().value());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    void sizeAndHeight() {
        assertEquals(7, tree().size());
        assertEquals(3, tree().height());
        assertEquals(1, TreeNode.leaf("x").size());
        assertEquals(1, TreeNode.leaf("x").height());
        assertTrue(TreeNode.leaf("x").isLeaf());
        assertFalse(tree().isLeaf());
    }

    @Test
    void deepTreesDoNotUseCallDepth() {
        TreeNode<Integer> node = TreeNode.leaf(0);
        for (int i = 1; i <= 20_000; i++) {
            node = TreeNode.of(i, node);
        }
        assertEquals(20_001, node.size());
        assertEquals(20_001, node.height());
    }

    @Test
    void equalityIsStructuralAndIdentityIsNotEquality() {
        assertEquals(TreeNode.of("b", TreeNode.leaf("c")), TreeNode.of("b", TreeNode.leaf("c")));
        assertNotEquals(TreeNode.of("b", TreeNode.leaf("c")), TreeNode.of("b", TreeNode.leaf("d")));
        TreeNode<String> shared = TreeNode.leaf("c");
        assertEquals(TreeNode.of("b", shared), TreeNode.of("b", shared));
    }
}
