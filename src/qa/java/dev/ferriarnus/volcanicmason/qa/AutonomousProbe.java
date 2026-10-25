package dev.ferriarnus.volcanicmason.qa;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.savedata.IServerColonySaveData;
import dev.ferriarnus.volcanicmason.buildings.BuildingVolcanicMason;
import dev.ferriarnus.volcanicmason.jobs.JobVolcanicMason;
import dev.ferriarnus.volcanicmason.settings.ModeSetting;
import dev.ferriarnus.volcanicmason.settings.Modes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Observes normal citizen ticks. Never calls worker AI handlers or advances their state machine. */
final class AutonomousProbe {
    private static final BlockPos HUT = new BlockPos(0,101,0);
    private static final BlockPos TARGET = new BlockPos(3,101,0);
    private Modes[] modes;
    private ServerLevel level;
    private BuildingVolcanicMason building;
    private ICitizenData citizen;
    private int ticks, stage, baseline, stageStarted;
    private double startX;
    private boolean repeat;

    AutonomousProbe() {
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }
    private void started(ServerStartedEvent event) {
        modes = new Modes[]{Modes.COBBLESTONE, Modes.STONE, Modes.BASALT, Modes.OBSIDIAN};
        level = event.getServer().overworld();
        for (int x=-1;x<=0;x++) for (int z=-1;z<=0;z++) level.setChunkForced(x,z,true);
    }
    private void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("VMQA PASS autonomous " + message);
    }
    private int mined() {
        var tag = new CompoundTag();
        building.getModule(BuildingVolcanicMason.LimitedMiningModule.class).serializeNBT(level.registryAccess(),tag);
        return tag.getIntOr("mined",-1);
    }
    private void select() throws Throwable {
        var tag = new CompoundTag(); tag.putInt("value",modes[stage].ordinal());
        building.getSetting(BuildingVolcanicMason.MODE).copyValue(
            new ModeSetting.ModeSettingFactory().deserialize(level.registryAccess(),null,tag));
        building.markDirty();
        level.setBlock(TARGET,((BlockItem)modes[stage].getItem().getItem()).getBlock().defaultBlockState(),3);
        baseline = mined(); stageStarted=ticks;
        System.out.println("VMQA AUTO stage=" + modes[stage] + " baseline=" + baseline);
    }
    private void tick(ServerTickEvent.Post event) {
        if (level == null) return;
        try {
            ticks++;
            if (ticks == 40) {
                var colony = IServerColonySaveData.getOrComputeSaveData(level).getColonies().stream()
                    .filter(c -> c.getName().equals("VMQA")).findFirst().orElseThrow();
                building = (BuildingVolcanicMason)colony.getServerBuildingManager().getBuilding(HUT);
                citizen = colony.getCitizenManager().getCivilian(1);
                var entity=citizen.getEntity().orElseThrow();
                check(citizen.getJob() instanceof JobVolcanicMason,"persisted profession");
                for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++) {
                    level.setBlock(new BlockPos(x,100,z),Blocks.STONE.defaultBlockState(),3);
                    for(int y=101;y<=104;y++) if(!new BlockPos(x,y,z).equals(HUT))
                        level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
                }
                for(int i=0;i<entity.getInventoryCitizen().getSlots();i++)
                    entity.getInventoryCitizen().setStackInSlot(i,ItemStack.EMPTY);
                entity.getInventoryCitizen().setStackInSlot(0,new ItemStack(Items.DIAMOND_PICKAXE));
                entity.getInventoryCitizen().setStackInSlot(2,new ItemStack(Items.LAVA_BUCKET));
                citizen.setSaturation(20);
                entity.setHealth(entity.getMaxHealth());
                entity.setPos(-4.5,101,0.5); startX=entity.getX();
                entity.setNoAi(false);
                select();
            }
            if (ticks < 40) return;
            // Controlled daytime/weather fixture; worker timing and navigation remain untouched.
            if(ticks == 40 || ticks % 200 == 0) {
                var server=level.getServer();
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set day");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"weather clear");
            }
            var entity=citizen.getEntity().orElseThrow();
            if(ticks % 200 == 0)
                System.out.println("VMQA AUTO tick="+ticks+" stage="+stage+" mined="+mined()+" pos="+entity.position()
                    +" citizenAI="+((com.minecolonies.core.entity.citizen.EntityCitizen)entity).getCitizenAI().getState()+" workerAI="+citizen.getJob().getWorkerAI().getState());
            if(ticks-stageStarted > 8000) throw new AssertionError("autonomous action timeout stage="+stage);
            if(mined() > baseline) {
                check(mined()==baseline+1,"exactly one action "+modes[stage]+(repeat?" repeat":""));
                check(entity.getX()>startX+2,"walked toward generator");
                boolean drop=false;
                for(int i=0;i<entity.getInventoryCitizen().getSlots();i++)
                    drop |= entity.getInventoryCitizen().getStackInSlot(i).is(modes[stage].getItem().getItem());
                check(drop,"inventory drop "+modes[stage]);
                if(stage < modes.length-1) {stage++;select();}
                else if(!repeat) {
                    repeat=true; baseline=mined(); stageStarted=ticks;
                    // Contained water column refills the mined obsidian hole.
                    for(var pos : java.util.List.of(TARGET.north(),TARGET.south(),TARGET.east())) {
                        level.setBlock(pos,Blocks.STONE.defaultBlockState(),3);
                        level.setBlock(pos.above(),Blocks.STONE.defaultBlockState(),3);
                    }
                    level.setBlock(TARGET.west().above(),Blocks.STONE.defaultBlockState(),3);
                    level.setBlock(TARGET.above(),Blocks.WATER.defaultBlockState(),3);
                } else {
                    check(entity.getInventoryCitizen().getStackInSlot(2).is(Items.BUCKET),"lava bucket consumed by scheduled AI");
                    check(entity.getInventoryCitizen().getStackInSlot(0).getDamageValue()>=5,"tool wear from five scheduled actions");
                    entity.setNoAi(true);
                    System.out.println("VMQA COMPLETE");
                    level=null;
                }
            }
        } catch(Throwable t) {
            System.out.println("VMQA FAIL");t.printStackTrace();level=null;
        }
    }
}
