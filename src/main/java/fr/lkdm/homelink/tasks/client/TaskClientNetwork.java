package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.network.CardView;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import fr.lkdm.homelink.tasks.task.TaskPriority;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends this client's requests to the server.
 *
 * <p>Every mutation carries the revision the client believed it was editing, so the
 * server can answer with an explicit conflict rather than silently overwriting somebody
 * else's edit. None of these calls decides anything by itself.</p>
 */
public final class TaskClientNetwork {
    private TaskClientNetwork() { }

    public static void expand(UUID board, UUID card, int ingredient, ItemStack variant, ResourceLocation recipe) {
        send(TaskPackets.CardCommand.EXPAND_INGREDIENT, board, card, "", Optional.empty(), TaskStatus.TODO,
                TaskPriority.NORMAL, ingredient, 0, 0, variant, Optional.of(recipe), true);
    }
    public static void expand(UUID board, UUID card, int ingredient, ItemStack variant, ResourceLocation recipe, long revision) {
        PacketDistributor.sendToServer(new TaskPackets.CardRequest(TaskPackets.CardCommand.EXPAND_INGREDIENT, board,
                Optional.of(card), "", Optional.empty(), TaskStatus.TODO, TaskPriority.NORMAL, ingredient, 0, 0,
                variant, Optional.of(recipe), true, revision));
    }

    /** Asks to move a card to another column and position.
     * @param board board the card belongs to
     * @param card card to move
     * @param status requested column
     * @param order requested position in that column
     */
    public static void move(UUID board, UUID card, TaskStatus status, int order) {
        send(TaskPackets.CardCommand.MOVE, board, card, "", Optional.empty(), status, TaskPriority.NORMAL,
                order, 0, 0, ItemStack.EMPTY, Optional.empty(), false);
    }

    /** Asks to move a mind map node.
     * @param board board the card belongs to
     * @param card card to move
     * @param x requested horizontal position
     * @param y requested vertical position
     */
    public static void moveNode(UUID board, UUID card, int x, int y) {
        send(TaskPackets.CardCommand.NODE, board, card, "", Optional.empty(), TaskStatus.TODO,
                TaskPriority.NORMAL, 0, x, y, ItemStack.EMPTY, Optional.empty(), false);
    }

    public static void moveNode(UUID board, UUID card, int x, int y, long revision) {
        PacketDistributor.sendToServer(new TaskPackets.CardRequest(TaskPackets.CardCommand.NODE, board, Optional.of(card), "",
                Optional.empty(), TaskStatus.TODO, TaskPriority.NORMAL, 0, x, y, ItemStack.EMPTY, Optional.empty(), false, revision));
    }

    /** Asks for a card change that carries only a flag or a text.
     * @param command what is being asked
     * @param board board the card belongs to
     * @param card card concerned
     * @param text new title or description
     * @param flag lock, archive or policy flag
     */
    public static void card(TaskPackets.CardCommand command, UUID board, UUID card, String text, boolean flag) {
        send(command, board, card, text, Optional.empty(), TaskStatus.TODO, TaskPriority.NORMAL,
                0, 0, 0, ItemStack.EMPTY, Optional.empty(), flag);
    }

    /** Asks to change a card's priority.
     * @param board board the card belongs to
     * @param card card concerned
     * @param priority requested priority
     */
    public static void priority(UUID board, UUID card, TaskPriority priority) {
        send(TaskPackets.CardCommand.PRIORITY, board, card, "", Optional.empty(), TaskStatus.TODO,
                priority, 0, 0, 0, ItemStack.EMPTY, Optional.empty(), false);
    }

    /** Asks to link or unlink two cards.
     * @param command parent or dependency command
     * @param board board both cards belong to
     * @param card card the link starts at
     * @param other card the link points to, or null to detach a parent
     */
    public static void link(TaskPackets.CardCommand command, UUID board, UUID card, UUID other) {
        send(command, board, card, "", Optional.ofNullable(other), TaskStatus.TODO, TaskPriority.NORMAL,
                0, 0, 0, ItemStack.EMPTY, Optional.empty(), false);
    }

    /** Asks to assign or unassign a member.
     * @param command assign or unassign
     * @param board board the card belongs to
     * @param card card concerned
     * @param member member concerned
     */
    public static void assignment(TaskPackets.CardCommand command, UUID board, UUID card, UUID member) {
        send(command, board, card, "", Optional.of(member), TaskStatus.TODO, TaskPriority.NORMAL,
                0, 0, 0, ItemStack.EMPTY, Optional.empty(), false);
    }

    /** Asks to change the reference recipe of a crafting objective.
     * @param board board the card belongs to
     * @param card card concerned
     * @param recipe recipe identifier
     */
    public static void selectRecipe(UUID board, UUID card, ResourceLocation recipe) {
        send(TaskPackets.CardCommand.SELECT_RECIPE, board, card, "", Optional.empty(), TaskStatus.TODO,
                TaskPriority.NORMAL, 0, 0, 0, ItemStack.EMPTY, Optional.of(recipe), false);
    }

    /** Asks to change the wanted quantity without discarding progress.
     * @param board board the card belongs to
     * @param card card concerned
     * @param quantity requested quantity
     */
    public static void quantity(UUID board, UUID card, int quantity) {
        send(TaskPackets.CardCommand.SET_QUANTITY, board, card, "", Optional.empty(), TaskStatus.TODO,
                TaskPriority.NORMAL, quantity, 0, 0, ItemStack.EMPTY, Optional.empty(), false);
    }

    /** Asks to create a free task.
     * @param board board to create it on
     * @param title card label
     */
    public static void createManual(UUID board, String title) {
        sendNew(TaskPackets.CardCommand.CREATE_MANUAL, board, title, 0, ItemStack.EMPTY,
                Optional.empty(), false);
    }

    /** Asks to create a crafting objective.
     * @param board board to create it on
     * @param title card label
     * @param target wanted item
     * @param quantity finished items wanted
     * @param recipe reference recipe
     * @param allContributors whether every contributing member may advance it
     */
    public static void createCraft(UUID board, String title, ItemStack target, int quantity,
                                   ResourceLocation recipe, boolean allContributors) {
        sendNew(TaskPackets.CardCommand.CREATE_CRAFT, board, title, quantity, target,
                Optional.of(recipe), allContributors);
    }

    /** Asks for a personal choice that affects only this player.
     * @param command pin, track or plan request
     * @param board board the card belongs to
     * @param card card concerned
     */
    public static void personal(TaskPackets.PersonalCommand command, UUID board, UUID card) {
        if (command == TaskPackets.PersonalCommand.REQUEST_PLAN && !ClientTaskState.claimPlanRequest()) return;
        PacketDistributor.sendToServer(new TaskPackets.PersonalRequest(command, board, card));
    }

    /** Asks to create a board owned by this player.
     * @param title board label
     */
    public static void createBoard(String title) {
        PacketDistributor.sendToServer(new TaskPackets.BoardRequest(TaskPackets.BoardCommand.CREATE,
                Optional.empty(), title, Optional.empty(), BoardRole.VIEWER, false, ClientTaskState.screen(), 0L));
    }

    /** Asks for a board-level change.
     * @param command what is being asked
     * @param board board concerned
     * @param text new title or description
     * @param target member concerned, or null
     * @param role role to grant
     * @param flag archive or attach flag
     * @param revision revision this client believed it was editing
     */
    public static void board(TaskPackets.BoardCommand command, UUID board, String text, UUID target,
                             BoardRole role, boolean flag, long revision) {
        Optional<BlockPos> screen = ClientTaskState.screen();
        PacketDistributor.sendToServer(new TaskPackets.BoardRequest(command, Optional.of(board), text,
                Optional.ofNullable(target), role, flag, screen, revision));
    }

    private static void sendNew(TaskPackets.CardCommand command, UUID board, String title, int quantity,
                                ItemStack target, Optional<ResourceLocation> recipe, boolean flag) {
        PacketDistributor.sendToServer(new TaskPackets.CardRequest(command, board, Optional.empty(), title,
                Optional.empty(), TaskStatus.TODO, TaskPriority.NORMAL, quantity, 0, 0, target, recipe, flag, 0L));
    }

    private static void send(TaskPackets.CardCommand command, UUID board, UUID card, String text,
                             Optional<UUID> target, TaskStatus status, TaskPriority priority, int quantity,
                             int nodeX, int nodeY, ItemStack item, Optional<ResourceLocation> recipe,
                             boolean flag) {
        long revision = ClientTaskState.card(card).map(CardView::revision).orElse(0L);
        PacketDistributor.sendToServer(new TaskPackets.CardRequest(command, board, Optional.of(card), text,
                target, status, priority, quantity, nodeX, nodeY, item, recipe, flag, revision));
    }
}
