package dev.ferriarnus.volcanicmason.qa;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.*;
import com.minecolonies.api.colony.savedata.IServerColonySaveData;
import com.minecolonies.api.colony.jobs.ModJobs;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import dev.ferriarnus.volcanicmason.block.BlockRegistry;
import dev.ferriarnus.volcanicmason.buildings.*;
import dev.ferriarnus.volcanicmason.jobs.*;
import dev.ferriarnus.volcanicmason.settings.*;
import dev.ferriarnus.volcanicmason.entity.ai.EntityAIVolcanicMason;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.lang.reflect.*;
import java.util.*;

/** Opt-in server integration probe. Invokes production AI handlers; never packaged in release jar. */
@Mod("vmqa")
public class ServerProbe {
    private static final BlockPos HUT = new BlockPos(0, 101, 0);
    private static final BlockPos TARGET = new BlockPos(3, 101, 0);
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("VMQA PASS " + message);
    }
    private ServerLevel pendingLevel;
    private int elapsed;
    public ServerProbe() {
        if ("autonomous".equals(System.getenv("VMQA_SCENARIO"))) {
            new AutonomousProbe();
            return;
        }
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }
    private void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (pendingLevel == null) return;
        if (++elapsed == 40) {
            try { run(pendingLevel); }
            catch (Throwable t) {
                pendingLevel = null;
                System.out.println("VMQA FAIL");
                t.printStackTrace();
            }
            return;
        }
        if (elapsed < 240) return;
        ServerLevel level = pendingLevel;
        pendingLevel = null;
        try {
            check(level.getBlockState(new BlockPos(6,101,6)).is(Blocks.COBBLESTONE),"water/lava generates cobblestone");
            check(level.getBlockState(new BlockPos(6,101,2)).is(Blocks.STONE),"water/lava generates stone");
            check(level.getBlockState(new BlockPos(6,101,-2)).is(Blocks.BASALT),"soul soil/blue ice/lava generates basalt");
            check(level.getBlockState(TARGET).is(Blocks.OBSIDIAN),"worker-placed lava generates obsidian");
            System.out.println("VMQA COMPLETE");
        } catch (Throwable t) {
            System.out.println("VMQA FAIL");
            t.printStackTrace();
        }
    }
    private static Object call(Object obj, String method) throws Exception {
        Method m = EntityAIVolcanicMason.class.getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(obj);
    }
    private static void mode(BuildingVolcanicMason b, Modes mode, ServerLevel level) throws Throwable {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("value", mode.ordinal());
        b.getSetting(BuildingVolcanicMason.MODE).copyValue(
            new ModeSetting.ModeSettingFactory().deserialize(level.registryAccess(), null, nbt));
        b.markDirty();
    }
    private void started(ServerStartedEvent event) {
        try {
            pendingLevel = event.getServer().overworld();
            for (int x=-1;x<=0;x++) for(int z=-1;z<=0;z++) pendingLevel.setChunkForced(x,z,true);
        } catch (Throwable t) {
            System.out.println("VMQA FAIL");
            t.printStackTrace();
        }
    }
    private void run(ServerLevel level) throws Throwable {
        Identifier id = Identifier.fromNamespaceAndPath("volcanicmason", "volcanic_mason");
        check(JobsRegistry.VOLCANIC_MASON.get() != null && ModJobs.jobs.contains(id), "job registry and namespace");
        check(BuildingRegistry.VOLCANIC_MASON.get() != null, "building registry");
        for (String path : List.of("vulcanicmason", "stone", "basalt", "obsidian", "soul_sand")) {
            check(IMinecoloniesAPI.getInstance().getGlobalResearchTree().getResearch(
                Identifier.fromNamespaceAndPath("volcanicmason", "technology/" + path)) != null, "research " + path);
        }
        check(level.getServer().getRecipeManager().getRecipes().stream()
            .anyMatch(recipe -> recipe.id().identifier().equals(id)), "hut crafting recipe loaded");
        check(com.minecolonies.core.colony.crafting.CustomRecipeManager.getInstance().getRecipes("crusher_custom")
            .stream().filter(recipe -> recipe.getRecipeId().getNamespace().equals("volcanicmason")).count() == 2,
            "both crusher recipes loaded");
        var factory = com.minecolonies.api.colony.requestsystem.StandardFactoryController.getInstance();
        for (Modes expected : Modes.values()) {
            CompoundTag tag = new CompoundTag(); tag.putInt("value",expected.ordinal());
            var setting = new ModeSetting.ModeSettingFactory().deserialize(level.registryAccess(),factory,tag);
            ModeSetting restored = factory.deserializeTag(level.registryAccess(),factory.serializeTag(level.registryAccess(),setting));
            check(restored.getValue()==expected,"factory NBT roundtrip " + expected);
            var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),level.registryAccess());
            try {
                factory.serialize(buf,setting);
                ModeSetting network = factory.deserialize(buf);
                check(network.getValue()==expected,"factory network roundtrip " + expected);
            } finally { buf.release(); }
        }
        var saved = IServerColonySaveData.getOrComputeSaveData(level);
        IColony colony = saved.getColonies().stream().filter(c -> c.getName().equals("VMQA")).findFirst().orElse(null);
        if (colony != null) {
            var b = (BuildingVolcanicMason) colony.getServerBuildingManager().getBuilding(HUT);
            check(b != null, "restart building");
            check(b.getSetting(BuildingVolcanicMason.MODE).getValue() == Modes.OBSIDIAN, "restart mode");
            check(TARGET.equals(b.getBlockToMine()), "restart generator position");
            CompoundTag count = new CompoundTag();
            b.getModule(BuildingVolcanicMason.LimitedMiningModule.class).serializeNBT(level.registryAccess(), count);
            check(count.getIntOr("mined", -1) == 4, "restart mined count: " + count);
            check(colony.getCitizenManager().getCivilian(1).getJob() instanceof JobVolcanicMason, "restart citizen job");
            return;
        }
        for (int x=-8;x<=8;x++) for (int z=-8;z<=8;z++) {
            level.setBlock(new BlockPos(x,100,z), Blocks.STONE.defaultBlockState(),3);
            for (int y=101;y<=104;y++) level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
        }
        for (int x=-1;x<=0;x++) for(int z=-1;z<=0;z++) level.setChunkForced(x,z,true);
        for (int z : List.of(6,2,-2)) {
            for(int x=5;x<=8;x++) {
                level.setBlock(new BlockPos(x,101,z-1),Blocks.STONE.defaultBlockState(),3);
                level.setBlock(new BlockPos(x,101,z+1),Blocks.STONE.defaultBlockState(),3);
            }
            level.setBlock(new BlockPos(5,101,z),Blocks.STONE.defaultBlockState(),3);
            level.setBlock(new BlockPos(8,101,z),Blocks.STONE.defaultBlockState(),3);
        }
        // Three independent physical generators. Obsidian uses the worker's bucket action below.
        level.setBlock(new BlockPos(7,101,6),Blocks.WATER.defaultBlockState(),3);
        level.setBlock(new BlockPos(6,101,6),Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL,1),3);
        level.setBlock(new BlockPos(6,102,6),Blocks.LAVA.defaultBlockState(),3);
        level.setBlock(new BlockPos(6,101,2),Blocks.WATER.defaultBlockState(),3);
        level.setBlock(new BlockPos(6,102,2),Blocks.LAVA.defaultBlockState(),3);
        level.setBlock(new BlockPos(6,100,-2),Blocks.SOUL_SOIL.defaultBlockState(),3);
        level.setBlock(new BlockPos(7,101,-2),Blocks.BLUE_ICE.defaultBlockState(),3);
        level.setBlock(new BlockPos(6,102,-2),Blocks.LAVA.defaultBlockState(),3);
        colony = saved.createColony(level, "VMQA", HUT);
        colony.setStructurePack("Volcanic Mason");
        level.setBlock(HUT, BlockRegistry.VOLCANIC_MASON.get().defaultBlockState(),3);
        var tile = (AbstractTileEntityColonyBuilding) level.getBlockEntity(HUT);
        tile.setStructurePack(com.ldtteam.structurize.storage.StructurePacks.getStructurePack("Volcanic Mason"));
        tile.setBlueprintPath("Minecolonies/volcanic_mason5.blueprint");
        var b = (BuildingVolcanicMason) colony.getServerBuildingManager().addNewBuilding(tile, level);
        b.setBuildingLevel(5);
        tile.setPositionedTags(Map.of(TARGET.subtract(HUT), List.of("cobble","stone","basalt","obsidian")));
        check(TARGET.equals(b.getBlockToMine()), "first lookup of blueprint generator tag");
        var citizen = colony.getCitizenManager().spawnOrCreateCivilian(
            (ICitizenData) null, level, List.of(new BlockPos(2,101,0)), true);
        check(citizen != null && citizen.getEntity().isPresent(), "spawn real citizen");
        check(b.getModule(WorkerBuildingModule.class).assignCitizen(citizen), "assign profession");
        check(citizen.getJob() instanceof JobVolcanicMason, "job class");
        var entity = citizen.getEntity().orElseThrow();
        entity.setNoAi(true);
        entity.setPos(2.5,101,0.5);
        entity.getInventoryCitizen().setStackInSlot(0,new ItemStack(Items.DIAMOND_PICKAXE));
        var ai = ((JobVolcanicMason) citizen.getJob()).generateAI();
        check(ai.building == b, "AI building binding");
        var pickaxe = dev.ferriarnus.volcanicmason.jobs.MasonEquipment.PICKAXE.get();
        var toolRequest = new com.minecolonies.api.colony.requestsystem.requestable.Tool(pickaxe, 0, 5);
        check(toolRequest.matches(new ItemStack(Items.DIAMOND_PICKAXE))
            && !toolRequest.matches(new ItemStack(Items.DIAMOND_SHOVEL)), "tool request accepts pickaxe and rejects shovel");
        com.minecolonies.api.colony.requestsystem.requestable.Tool restoredTool =
            com.minecolonies.api.colony.requestsystem.requestable.Tool.deserialize(level.registryAccess(), factory,
                com.minecolonies.api.colony.requestsystem.requestable.Tool.serialize(level.registryAccess(), factory, toolRequest));
        check(restoredTool.matches(new ItemStack(Items.DIAMOND_PICKAXE)), "tool request NBT roundtrip");
        check(b.getRequiredItemsAndAmount().keySet().stream().anyMatch(p -> p.test(new ItemStack(Items.DIAMOND_PICKAXE))),
            "building retains pickaxe");
        for (Modes mode : Modes.values()) {
            mode(b,mode,level);
            Block block = ((BlockItem)mode.getItem().getItem()).getBlock();
            level.setBlock(TARGET,block.defaultBlockState(),3);
            System.out.println("VMQA DIAG mode="+mode+" position="+entity.blockPosition()+" target="+b.getBlockToMine()
                +" tool="+ai.holdEfficientTool(level.getBlockState(TARGET),TARGET)+" hand="+entity.getMainHandItem()
                +" decision="+call(ai,"decide"));
            // Handler-level integration: production mining, tool wear, drops and daily limit.
            for (int i=0;i<8 && level.getBlockState(TARGET).is(block);i++) {
                System.out.println("VMQA DIAG harvest="+call(ai,"harvest")+" block="+level.getBlockState(TARGET));
            }
            check(!level.getBlockState(TARGET).is(block), "harvest " + mode);
            boolean found=false;
            for(int i=0;i<entity.getInventoryCitizen().getSlots();i++)
                found |= entity.getInventoryCitizen().getStackInSlot(i).is(mode.getItem().getItem());
            check(found, "inventory drop " + mode);
        }
        check(entity.getInventoryCitizen().getStackInSlot(0).getDamageValue() >= 4,"real mining damages pickaxe");
        var module=b.getModule(BuildingVolcanicMason.LimitedMiningModule.class);
        CompoundTag mined=new CompoundTag();
        module.serializeNBT(level.registryAccess(),mined);
        check(mined.getIntOr("mined",-1)==4, "four actions counted");
        check(module.checkDirty(), "mining marks persistent module dirty");
        var limitProbe = new BuildingVolcanicMason.LimitedMiningModule();
        limitProbe.setBuilding(b);
        CompoundTag limit = new CompoundTag(); limit.putInt("mined",16); limit.putInt("miningDay", b.getColony().getDay());
        limitProbe.deserializeNBT(level.registryAccess(),limit);
        check(!limitProbe.canMine(),"daily limit stops mining");
        limitProbe.onWakeUp();
        check(!limitProbe.canMine(), "same-day wake-up preserves daily limit");
        limit.putInt("miningDay", b.getColony().getDay() - 2);
        limitProbe.deserializeNBT(level.registryAccess(),limit);
        limitProbe.onWakeUp();
        check(limitProbe.canMine() && limitProbe.checkDirty(),"wake-up resets and dirties daily limit");
        mode(b,Modes.OBSIDIAN,level);
        entity.getInventoryCitizen().setStackInSlot(2,new ItemStack(Items.LAVA_BUCKET));
        level.setBlock(TARGET,Blocks.DIAMOND_BLOCK.defaultBlockState(),3);
        call(ai,"placeFluid");
        check(level.getBlockState(TARGET).is(Blocks.DIAMOND_BLOCK)
            && entity.getInventoryCitizen().getStackInSlot(2).is(Items.LAVA_BUCKET), "fluid placement protects occupied target");
        level.setBlock(TARGET,Blocks.AIR.defaultBlockState(),3);
        call(ai,"placeFluid");
        check(level.getBlockState(TARGET).is(Blocks.LAVA)
            && entity.getInventoryCitizen().getStackInSlot(2).is(Items.BUCKET), "lava placement consumes one bucket");
        level.setBlock(TARGET.above(),Blocks.WATER.defaultBlockState(),3);
        // After mining, water refills the generator; the next bucket must still work.
        level.setBlock(TARGET,Blocks.WATER.defaultBlockState(),3);
        entity.getInventoryCitizen().setStackInSlot(2,new ItemStack(Items.LAVA_BUCKET));
        call(ai,"placeFluid");
        check(level.getBlockState(TARGET).is(Blocks.OBSIDIAN)
            && entity.getInventoryCitizen().getStackInSlot(2).is(Items.BUCKET), "obsidian generator refilled with water accepts next lava bucket");
        // Both world state and NBT are left in place for a real process restart.
        b.markDirty();
        citizen.markDirty(0);
        CompoundTag bad=new CompoundTag();bad.putInt("value",999);
        check(new ModeSetting.ModeSettingFactory().deserialize(level.registryAccess(),null,bad).getValue()==Modes.COBBLESTONE,
            "invalid saved mode falls back safely");
    }
}
