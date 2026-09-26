package com.hearthstead.settlement.builder;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.BuilderWorkGoal;
import com.hearthstead.registry.ModEntities;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Private QA harness. Production methods are invoked without alteration. */
@GameTestHolder("codex_diagnostics")
@PrefixGameTestTemplate(false)
public final class CodexBuilderBatchGameTests {
    @GameTest(template="empty32", timeoutTicks=40)
    public static void selectedMaterialWithinBatchIsLoaded(GameTestHelper h) throws Exception {
        check(h, 63);
    }
    @GameTest(template="empty32", timeoutTicks=40)
    public static void selectedMaterialBeyondBatchIsLoaded(GameTestHelper h) throws Exception {
        check(h, 64);
    }
    @SuppressWarnings("unchecked")
    private static void check(GameTestHelper h, int preceding) throws Exception {
        SettlerEntity worker=h.spawn(ModEntities.SETTLER.get(),new BlockPos(2,1,2));
        worker.setNoAi(true);
        int n=preceding+1;
        long[] positions=new long[n], companions=new long[n];
        int[] states=new int[n], companionStates=new int[n];
        byte[] phases=new byte[n];
        Arrays.fill(phases,(byte)BuildPhase.STRUCTURE.ordinal());
        Arrays.fill(companionStates,-1);
        for(int i=0;i<preceding;i++) positions[i]=h.absolutePos(new BlockPos(15+i%8,1,15+i/8)).asLong();
        positions[preceding]=h.absolutePos(new BlockPos(3,1,2)).asLong();
        states[preceding]=1;
        BuildJob job=new BuildJob(UUID.randomUUID(),UUID.randomUUID(),BuildJob.Kind.BLUEPRINT,
            "codex_batch_probe","batch probe",h.absolutePos(BlockPos.ZERO),0,false,
            new BoundingBox(h.absolutePos(BlockPos.ZERO)),null,h.getLevel().getGameTime(),
            List.of(Blocks.COBBLESTONE.defaultBlockState(),Blocks.OAK_PLANKS.defaultBlockState()),
            positions,states,phases,new byte[n],companions,companionStates);
        BuilderWorkGoal goal=new BuilderWorkGoal(worker);
        Method select=BuilderWorkGoal.class.getDeclaredMethod("nextStep",BuildJob.class,long.class);
        select.setAccessible(true);
        int selected=(int)select.invoke(goal,job,h.getLevel().getGameTime());
        h.assertTrue(selected==preceding,"fixture must choose nearby oak step; selected="+selected);
        Method batch=BuilderWorkGoal.class.getDeclaredMethod("batchNeeds",BuildJob.class);
        batch.setAccessible(true);
        Map<Item,Integer> needed=(Map<Item,Integer>)batch.invoke(goal,job);
        h.assertTrue(!BuilderMaterials.costsOfStep(job,selected).isEmpty(),"fixture selected step must consume material");
        for(BuilderMaterials.ItemCount cost:BuilderMaterials.costsOfStep(job,selected)) {
            h.assertTrue(needed.getOrDefault(cost.item(),0)>=cost.count(),
                "Selected step "+selected+" needs "+cost.item()+" but batch="+needed
                +" capacity="+BuilderWorkGoal.builderCapacity(worker));
        }
        worker.discard();
        h.succeed();
    }
}
