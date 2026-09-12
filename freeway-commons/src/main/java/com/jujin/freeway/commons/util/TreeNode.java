package com.jujin.freeway.commons.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * An immutable tree value: a node holds a value and its children, in order.
 *
 * <p>A tree is <b>data</b>, not a cursor and not an editor. Composition,
 * traversal order and structure are all readable from the value, so anything
 * that needs the shape (diagnostics, logs, tests, a renderer) reads the same
 * object instead of walking a producer method again. There is deliberately no
 * {@code parent}, {@code add}, {@code remove} or {@code set}: a mutable node
 * has to keep several pointers consistent at every edit site, and its readers
 * cannot tell whether the structure they hold is still the one that was
 * validated. Immutability also makes cycles unrepresentable — a child cannot
 * reference a node that does not exist yet.
 *
 * <p>Equality is structural ({@code record} semantics): two trees are equal
 * when their values and children are. Node <em>identity</em> is what a caller
 * checks when the value type itself has identity semantics (a module instance,
 * say) — build a set over {@link #preOrder()} for that.
 *
 * <pre>{@code
 * TreeNode<String> tree = TreeNode.of("root",
 *     TreeNode.leaf("a"),
 *     TreeNode.of("b", TreeNode.leaf("c")));
 * for (TreeNode<String> node : tree.preOrder()) { ... }   // root, a, b, c
 * }</pre>
 *
 * @param value    this node's value
 * @param children its children, in order; the list is copied and never null
 * @param <T>      the value type
 */
public record TreeNode<T>(T value, List<TreeNode<T>> children) {

    public TreeNode {
        value = Objects.requireNonNull(value, "value");
        // List.copyOf both copies and rejects null children — a tree with a
        // hole in it is a construction mistake, not a shape to carry around.
        children = List.copyOf(Objects.requireNonNull(children, "children"));
    }

    /** A node with the given value and children, in order. */
    @SafeVarargs
    public static <T> TreeNode<T> of(T value, TreeNode<T>... children) {
        return new TreeNode<>(value, List.of(children));
    }

    /** A node with the given value and children taken from a list, in order. */
    public static <T> TreeNode<T> of(T value, List<TreeNode<T>> children) {
        return new TreeNode<>(value, children);
    }

    /** A node without children. */
    public static <T> TreeNode<T> leaf(T value) {
        return new TreeNode<>(value, List.of());
    }

    /** Whether this node has no children. */
    public boolean isLeaf() {
        return children.isEmpty();
    }

    /**
     * This node and its descendants, depth first, parents before children and
     * siblings in order. The iterator is lazy and iterative (an explicit
     * stack): a deep tree is data, not call depth.
     */
    public Iterable<TreeNode<T>> preOrder() {
        return () -> new Iterator<>() {
            private final Deque<TreeNode<T>> pending = new ArrayDeque<>(List.of(TreeNode.this));

            @Override
            public boolean hasNext() {
                return !pending.isEmpty();
            }

            @Override
            public TreeNode<T> next() {
                if (pending.isEmpty()) {
                    throw new NoSuchElementException();
                }
                TreeNode<T> node = pending.pop();
                List<TreeNode<T>> children = node.children;
                for (int i = children.size() - 1; i >= 0; i--) {
                    pending.push(children.get(i));
                }
                return node;
            }
        };
    }

    /**
     * This node and its descendants, level by level, siblings in order. The
     * same nodes as {@link #preOrder()}, in the order a reader expects on a
     * screen; it is not an alternative binding order.
     */
    public Iterable<TreeNode<T>> levelOrder() {
        return () -> new Iterator<>() {
            private final Deque<TreeNode<T>> pending = new ArrayDeque<>(List.of(TreeNode.this));

            @Override
            public boolean hasNext() {
                return !pending.isEmpty();
            }

            @Override
            public TreeNode<T> next() {
                if (pending.isEmpty()) {
                    throw new NoSuchElementException();
                }
                TreeNode<T> node = pending.removeFirst();
                pending.addAll(node.children);
                return node;
            }
        };
    }

    /** How many nodes this subtree holds (at least one). */
    public int size() {
        int count = 0;
        for (TreeNode<T> ignored : preOrder()) {
            count++;
        }
        return count;
    }

    /** The number of levels below this node, counting this node (a leaf is 1). */
    public int height() {
        Deque<TreeNode<T>> level = new ArrayDeque<>(List.of(this));
        int height = 0;
        while (!level.isEmpty()) {
            height++;
            for (int i = level.size(); i > 0; i--) {
                level.addAll(level.removeFirst().children);
            }
        }
        return height;
    }
}
