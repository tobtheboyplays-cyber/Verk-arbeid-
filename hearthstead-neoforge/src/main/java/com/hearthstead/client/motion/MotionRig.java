package com.hearthstead.client.motion;

import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Name -> {@link ModelPart} table for one baked model instance.
 *
 * <p>Vanilla {@code KeyframeAnimations.animate} resolves every animated bone
 * with {@code getAnyDescendantWithName}, which streams the whole part tree
 * once per bone per frame. A rig resolves each name exactly once and every
 * {@link MotionClip} caches its track -> bone index table per rig, so a
 * crowd of settlers samples clips without any per-frame lookup or
 * allocation.
 */
public final class MotionRig {
    private final java.util.function.Function<String, Optional<ModelPart>> lookup;
    private final Map<String, Integer> indexByName = new HashMap<>();
    private final List<ModelPart> parts = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private ModelPart[] partArray = new ModelPart[0];

    public MotionRig(HierarchicalModel<?> model) {
        this.lookup = model::getAnyDescendantWithName;
    }

    /**
     * Rig over any baked part tree -- e.g. a vanilla PlayerModel/HumanoidModel
     * root (bones "head", "hat", "body", "right_arm", "left_arm", "right_leg",
     * "left_leg"; no bend bones, so elbow/knee tracks are simply skipped).
     */
    public MotionRig(ModelPart root) {
        this.lookup = name -> root.getAllParts().filter(part -> part.hasChild(name)).findFirst()
            .map(part -> part.getChild(name));
    }

    /** Stable index of a bone, or -1 when the model has no such part. */
    public int indexOf(String name) {
        Integer cached = indexByName.get(name);
        if (cached != null) {
            return cached;
        }
        Optional<ModelPart> found = lookup.apply(name);
        int index = -1;
        if (found.isPresent()) {
            index = parts.size();
            parts.add(found.get());
            names.add(name);
            partArray = parts.toArray(new ModelPart[0]);
        }
        indexByName.put(name, index);
        return index;
    }

    public ModelPart part(int index) {
        return partArray[index];
    }

    public ModelPart part(String name) {
        int index = indexOf(name);
        return index < 0 ? null : partArray[index];
    }

    public String name(int index) {
        return names.get(index);
    }

    public int size() {
        return partArray.length;
    }
}
