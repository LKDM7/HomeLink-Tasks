package fr.lkdm.homelink.tasks.server;

import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.task.ActivityEntry;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskPriority;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A board written by one run has to come back identical in the next one. */
class TaskStorageFormatTest {
    @Test void legacyArchiveOverflowSurvivesAndCanReturnWhenRoomIsFreed() {
        var board = new TaskBoard(UUID.randomUUID(), "Legacy archives", UUID.randomUUID(), 0);
        for (int i = 0; i < TaskBoard.MAX_CARDS; i++) {
            var card = new TaskCard(UUID.randomUUID(), board.id(), TaskType.MANUAL, "Archive " + i, board.owner(), 0);
            card.setArchived(true); board.addCard(card);
        }
        var tag = TaskStorageFormat.writeBoard(board, REGISTRIES);
        var cards = tag.getList("Cards", net.minecraft.nbt.Tag.TAG_COMPOUND);
        var extra = cards.getCompound(0).copy(); var extraId = UUID.randomUUID();
        extra.putUUID("Id", extraId); extra.putString("Title", "Preserved legacy archive"); cards.add(extra);
        var restored = TaskStorageFormat.readBoard(tag, REGISTRIES).orElseThrow();
        assertEquals(TaskBoard.MAX_CARDS, restored.cards().size());
        assertEquals(1, restored.savedOverflowCards().size());
        assertEquals(extraId, restored.savedOverflowCards().getFirst().id());
        assertEquals(TaskBoard.MAX_CARDS + 1, TaskStorageFormat.writeBoard(roundTrip(restored), REGISTRIES)
                .getList("Cards", net.minecraft.nbt.Tag.TAG_COMPOUND).size());
        restored.removeCard(restored.cards().iterator().next().id());
        var recovered = roundTrip(restored);
        assertTrue(recovered.card(extraId).orElseThrow().archived());
        assertTrue(recovered.savedOverflowCards().isEmpty());
    }
    private static final ResourceLocation RECIPE = ResourceLocation.parse("homecore:cable");
    private static final HolderLookup.Provider REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private final UUID owner = UUID.randomUUID();
    private final UUID editor = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();

    private TaskBoard sample() {
        TaskBoard board = new TaskBoard(UUID.randomUUID(), "Nouvelle ferme", owner, 120L);
        board.setDescription("Automatiser la ferme de blé");
        board.setMember(editor, BoardRole.EDITOR);
        board.setMember(member, BoardRole.MEMBER);
        board.setNetworkId(UUID.randomUUID());

        TaskCard manual = new TaskCard(UUID.randomUUID(), board.id(), TaskType.MANUAL,
                "Construire le bâtiment", owner, 130L);
        manual.setPriority(TaskPriority.HIGH);
        manual.setStatus(TaskStatus.IN_PROGRESS);
        manual.setNodePosition(-40, 75);
        manual.assign(member);
        board.addCard(manual);

        TaskCard craft = new TaskCard(UUID.randomUUID(), board.id(), TaskType.CRAFT, "Câbles", editor, 140L);
        CraftObjective objective = new CraftObjective(new ItemStack(Items.COPPER_INGOT), 64, RECIPE,
                ContributionPolicy.ALL_CONTRIBUTORS, 145L);
        objective.credit(member, 16);
        objective.credit(editor, 8);
        objective.setLockedToRecipe(true);
        objective.setActivationStart(new fr.lkdm.homecore.api.production.ProductionStart(board.id(), 42));
        craft.setObjective(objective);
        craft.setParent(manual.id());
        craft.addDependency(manual.id());
        craft.setNodePosition(120, -30);
        craft.setOrder(3);
        craft.log().recordProgress(member, 16, 150L);
        board.addCard(craft);
        return board;
    }

    private TaskBoard roundTrip(TaskBoard board) {
        return TaskStorageFormat.readBoard(TaskStorageFormat.writeBoard(board, REGISTRIES), REGISTRIES)
                .orElseThrow();
    }

    @Test void aBoardComesBackWithItsMembersAndAttachment() {
        TaskBoard original = sample();
        TaskBoard restored = roundTrip(original);
        assertEquals(original.id(), restored.id());
        assertEquals(original.title(), restored.title());
        assertEquals(original.description(), restored.description());
        assertEquals(original.owner(), restored.owner());
        assertEquals(original.createdTick(), restored.createdTick());
        assertEquals(original.networkId(), restored.networkId());
        assertEquals(BoardRole.OWNER, restored.roleOf(owner).orElseThrow());
        assertEquals(BoardRole.EDITOR, restored.roleOf(editor).orElseThrow());
        assertEquals(BoardRole.MEMBER, restored.roleOf(member).orElseThrow());
        assertEquals(original.revision(), restored.revision());
    }

    @Test void progressAndContributionsSurviveTheRestart() {
        TaskBoard original = sample();
        TaskCard originalCraft = original.cards().stream()
                .filter(card -> card.type() == TaskType.CRAFT).findFirst().orElseThrow();
        TaskCard restored = roundTrip(original).card(originalCraft.id()).orElseThrow();
        CraftObjective objective = restored.objective().orElseThrow();
        assertEquals(64, objective.targetQuantity());
        assertEquals(24, objective.completedQuantity());
        assertEquals(16, objective.contributions().get(member));
        assertEquals(8, objective.contributions().get(editor));
        assertEquals(RECIPE, objective.recipeId());
        assertTrue(objective.lockedToRecipe());
        assertEquals(ContributionPolicy.ALL_CONTRIBUTORS, objective.policy());
        assertEquals(145L, objective.activationTick(), "tracking keeps the moment it started");
        assertEquals(new fr.lkdm.homecore.api.production.ProductionStart(original.id(), 42), objective.activationStart().orElseThrow());
        assertTrue(ItemStack.isSameItemSameComponents(new ItemStack(Items.COPPER_INGOT), objective.target()));
    }

    @Test void theGraphAndTheMindMapPositionsAreKept() {
        TaskBoard original = sample();
        TaskCard originalManual = original.cards().stream()
                .filter(card -> card.type() == TaskType.MANUAL).findFirst().orElseThrow();
        TaskCard originalCraft = original.cards().stream()
                .filter(card -> card.type() == TaskType.CRAFT).findFirst().orElseThrow();
        TaskBoard restored = roundTrip(original);
        TaskCard manual = restored.card(originalManual.id()).orElseThrow();
        TaskCard craft = restored.card(originalCraft.id()).orElseThrow();
        assertEquals(-40, manual.nodeX());
        assertEquals(75, manual.nodeY());
        assertEquals(120, craft.nodeX());
        assertEquals(-30, craft.nodeY());
        assertEquals(java.util.Optional.of(manual.id()), craft.parent());
        assertTrue(craft.dependencies().contains(manual.id()));
        assertEquals(3, craft.order());
    }

    @Test void columnsAndAssignmentsAreKept() {
        TaskBoard original = sample();
        TaskCard originalManual = original.cards().stream()
                .filter(card -> card.type() == TaskType.MANUAL).findFirst().orElseThrow();
        TaskCard manual = roundTrip(original).card(originalManual.id()).orElseThrow();
        assertEquals(TaskStatus.IN_PROGRESS, manual.status());
        assertEquals(TaskPriority.HIGH, manual.priority());
        assertTrue(manual.assignees().contains(member));
        assertEquals(originalManual.revision(), manual.revision());
    }

    @Test void theBoundedHistoryIsKeptWithItsGroupedProgress() {
        TaskBoard original = sample();
        TaskCard originalCraft = original.cards().stream()
                .filter(card -> card.type() == TaskType.CRAFT).findFirst().orElseThrow();
        TaskCard craft = roundTrip(original).card(originalCraft.id()).orElseThrow();
        var progress = craft.log().entries().stream()
                .filter(entry -> entry.kind() == ActivityEntry.Kind.PROGRESS).toList();
        assertEquals(1, progress.size());
        assertEquals(16, progress.getFirst().amount());
        assertEquals(java.util.Optional.of(member), progress.getFirst().actor());
    }

    @Test void aMalformedBoardIsSkippedInsteadOfFailingTheWholeFile() {
        var broken = new net.minecraft.nbt.CompoundTag();
        broken.putString("Title", "No identity");
        assertTrue(TaskStorageFormat.readBoard(broken, REGISTRIES).isEmpty());
    }

    @Test void aCardWhoseItemNoLongerResolvesKeepsExistingAsAManualEntry() {
        TaskBoard board = new TaskBoard(UUID.randomUUID(), "Atelier", owner, 0L);
        TaskCard craft = new TaskCard(UUID.randomUUID(), board.id(), TaskType.CRAFT, "Composant disparu", owner, 10L);
        craft.setObjective(new CraftObjective(new ItemStack(Items.COPPER_INGOT), 8, RECIPE,
                ContributionPolicy.ASSIGNEES_ONLY, 10L));
        board.addCard(craft);
        var tag = TaskStorageFormat.writeBoard(board, REGISTRIES);
        // Simulate the item disappearing with the mod that provided it.
        tag.getList("Cards", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0)
                .getCompound("Objective").put("Target", new net.minecraft.nbt.CompoundTag());
        TaskBoard restored = TaskStorageFormat.readBoard(tag, REGISTRIES).orElseThrow();
        TaskCard kept = restored.card(craft.id()).orElseThrow();
        assertTrue(kept.objective().isEmpty(), "the objective cannot be restored");
        assertEquals("Composant disparu", kept.title(), "but the card itself is never silently deleted");
    }
}
