package dev.ferriarnus.volcanicmason.entity.ai;

import com.google.common.reflect.TypeToken;
import net.minecraft.world.InteractionHand;
import dev.ferriarnus.volcanicmason.jobs.MasonEquipment;
import com.minecolonies.core.util.WorkerUtil;
import com.minecolonies.core.util.citizenutils.CitizenItemUtils;
import com.minecolonies.api.entity.ai.JobStatus;
import com.minecolonies.api.colony.requestsystem.requestable.StackList;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.research.util.ResearchConstants;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.Tuple;
import com.minecolonies.api.util.constant.StatisticsConstants;
import com.minecolonies.api.util.constant.translation.RequestSystemTranslationConstants;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIInteract;
import dev.ferriarnus.volcanicmason.buildings.BuildingVolcanicMason;
import dev.ferriarnus.volcanicmason.jobs.JobVolcanicMason;
import dev.ferriarnus.volcanicmason.settings.Modes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.*;
import static com.minecolonies.api.util.constant.Constants.TICKS_SECOND;
import static com.minecolonies.api.util.constant.Constants.UPDATE_FLAG;
import static dev.ferriarnus.volcanicmason.entity.ai.VolcanicMasonStates.*;

public class EntityAIVolcanicMason extends AbstractEntityAIInteract<JobVolcanicMason, BuildingVolcanicMason> {

    public static final Predicate<ItemStack> LAVA_BUCKET = (s) -> s.is(Items.LAVA_BUCKET);
    private BlockPos lastPos;

    public EntityAIVolcanicMason(@NotNull JobVolcanicMason job) {
        super(job);
        super.registerTargets(
                new AITarget<IAIState>(AIWorkerState.IDLE, () -> AIWorkerState.START_WORKING, 1),
                new AITarget<IAIState>(AIWorkerState.START_WORKING, this::decide, 5),
                new AITarget<IAIState>(VOLCANIC_MASON_PLACE_FLUID, this::placeFluid, TICKS_SECOND),
                new AITarget<IAIState>(VOLCANIC_MASON_HARVESTING, this::harvest, TICKS_SECOND)
        );
        this.worker.setCanPickUpLoot(true);
    }

    private IAIState decide() {
        worker.getCitizenData().setVisibleStatus(VisibleCitizenStatus.WORKING);
        lastPos = building.getBlockToMine();

        if (lastPos != null) {
            if (building.getModule(BuildingVolcanicMason.LimitedMiningModule.class).canMine()) {
                return VOLCANIC_MASON_HARVESTING;
            }
        }

        if (job.getActionsDone() > 0) {
            return getState();
        }

        return START_WORKING;
    }

    private IAIState placeFluid() {
        final BlockPos posToPlace = building.getObsidianPos();
        if (posToPlace == null) {
            return START_WORKING;
        }

        final int slot = getSlotFluid();
        if (slot == -1) {
            if (InventoryUtils.getCountFromBuilding(building, LAVA_BUCKET) > 0) {
                this.needsCurrently = new Tuple<>(LAVA_BUCKET, 1);
                return GATHERING_REQUIRED_MATERIALS;
            } else if (!building.hasWorkerOpenRequestsOfType(worker.getCitizenData().getId(), TypeToken.of(StackList.class))){
                final List<ItemStack> items = new ArrayList<>();
                items.add(new ItemStack(Items.LAVA_BUCKET, 1));
                worker.getCitizenData().createRequestAsync(new StackList(items, RequestSystemTranslationConstants.REQUESTS_TYPE_DELIVERY, 4, 1));
            }
            return START_WORKING;
        }

        if (!walkToWorkPos(posToPlace)) {
            return getState();
        }

        // Water can flow back into an obsidian generator after harvesting.
        // Recheck after walking: allow air/water, but never replace solid or waterlogged blocks.
        final BlockState placementState = world.getBlockState(posToPlace);
        if (!placementState.isAir() && !placementState.is(Blocks.WATER)) {
            return START_WORKING;
        }
        if (world.setBlock(posToPlace, Fluids.LAVA.defaultFluidState().createLegacyBlock(), UPDATE_FLAG)) {
            worker.getInventoryCitizen().setStackInSlot(slot, new ItemStack(Items.BUCKET));
            return START_WORKING;
        }

        return getState();
    }

    private int getSlotFluid() {
        final ItemStack inputStack = new ItemStack(Items.LAVA_BUCKET);
        return InventoryUtils.findFirstSlotInItemHandlerWith(worker.getInventoryCitizen(), s -> ItemStackUtils.compareItemStacksIgnoreStackSize(s, inputStack));
    }

    private IAIState harvest() {
        if (lastPos == null) {
            this.resetActionsDone();
            return START_WORKING;
        }

        final Modes modes = building.getSetting(BuildingVolcanicMason.MODE).getValue();
        if (modes.getItem().getItem() instanceof BlockItem item && !worker.level().getBlockState(lastPos).is(item.getBlock())) {
            if (modes == Modes.OBSIDIAN) {
                return VOLCANIC_MASON_PLACE_FLUID;
            }
            return START_WORKING;
        }

        if (!walkToWorkPos(lastPos)) {
            return getState();
        }


        if (mineBlock(lastPos)) {
            building.getModule(BuildingVolcanicMason.LimitedMiningModule.class).mine();
            incrementActionsDoneAndDecSaturation();
            worker.getCitizenExperienceHandler().addExperience(1.0 / 2.0);
            return START_WORKING;
        }

        return getState();
    }

    @Override
    public boolean holdEfficientTool(@NotNull BlockState target, BlockPos pos) {
        final var type = MasonEquipment.PICKAXE.get();
        final int required = WorkerUtil.getCorrectHarvestLevelForBlock(target);
        int bestSlot = -1;
        int bestLevel = Integer.MAX_VALUE;
        for (int slot = 0; slot < worker.getInventoryCitizen().getSlots(); slot++) {
            ItemStack stack = worker.getInventoryCitizen().getStackInSlot(slot);
            int level = type.getMiningLevel(stack);
            if (level >= 0 && level < bestLevel && stack.isCorrectToolForDrops(target)
                && ItemStackUtils.verifyEquipmentLevel(stack, level, required, building.getMaxEquipmentLevel())) {
                bestSlot = slot;
                bestLevel = level;
            }
        }
        if (bestSlot >= 0) {
            CitizenItemUtils.setHeldItem(worker, InteractionHand.MAIN_HAND, bestSlot);
            worker.getCitizenData().setJobStatus(JobStatus.WORKING);
            return true;
        }
        // Use the standard request/retrieval pipeline with the component-aware category.
        checkForToolOrWeapon(type, required);
        return false;
    }

    @Override
    public void onBlockDropReception(final List<ItemStack> blockDrops) {
        super.onBlockDropReception(blockDrops);
        for (final ItemStack stack : blockDrops) {
            building.getModule(BuildingModules.STATS_MODULE).incrementBy(StatisticsConstants.ITEM_OBTAINED + ";" + stack.getItem().getDescriptionId(), stack.getCount());
        }
    }

    @Override
    public boolean shouldSilkTouchBlock(BlockState curBlockState) {
        return true;
    }

    @Override
    public int getBlockMiningTime(@NotNull BlockState state, @NotNull BlockPos pos) {
        if (this.worker.getMainHandItem() == null) {
            return (int) state.getDestroySpeed(this.world, pos);
        } else {
            return MineColonies.getConfig().getServer().pvp_mode.get() ? 250 : this.calculateWorkerMiningDelay(state, pos);
        }
    }

    //TODO increase speed with level
    private int calculateWorkerMiningDelay(@NotNull BlockState state, @NotNull BlockPos pos) {
        double reduction = 1.0 - this.worker.getCitizenColonyHandler().getColonyOrRegister().getResearchManager().getResearchEffects().getEffectStrength(ResearchConstants.BLOCK_BREAK_SPEED);
        return (int)(500.0 * Math.pow(0.85, (double)this.getBreakSpeedLevel() / 2.0) * (double)this.world.getBlockState(pos).getDestroySpeed(this.world, pos) / (double)this.worker.getMainHandItem().getItem().getDestroySpeed(this.worker.getMainHandItem(), state) * reduction);
    }

    @Override
    public Class<BuildingVolcanicMason> getExpectedBuildingClass() {
        return BuildingVolcanicMason.class;
    }
}
