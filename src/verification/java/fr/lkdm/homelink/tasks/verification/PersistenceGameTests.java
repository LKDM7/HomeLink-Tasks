package fr.lkdm.homelink.tasks.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.server.TaskSavedData;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(TasksValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PersistenceGameTests {
    private static final UUID OWNER = UUID.fromString("dc37728c-72d9-40cc-898b-13980c2b7352");
    private static final UUID MEMBER = UUID.fromString("64194630-494c-4f2a-aa73-320449e72a49");
    private static final UUID BOARD = UUID.fromString("de4c4c1a-5da0-4cf0-845d-1780c39a566e");
    private static final UUID CARD = UUID.fromString("53a2db80-c90e-47a9-b47b-a0a43df5d24b");
    private static final UUID RECEIPT = UUID.fromString("e8ab283a-65a3-45cf-a24c-a0d181ce8fb2");

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void boardAndPinsSurviveTwoServerProcesses(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        TaskSavedData data = TaskSavedData.get(server);
        String pass = System.getProperty("tasks.persistencePass", "");
        if (pass.equals("write")) {
            data.removeBoard(BOARD);
            TaskBoard board = new TaskBoard(BOARD, "Persistent project", OWNER, 100);
            board.setMember(MEMBER, BoardRole.EDITOR);
            TaskCard card = new TaskCard(CARD, BOARD, TaskType.CRAFT, "Copper", OWNER, 110);
            var objective = new CraftObjective(new ItemStack(Items.COPPER_INGOT), 64,
                    ResourceLocation.parse("minecraft:copper_ingot"), ContributionPolicy.ALL_CONTRIBUTORS, 110);
            objective.credit(MEMBER, 16); card.setObjective(objective); card.setNodePosition(-40, 120);
            objective.setActivationStart(new fr.lkdm.homecore.api.production.ProductionStart(BOARD, 42));
            board.addCard(card); data.addBoard(board); data.setPinned(MEMBER, CARD, true, 3);
            data.setTrackedCard(MEMBER, CARD); data.deduplicator().claim(RECEIPT); data.setDirty();
            server.saveEverything(false, true, true);
            LogUtils.getLogger().info("TASKS_PERSISTENCE_WRITE_OK board={}", BOARD);
        } else if (pass.equals("read")) {
            var board = data.board(BOARD).orElseThrow();
            var card = board.card(CARD).orElseThrow();
            helper.assertTrue(board.roleOf(MEMBER).orElseThrow() == BoardRole.EDITOR, "Member role lost");
            helper.assertTrue(card.nodeX() == -40 && card.nodeY() == 120, "Mind map position lost");
            helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 16, "Progress lost");
            helper.assertTrue(card.objective().orElseThrow().activationStart().orElseThrow().epoch().equals(BOARD)
                    && card.objective().orElseThrow().activationStart().orElseThrow().ordinal() == 42, "Durable activation order lost");
            helper.assertTrue(data.pins(MEMBER).contains(CARD) && data.trackedCard(MEMBER).filter(CARD::equals).isPresent(), "Personal choices lost");
            helper.assertTrue(data.deduplicator().seen(RECEIPT), "Receipt identity lost");
            LogUtils.getLogger().info("TASKS_PERSISTENCE_READ_OK board={}", BOARD);
        }
        helper.succeed();
    }
}
