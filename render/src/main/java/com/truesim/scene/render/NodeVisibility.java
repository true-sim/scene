package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.animation.AnimationPointer;
import com.truesim.scene.animation.Animator;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;
import java.util.Objects;

/**
 * Shows and hides nodes with everything under them, as glTF's {@code KHR_node_visibility}: a hidden node and its
 * descendants are put on no layer, and given back the layers they had once nothing above them is hidden any more. A
 * node keeps its own visibility apart from its ancestors', so showing a parent again leaves a child hidden that was
 * hidden itself. As a pointer sink it applies animators' visibility tracks; models spawned with it
 * ({@link ModelData#spawn(SceneGraph, Renderables, long, NodeVisibility)}) start with their hidden nodes hidden by it.
 *
 * <pre>{@code
 * NodeVisibility visibility = new NodeVisibility(scene);
 * long[] nodes = model.spawn(scene, renderables, root, visibility);
 * animators.pointerSink(visibility.andThen(rendererSink));
 * visibility.setVisible(nodes[3], false);
 * }</pre>
 *
 * <p>Nodes added under a hidden node afterwards keep their layers; layers set on a hidden node are replaced when it
 * shows. Used from the scene's thread; showing and hiding allocate nothing but room for more nodes.
 */
public final class NodeVisibility implements Animators.PointerSink {

    private final SceneGraph scene;
    // By node index: the node it is about (another there is a removed node's: forgotten), whether it is hidden itself,
    // how many of it and its ancestors are, and the layers it gets back once none is.
    private long[] nodes = new long[0];
    private boolean[] hidden = new boolean[0];
    private int[] hiders = new int[0];
    private int[] layers = new int[0];

    /** Visibility of {@code scene}'s nodes: all shown until hidden here. */
    public NodeVisibility(SceneGraph scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    /**
     * Shows {@code node} ({@code visible}) or hides it, and with it everything under it that is not hidden itself or by
     * another node between.
     */
    public void setVisible(long node, boolean visible) {
        checkOwner();
        if (!scene.contains(node)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(node));
        }
        int index = slot(node);
        if (hidden[index] != visible) {
            return;
        }
        hidden[index] = !visible;
        // Every node of the subtree, depth first, by the scene's links
        long at = node;
        while (at != NodeHandle.NONE) {
            count(at, visible ? -1 : 1);
            long next = scene.firstChild(at);
            while (next == NodeHandle.NONE && at != node) {
                next = scene.nextSibling(at);
                at = scene.parent(at);
            }
            at = at == node && next == NodeHandle.NONE ? NodeHandle.NONE : next;
        }
    }

    /** Whether {@code node} is not hidden itself (its ancestors may be). */
    public boolean visible(long node) {
        int index = NodeHandle.index(node);
        return index >= nodes.length || nodes[index] != node || !hidden[index];
    }

    /** Whether {@code node} shows: neither it nor any of its ancestors is hidden. */
    public boolean shown(long node) {
        int index = NodeHandle.index(node);
        return index >= nodes.length || nodes[index] != node || hiders[index] == 0;
    }

    /** Applies a {@link AnimationPointer.Target#VISIBILITY visibility} pointer: shown from 0.5 on; ignores others. */
    @Override
    public void value(Animator animator, AnimationPointer pointer, double[] values, int offset, int count) {
        if (pointer.target() == AnimationPointer.Target.VISIBILITY) {
            setVisible(animator.node(pointer.index()), values[offset] >= 0.5);
        }
    }

    /** One more hidden node at or above {@code node} ({@code by} 1) or one fewer (-1), its layers following. */
    private void count(long node, int by) {
        int index = slot(node);
        if (by > 0 && hiders[index]++ == 0) {
            layers[index] = scene.layers(node);
            scene.setLayers(node, 0);
        } else if (by < 0 && --hiders[index] == 0) {
            scene.setLayers(node, layers[index]);
        }
    }

    /** {@code node}'s index, with room for it, forgetting a removed node's state there. */
    private int slot(long node) {
        int index = NodeHandle.index(node);
        if (index >= nodes.length) {
            grow(Math.max(index + 1, 2 * nodes.length));
        }
        if (nodes[index] != node) {
            nodes[index] = node;
            hidden[index] = false;
            hiders[index] = 0;
        }
        return index;
    }

    @AllowAllocation("growing with the scene")
    private void grow(int length) {
        int from = nodes.length;
        nodes = Arrays.copyOf(nodes, length);
        Arrays.fill(nodes, from, length, NodeHandle.NONE);
        hidden = Arrays.copyOf(hidden, length);
        hiders = Arrays.copyOf(hiders, length);
        layers = Arrays.copyOf(layers, length);
    }

    /** Throws unless the calling thread may change visibility: any, or the clock's owner (ChangeClock.ownedBy). */
    private void checkOwner() {
        Thread owner = scene.clock().owner();
        if (owner != null && Thread.currentThread() != owner) {
            throw new IllegalStateException(notOwner(owner));
        }
    }

    @AllowAllocation("diagnostics")
    private static String notOwner(Thread owner) {
        return "Visibility changes on " + owner.getName() + " only, not on " + Thread.currentThread().getName();
    }
}
