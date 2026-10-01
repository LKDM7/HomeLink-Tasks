package fr.lkdm.homelink.tasks.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlock;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlockEntity;
import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.client.*;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import fr.lkdm.homelink.tasks.registry.TaskRegistries;
import fr.lkdm.homelink.tasks.server.TaskSavedData;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/** Real rendered client, real packets and integrated server. Never included in the release. */
@EventBusSubscriber(modid = TasksValidation.MOD_ID, value = Dist.CLIENT)
public final class InGameValidation {
    private record Step(String name, Runnable action, BooleanSupplier ready, int delay) { }
    private static final ArrayDeque<Step> STEPS = new ArrayDeque<>();
    private static boolean started, queued, finished, entered;
    private static int age;
    private static int waiting;
    private static long deadline;
    private static UUID board, manual, craft;
    private static UUID guest;
    private static long staleRevision;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static volatile boolean serverDone;
    private static volatile Throwable failure;
    private static final BlockPos ORIGIN = new BlockPos(1024, -58, 1024);
    private static final ResourceLocation RECIPE = ResourceLocation.withDefaultNamespace("crafting_table");

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        boolean guestMode = Boolean.getBoolean("tasks.inGameGuest");
        if ((!Boolean.getBoolean("tasks.inGame") && !guestMode) || finished) return;
        Minecraft c = Minecraft.getInstance();
        c.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
        try {
            if (exists("abort")) throw new IllegalStateException("Other validation client failed");
            if (!started) {
                if (++waiting % 100 == 0) {
                    LogUtils.getLogger().info("TASKS_WAITING screen={} exchange={} exists={}", c.screen == null ? "null" : c.screen.getClass().getName(),
                            System.getProperty("tasks.exchange"), exists("host"));
                    capture(c, "startup");
                }
                if (c.screen instanceof AccessibilityOnboardingScreen onboarding) { onboarding.onClose(); return; }
                if (!(c.screen instanceof TitleScreen)) return;
                if (guestMode) {
                    if (!exists("host")) return;
                    String[] details = read("host").split("\\n");
                    board = UUID.fromString(details[1]); manual = UUID.fromString(details[2]); craft = UUID.fromString(details[3]);
                    String address = "127.0.0.1:" + details[0];
                    net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(), c,
                            net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),
                            new net.minecraft.client.multiplayer.ServerData("Tasks validation", address,
                                    net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
                    started = true; deadline = System.nanoTime() + 180_000_000_000L; return;
                }
                started = true;
                if (Boolean.getBoolean("tasks.userReview")) {
                    c.createWorldOpenFlows().openWorld("UserReview", () -> { throw new IllegalStateException("Cannot load copied user world"); });
                    deadline = System.nanoTime() + 180_000_000_000L;
                    return;
                }
                var rules = new GameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, null);
                c.createWorldOpenFlows().createFreshLevel("tasks-validation-" + System.currentTimeMillis(),
                        new LevelSettings("Tasks validation", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                                rules, WorldDataConfiguration.DEFAULT), new WorldOptions(0, false, false),
                        registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                                .value().createWorldDimensions(), new TitleScreen());
                deadline = System.nanoTime() + 180_000_000_000L;
                return;
            }
            if (failure != null) throw new IllegalStateException("Server check failed", failure);
            if (System.nanoTime() > deadline) throw new IllegalStateException("Timeout: " + (STEPS.peek() == null ? "world" : STEPS.peek().name()));
            if (!queued) {
                if (c.player == null || (!guestMode && c.getSingleplayerServer() == null) || c.screen != null) return;
                queued = true;
                if (guestMode) enqueueGuest(c); else enqueue(c);
            }
            Step step = STEPS.peek();
            if (step == null) {
                LogUtils.getLogger().info(guestMode ? "TASKS_GUEST_OK" : "TASKS_IN_GAME_OK");
                shutdown(c); return;
            }
            if (!entered) {
                entered = true; age = 0; deadline = System.nanoTime() + 120_000_000_000L;
                LogUtils.getLogger().info("TASKS_IN_GAME_STEP {}", step.name());
                step.action().run();
            }
            if (++age >= step.delay() && step.ready().getAsBoolean()) { STEPS.remove(); entered = false; }
        } catch (Throwable problem) {
            LogUtils.getLogger().error("TASKS_IN_GAME_FAILED", problem);
            shutdown(c);
        }
    }


    private static void enqueueUserReview(Minecraft c) {
        BlockPos pos = new BlockPos(8, -59, 0);
        add("inspect saved physical display", () -> server(c, () -> {
            var p = c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID());
            var display = (TaskDisplayBlockEntity)p.serverLevel().getBlockEntity(pos);
            require(display != null, "Saved task display missing");
            var facing = display.getBlockState().getValue(TaskDisplayBlock.FACING);
            var eye = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(facing.getNormal()).scale(3));
            p.teleportTo(eye.x, eye.y - p.getEyeHeight(), eye.z);
            p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
            require(display.owner().filter(p.getUUID()::equals).isPresent(), "Review must run as actual owner");
            require(display.selectedBoard().isEmpty() && display.networkId().isEmpty(), "Expected unconfigured saved screen");
            require(fr.lkdm.homelink.tasks.server.DisplayQueries.describe(p, pos).state() == fr.lkdm.homelink.tasks.network.DisplayPackets.State.NO_PROJECT, "Unconfigured screen reported private");
            board = TaskSavedData.get(p.server).boards().stream().filter(b -> b.title().equals("dtedhcnje,k")).findFirst().orElseThrow().id();
        }), () -> serverDone);
        add("look at saved display", () -> {
            c.mouseHandler.releaseMouse();
            c.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
            c.options.hideGui = true;
        }, () -> DisplayClientState.state(pos) == fr.lkdm.homelink.tasks.network.DisplayPackets.State.NO_PROJECT);
        add("capture unconfigured display", () -> { capture(c, "user-unconfigured"); c.options.hideGui = false; }, () -> true);
        add("open saved display through right click", () -> c.gameMode.useItemOn(c.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false)), () -> c.screen instanceof BoardListScreen);
        add("open physical network settings", () -> press(c, "screen_network"), () -> c.screen instanceof ScreenNetworkScreen);
        add("connect to existing HomeLink server", () -> press(c, "connect_screen"), () -> button(c, "disconnect_screen").active);
        add("capture actual network binding", () -> capture(c, "user-network-connected"), () -> true);
        add("verify API membership", () -> server(c, () -> {
            var p = c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID());
            var display = (TaskDisplayBlockEntity)p.serverLevel().getBlockEntity(pos);
            var network = fr.lkdm.homecore.api.DashboardAPI.networks(p.server).getNetworksForPlayer(p.getUUID()).getFirst();
            require(display.networkId().filter(network.id()::equals).isPresent(), "Physical binding was not persisted");
            require(network.devices().contains(display.id()), "HomeCore membership missing");
            require(fr.lkdm.homecore.api.DashboardAPI.providers().discover(display).isPresent(), "Connector provider missing");
        }), () -> serverDone);
        add("return to saved projects", () -> press(c, "back"), () -> c.screen instanceof BoardListScreen);
        add("select real project through request", () -> TaskClientNetwork.board(TaskPackets.BoardCommand.OPEN, board, "", null, BoardRole.OWNER, false, 0), () -> ClientTaskState.board().isPresent());
        add("verify project selected on physical display", () -> server(c, () -> {
            var p = c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID());
            var display = (TaskDisplayBlockEntity)p.serverLevel().getBlockEntity(pos);
            require(display.selectedBoard().filter(board::equals).isPresent(), "Opening project did not select display content");
            require(fr.lkdm.homelink.tasks.server.DisplayQueries.describe(p, pos).view().isPresent(), "Owner cannot view selected project");
        }), () -> serverDone);
        add("view saved project on wall", () -> { c.setScreen(null); c.mouseHandler.releaseMouse(); c.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos)); c.options.hideGui = true; }, () -> DisplayClientState.view(pos).isPresent());
        add("capture fixed physical display", () -> capture(c, "user-project-visible"), () -> true);
    }

    private static void enqueue(Minecraft c) {
        if (Boolean.getBoolean("tasks.userReview")) { enqueueUserReview(c); return; }
        if (Boolean.getBoolean("tasks.guiReview")) { enqueueGuiReview(c); return; }
        add("create board over transport", () -> TaskClientNetwork.createBoard("Atelier de validation — projet partagé"),
                () -> ClientTaskState.board().isPresent());
        add("open manual form from primary action", () -> {
            board = ClientTaskState.board().orElseThrow().id();
            press(c, "new_card");
            field(c, 0).setValue("Construire l'atelier principal et vérifier les accès");
            press(c, "create");
        }, () -> ClientTaskState.board().orElseThrow().cards().size() == 1);
        add("open craft form with empty hand", () -> {
            manual = ClientTaskState.board().orElseThrow().cards().getFirst().id();
            require(c.player.getMainHandItem().isEmpty(), "item selector must work empty handed");
            press(c, "new_card"); press(c, "type_craft"); press(c, "choose_item");
        }, () -> c.screen instanceof ItemPickerScreen);
        add("search item by localized name through typing", () -> {
            String query = new ItemStack(Items.CRAFTING_TABLE).getHoverName().getString();
            for (char ch : query.toCharArray()) c.screen.charTyped(ch, 0);
            require(field(c, 0).getValue().equals(query), "search lost input/cursor");
        }, () -> true);
        add("capture item selection", () -> capture(c, "ux-item-search"), () -> true);
        add("select crafting table", () -> pressLabel(c, new ItemStack(Items.CRAFTING_TABLE).getHoverName().getString()), () -> c.screen instanceof CardEditorScreen);
        add("quantity validation", () -> {
            field(c, 1).setValue("0"); require(!button(c, "create").active, "zero quantity accepted");
            field(c, 1).setValue("16"); require(button(c, "create").active, "valid form disabled");
            field(c, 0).setValue("Fabriquer les établis de l'atelier");
        }, () -> true);
        add("capture populated craft form", () -> capture(c, "ux-craft-form"), () -> true);
        add("preview recipe without losing the draft", () -> {
            var recipeButton = c.screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("screen.homelink_tasks.recipe_selection", 1, 1).getString())).findFirst().orElseThrow();
            ((net.minecraft.client.gui.components.Button)recipeButton).onPress();
        }, () -> c.screen instanceof RecipePickerScreen);
        add("cancel recipe preview retains draft", () -> {
            c.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
            require(c.screen instanceof CardEditorScreen && field(c, 1).getValue().equals("16") && field(c, 0).getValue().equals("Fabriquer les établis de l'atelier"), "recipe preview lost draft");
        }, () -> true);
        add("create crafting objective through form", () -> press(c, "create"),
                () -> ClientTaskState.board().orElseThrow().cards().size() == 2);
        add("assert chosen output and quantity", () -> {
            var objective = ClientTaskState.board().orElseThrow().cards().stream().filter(v -> v.objective().isPresent()).findFirst().orElseThrow().objective().orElseThrow();
            require(objective.target().is(Items.CRAFTING_TABLE) && objective.targetQuantity() == 16, "form submitted wrong output/quantity");
            require(objective.completedQuantity() == 0, "creating task credited stock");
            LogUtils.getLogger().info("TASKS_UX_ITEM_SELECTION_OK");
        }, () -> ClientTaskState.board().orElseThrow().cards().size() == 2);
        add("choose an alternative recipe through its preview", () -> {
            var recipes = ClientRecipeLookup.alternatives(new ItemStack(Items.STICK));
            require(recipes.size() > 1, "fixture needs multiple stick recipes");
            press(c, "new_card"); press(c, "type_craft"); press(c, "choose_item");
            field(c, 0).setValue(new ItemStack(Items.STICK).getHoverName().getString());
            pressLabel(c, new ItemStack(Items.STICK).getHoverName().getString());
            field(c, 0).setValue("UX recipe variants");
            pressLabel(c, net.minecraft.network.chat.Component.translatable("screen.homelink_tasks.recipe_selection", 1, recipes.size()).getString());
            press(c, "next"); press(c, "use_recipe"); press(c, "create");
        }, () -> ClientTaskState.board().orElseThrow().cards().size() == 3);
        add("verify recipe choice then remove fixture", () -> {
            var temporary = ClientTaskState.board().orElseThrow().cards().stream().filter(card -> card.title().equals("UX recipe variants")).findFirst().orElseThrow();
            require(temporary.objective().orElseThrow().recipeId().equals(ClientRecipeLookup.alternatives(new ItemStack(Items.STICK)).get(1).recipeId()), "recipe preview selection ignored");
            TaskClientNetwork.card(TaskPackets.CardCommand.DELETE, board, temporary.id(), "", true);
        }, () -> ClientTaskState.board().orElseThrow().cards().size() == 2);
        add("pin actual objective", () -> {
            craft = ClientTaskState.board().orElseThrow().cards().stream().filter(v -> v.objective().isPresent()).findFirst().orElseThrow().id();
            c.setScreen(new CardDetailScreen(board, craft)); press(c, "pin");
        }, () -> ClientTaskState.pinned().stream().anyMatch(v -> v.card().equals(craft)));
        add("track independent from pin", () -> press(c, "track"),
                () -> ClientTaskState.tracking(craft));
        add("drag manual card through real screen input", () -> {
            BoardScreen.openOrRefresh();
            var screen = (TaskScreen)c.screen;
            c.screen.mouseClicked(screen.guiLeft() + 30, screen.guiTop() + 100, 0);
            c.screen.mouseDragged(screen.guiLeft() + c.screen.width / 2.0, screen.guiTop() + 100, 0, 150, 0);
            c.screen.mouseReleased(screen.guiLeft() + c.screen.width / 2.0, screen.guiTop() + 100, 0);
        }, () -> ClientTaskState.card(manual).orElseThrow().status() == TaskStatus.IN_PROGRESS);
        add("reject unfinished craft DONE", () -> TaskClientNetwork.move(board, craft, TaskStatus.DONE, 0),
                () -> ClientTaskState.card(craft).orElseThrow().status() != TaskStatus.DONE);
        add("mindmap transport", () -> TaskClientNetwork.moveNode(board, manual, 35, 60),
                () -> ClientTaskState.card(manual).orElseThrow().nodeX() == 35);
        add("dependency transport", () -> TaskClientNetwork.link(TaskPackets.CardCommand.ADD_DEPENDENCY, board, craft, manual),
                () -> ClientTaskState.card(craft).orElseThrow().dependencies().contains(manual));
        add("keyboard move to DONE", () -> {
            BoardScreen.openOrRefresh();
            var screen = (TaskScreen)c.screen;
            c.screen.mouseClicked(screen.guiLeft() + c.screen.width / 2.0, screen.guiTop() + 100, 0);
            c.screen.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0); c.screen.mouseReleased(1, 1, 0);
        }, () -> ClientTaskState.card(manual).orElseThrow().status() == TaskStatus.DONE);
        add("keyboard move back", () -> c.screen.keyPressed(GLFW.GLFW_KEY_LEFT, 0, 0),
                () -> ClientTaskState.card(manual).orElseThrow().status() == TaskStatus.IN_PROGRESS);
        add("edit title and description with one Save", () -> {
            c.setScreen(new CardActionsScreen(board, manual)); field(c, 0).setValue("Atelier — accès vérifiés"); field(c, 1).setValue("Texte conservé après un retour et un changement de taille.");
            press(c, "team"); press(c, "details");
            c.screen.resize(c, c.screen.width, c.screen.height);
            require(field(c, 0).getValue().equals("Atelier — accès vérifiés"), "editing lost draft on resize");
            press(c, "save");
        }, () -> ClientTaskState.card(manual).orElseThrow().title().equals("Atelier — accès vérifiés") && ClientTaskState.card(manual).orElseThrow().description().startsWith("Texte conservé"));
        add("complete manual task from detail", () -> press(c, "complete"), () -> ClientTaskState.card(manual).orElseThrow().status() == TaskStatus.DONE);
        add("reopen manual task from detail", () -> press(c, "reopen_task"), () -> ClientTaskState.card(manual).orElseThrow().status() == TaskStatus.TODO);
        add("restore manual workflow", () -> TaskClientNetwork.move(board, manual, TaskStatus.IN_PROGRESS, 0), () -> ClientTaskState.card(manual).orElseThrow().status() == TaskStatus.IN_PROGRESS);
        add("temporary task for deletion", () -> { BoardScreen.openOrRefresh(); press(c, "new_card"); field(c, 0).setValue("Temporary UX deletion"); press(c, "create"); }, () -> ClientTaskState.board().orElseThrow().cards().size() == 3);
        add("archive task through advanced settings", () -> {
            var temporary = ClientTaskState.board().orElseThrow().cards().stream().filter(card -> card.title().equals("Temporary UX deletion")).findFirst().orElseThrow();
            c.setScreen(new CardActionsScreen(board, temporary.id())); press(c, "advanced"); press(c, "archive_card");
        }, () -> ClientTaskState.board().orElseThrow().cards().stream().anyMatch(card -> card.title().equals("Temporary UX deletion") && card.archived()));
        add("restore through the archived task list", () -> {
            c.setScreen(new BoardSettingsScreen(board)); press(c, "advanced"); press(c, "archived_tasks"); pressLabel(c, "Temporary UX deletion");
            press(c, "edit_card"); press(c, "advanced"); press(c, "restore_task");
        }, () -> ClientTaskState.board().orElseThrow().cards().stream().anyMatch(card -> card.title().equals("Temporary UX deletion") && !card.archived()));
        add("cancel deletion preserves task", () -> {
            var temporary = ClientTaskState.board().orElseThrow().cards().stream().filter(card -> card.title().equals("Temporary UX deletion")).findFirst().orElseThrow();
            c.setScreen(new CardActionsScreen(board, temporary.id())); press(c, "advanced"); press(c, "delete_card");
            pressLabel(c, net.minecraft.network.chat.Component.translatable("gui.no").getString());
            require(ClientTaskState.board().orElseThrow().cards().size() == 3, "cancel deleted task");
        }, () -> true);
        add("confirm deletion removes only selected task", () -> {
            press(c, "delete_card"); pressLabel(c, net.minecraft.network.chat.Component.translatable("gui.yes").getString());
        }, () -> ClientTaskState.board().orElseThrow().cards().size() == 2);
        add("inventory availability fixture", () -> server(c, () -> {
            c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID()).getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 64));
        }), () -> serverDone);
        add("green inventory plan", () -> c.setScreen(new RecipeScreen(board, craft)), () -> planIs(fr.lkdm.homelink.tasks.stock.AvailabilityState.READY));
        add("capture green plan", () -> capture(c, "stock-green"), () -> true);
        add("prepare actual crafting menu", () -> server(c, () -> {
            var player = c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID());
            player.getInventory().clearContent();
            var table = player.blockPosition().east(2);
            player.serverLevel().setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
            player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, actor) -> new net.minecraft.world.inventory.CraftingMenu(id, inventory,
                    net.minecraft.world.inventory.ContainerLevelAccess.create(player.serverLevel(), table)), net.minecraft.network.chat.Component.literal("Validation craft")));
            for (int slot : new int[]{1, 2, 4, 5}) player.containerMenu.getSlot(slot).set(new ItemStack(Items.OAK_PLANKS));
            player.containerMenu.broadcastChanges();
        }), () -> serverDone && c.screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen
                && !c.player.containerMenu.getSlot(0).getItem().isEmpty());
        add("real client takes crafted output", () -> c.gameMode.handleInventoryMouseClick(c.player.containerMenu.containerId, 0, 0,
                net.minecraft.world.inventory.ClickType.PICKUP, c.player), () -> ClientTaskState.card(craft).orElseThrow().objective().orElseThrow().completedQuantity() == 1);
        add("close craft and reopen board", () -> { c.player.closeContainer(); TaskClientNetwork.board(TaskPackets.BoardCommand.OPEN, board, "", null, BoardRole.OWNER, false, 0); },
                () -> c.screen instanceof BoardScreen);
        add("red missing plan", () -> c.setScreen(new RecipeScreen(board, craft)), () -> planIs(fr.lkdm.homelink.tasks.stock.AvailabilityState.MISSING));
        add("capture red plan", () -> capture(c, "stock-red"), () -> true);
        add("setup real Storage source", () -> server(c, () -> storage(c, "setup", new Class<?>[]{net.minecraft.server.MinecraftServer.class, UUID.class, UUID.class},
                c.getSingleplayerServer(), c.player.getUUID(), board)), () -> serverDone);
        add("orange actual Storage plan", () -> TaskClientNetwork.board(TaskPackets.BoardCommand.OPEN, board, "", null, BoardRole.OWNER, false, 0), () -> true);
        add("request Storage plan", () -> c.setScreen(new RecipeScreen(board, craft)), () -> planIs(fr.lkdm.homelink.tasks.stock.AvailabilityState.IN_STORAGE));
        add("capture orange plan", () -> capture(c, "stock-orange"), () -> true);
        add("power off real Storage", () -> server(c, () -> storage(c, "setPowered", new Class<?>[]{net.minecraft.server.MinecraftServer.class, boolean.class}, c.getSingleplayerServer(), false)), () -> serverDone);
        add("gray unavailable plan", () -> {}, () -> planIs(fr.lkdm.homelink.tasks.stock.AvailabilityState.UNVERIFIED));
        add("capture gray plan", () -> capture(c, "stock-gray"), () -> true);
        add("power back real Storage", () -> server(c, () -> storage(c, "setPowered", new Class<?>[]{net.minecraft.server.MinecraftServer.class, boolean.class}, c.getSingleplayerServer(), true)), () -> serverDone);
        add("power recovery plan", () -> {}, () -> planIs(fr.lkdm.homelink.tasks.stock.AvailabilityState.IN_STORAGE));
        for (String language : new String[]{"fr_fr", "en_us"}) {
        add("language " + language, () -> {
            c.getLanguageManager().setSelected(language); c.options.languageCode = language;
            reload = c.reloadResourcePacks();
        }, () -> reload.isDone());
        for (int scale : new int[]{2, 4}) {
            add("GUI size " + scale, () -> {
                GLFW.glfwSetWindowSize(c.getWindow().getWindow(), scale == 2 ? 1280 : 640, scale == 2 ? 720 : 480);
                c.options.guiScale().set(2); c.resizeDisplay();
            }, () -> true);
            for (int screen = 0; screen < 23; screen++) {
                int index = screen;
                add("open screen " + screen + " scale " + scale, () -> open(c, index), () -> true);
                add("capture screen " + screen + " scale " + scale, () -> capture(c, language + "-gui-" + scale + "-" + index), () -> true);
            }
        }
        }
        add("prepare real display fixtures", () -> server(c, () -> {
            var level = c.getSingleplayerServer().overworld();
            var player = c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID());
            level.setDayTime(6000);
            var blocks = java.util.List.of(TaskRegistries.TASK_DISPLAY.get(), TaskRegistries.TASK_DISPLAY_MEDIUM.get(), TaskRegistries.TASK_DISPLAY_LARGE.get());
            for (int direction = 0; direction < 4; direction++) {
                var facing = Direction.from2DDataValue(direction);
                for (int size = 0; size < 3; size++) {
                    var pos = ORIGIN.offset(size * 12, 0, direction * 12);
                    level.setBlockAndUpdate(pos.relative(facing.getOpposite()), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(pos, blocks.get(size).defaultBlockState().setValue(TaskDisplayBlock.FACING, facing));
                    var display = (TaskDisplayBlockEntity) level.getBlockEntity(pos);
                    display.setOwner(player.getUUID()); display.setSelectedBoard(board);
                    display.setNetworkId(TaskSavedData.get(c.getSingleplayerServer()).board(board).orElseThrow().networkId().orElse(null));
                }
            }
        }), () -> serverDone);
        add("restore GUI scale", () -> { GLFW.glfwSetWindowSize(c.getWindow().getWindow(), 1280, 720); c.options.guiScale().set(2); c.resizeDisplay(); c.setScreen(null); c.options.hideGui = true; }, () -> true);
        for (int direction = 0; direction < 4; direction++) for (int size = 0; size < 3; size++) {
            int d = direction, s = size;
            add("face display " + d + " size " + s, () -> server(c, () -> {
                var player = c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID());
                var pos = ORIGIN.offset(s * 12, 0, d * 12);
                var facing = Direction.from2DDataValue(d);
                player.getAbilities().flying = true; player.onUpdateAbilities();
                player.teleportTo(c.getSingleplayerServer().overworld(), pos.getX() + .5 + facing.getStepX() * 4,
                        pos.getY() - 1, pos.getZ() + .5 + facing.getStepZ() * 4, java.util.Set.of(), 0, 0);
                player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
            }), () -> serverDone && DisplayClientState.view(ORIGIN.offset(s * 12, 0, d * 12)).isPresent());
            add("capture display " + d + " size " + s, () -> capture(c, "display-" + d + "-" + s), () -> true);
        }
        add("actual Storage source chunk unloaded", () -> {}, () -> c.getSingleplayerServer().submit(() -> {
            var server = c.getSingleplayerServer();
            if (server.overworld().getChunkSource().getChunkNow(0, 0) != null) return false;
            var player = server.getPlayerList().getPlayer(c.player.getUUID());
            var stock = fr.lkdm.homelink.tasks.stock.AuthorizedStockQuery.observe(player, TaskSavedData.get(server).board(board).orElseThrow(), java.util.List.of(new ItemStack(Items.OAK_PLANKS)));
            require(stock.entries().isEmpty() && stock.availability() == fr.lkdm.homecore.api.stock.StockAvailability.UNAVAILABLE, "unloaded Storage leaked stock");
            require(server.overworld().getChunkSource().getChunkNow(0, 0) == null, "stock query forced a chunk load");
            LogUtils.getLogger().info("TASKS_STORAGE_CHUNK_UNLOADED_NO_FORCE_LOAD_OK"); return true;
        }).join());
        add("leave display chunks", () -> server(c, () -> {
            var player = c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID());
            player.teleportTo(c.getSingleplayerServer().overworld(), 8.5, -60, 13.5, java.util.Set.of(), 180, 0);
        }), () -> serverDone);
        add("wait for actual server chunk unload", () -> {}, () -> {
            var server = c.getSingleplayerServer();
            var future = server.submit(() -> server.overworld().getChunkSource().getChunkNow(ORIGIN.getX() >> 4, ORIGIN.getZ() >> 4) == null);
            return future.join();
        });
        add("reopen actual Storage screen after reload", () -> server(c, () -> {
            var display = (TaskDisplayBlockEntity)c.getSingleplayerServer().overworld().getBlockEntity(new BlockPos(8, -59, 11));
            display.openFor(c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID()));
        }), () -> serverDone && ClientTaskState.board().isPresent());
        add("actual Storage availability recovers after reload", () -> c.setScreen(new RecipeScreen(board, craft)),
                () -> planIs(fr.lkdm.homelink.tasks.stock.AvailabilityState.IN_STORAGE));
        add("capture reloaded stock", () -> { capture(c, "stock-reloaded"); c.setScreen(null); LogUtils.getLogger().info("TASKS_STORAGE_CHUNK_RELOADED_OK"); }, () -> true);
        add("return to unloaded display", () -> server(c, () -> {
            var player = c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID());
            player.teleportTo(c.getSingleplayerServer().overworld(), ORIGIN.getX() + .5, -59, ORIGIN.getZ() + 4.5, java.util.Set.of(), 180, 0);
        }), () -> serverDone);
        add("verify saved display and board after reload", () -> server(c, () -> {
            var display = (TaskDisplayBlockEntity)c.getSingleplayerServer().overworld().getBlockEntity(ORIGIN);
            require(display != null && display.selectedBoard().orElseThrow().equals(board), "lost selected board on chunk reload");
            require(TaskSavedData.get(c.getSingleplayerServer()).board(board).orElseThrow().card(craft).orElseThrow()
                    .objective().orElseThrow().completedQuantity() == 1, "chunk cycle changed actual craft progress");
            LogUtils.getLogger().info("TASKS_REAL_CHUNK_UNLOAD_RELOAD_OK");
        }), () -> serverDone);
        add("capture reloaded display", () -> capture(c, "display-reloaded"), () -> true);
        for (int size = 0; size < 3; size++) {
            int index = size;
            add("hold display item " + size, () -> { c.options.hideGui = false; server(c, () -> {
                var player = c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID());
                var items = java.util.List.of(TaskRegistries.TASK_DISPLAY_ITEM.get(), TaskRegistries.TASK_DISPLAY_MEDIUM_ITEM.get(), TaskRegistries.TASK_DISPLAY_LARGE_ITEM.get());
                player.getInventory().setItem(0, new ItemStack(items.get(index))); player.getInventory().selected = 0;
                player.inventoryMenu.broadcastChanges();
            }); }, () -> serverDone);
            add("capture held item " + size, () -> capture(c, "item-hand-" + index), () -> true);
        }
        add("small viewport maximum HUD scale", () -> {
            GLFW.glfwSetWindowSize(c.getWindow().getWindow(), 640, 480); c.options.guiScale().set(2); c.resizeDisplay();
            TaskClientConfig.HUD_SCALE.set(2.0D); TaskClientConfig.HUD_COMPACT.set(false); c.setScreen(null);
        }, () -> true);
        for (var corner : TaskClientConfig.Corner.values()) {
            add("HUD corner " + corner, () -> TaskClientConfig.HUD_CORNER.set(corner), () -> true);
            add("capture HUD " + corner, () -> capture(c, "hud-large-" + corner.name().toLowerCase(java.util.Locale.ROOT)), () -> true);
        }
        add("restore HUD preferences", () -> {
            TaskClientConfig.HUD_SCALE.set(1.0D); TaskClientConfig.HUD_CORNER.set(TaskClientConfig.Corner.TOP_RIGHT);
            GLFW.glfwSetWindowSize(c.getWindow().getWindow(), 1280, 720); c.resizeDisplay();
        }, () -> true);
        add("reopen personal board after leaving physical screen", () -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new TaskPackets.BoardRequest(TaskPackets.BoardCommand.OPEN, java.util.Optional.of(board), "", java.util.Optional.empty(),
                        BoardRole.OWNER, false, java.util.Optional.empty(), 0)), () -> ClientTaskState.board().isPresent());
        add("open local transport for second real client", () -> {
            c.options.hideGui = false;
            var server = c.getSingleplayerServer();
            int port = net.minecraft.util.HttpUtil.getAvailablePort();
            require(server.publishServer(GameType.CREATIVE, false, port), "cannot publish validation server");
            server.setUsesAuthentication(false);
            write("host", port + "\n" + board + "\n" + manual + "\n" + craft);
        }, () -> exists("guest"));
        add("invite second client via real request", () -> {
            guest = UUID.fromString(read("guest"));
            server(c, () -> c.getSingleplayerServer().getPlayerList().getPlayer(guest).getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 64)));
            TaskClientNetwork.board(TaskPackets.BoardCommand.SET_MEMBER, board, "", guest, BoardRole.MEMBER, false,
                    ClientTaskState.board().orElseThrow().revision());
        }, () -> c.getSingleplayerServer().submit(() -> TaskSavedData.get(c.getSingleplayerServer()).board(board).orElseThrow()
                .roleOf(guest).orElse(null) == BoardRole.MEMBER).join());
        add("guest claims a task without editor rights", () -> write("invited", "true"), () -> exists("guest-claimed"));
        add("promote guest for edit conflict checks", () -> TaskClientNetwork.board(TaskPackets.BoardCommand.SET_MEMBER, board, "", guest, BoardRole.EDITOR, false,
                ClientTaskState.board().orElseThrow().revision()), () -> ClientTaskState.board().orElseThrow().members().get(guest) == BoardRole.EDITOR);
        add("guest opens shared board", () -> write("invited", "true"), () -> exists("guest-ready"));
        add("host personal unknown stock", () -> c.setScreen(new RecipeScreen(board, craft)), () -> planIs(fr.lkdm.homelink.tasks.stock.AvailabilityState.UNVERIFIED));
        add("concurrent edit delivered to guest", () -> TaskClientNetwork.card(TaskPackets.CardCommand.RENAME, board, manual,
                "Modification reçue par les deux clients", false), () -> ClientTaskState.card(manual).orElseThrow().title().startsWith("Modification"));
        add("guest rejects stale edit", () -> write("edited", "true"), () -> exists("guest-conflict-ok"));
        add("verify private HUDs on server", () -> server(c, () -> {
            var data = TaskSavedData.get(c.getSingleplayerServer());
            require(data.pins(c.player.getUUID()).equals(java.util.List.of(craft)), "host pins polluted");
            require(data.pins(guest).equals(java.util.List.of(manual)), "guest pins incorrect");
            require(data.trackedCard(guest).orElseThrow().equals(craft), "guest tracking not independent of pin");
            require(data.board(board).orElseThrow().card(manual).orElseThrow().nodeX() == 35, "stale node update accepted");
            require(c.getSingleplayerServer().getPlayerList().getPlayerCount() == 2, "expected two connected real players");
            LogUtils.getLogger().info("TASKS_TWO_CLIENTS_CONFLICT_PRIVATE_HUD_OK");
        }), () -> serverDone);
        add("revoke second client via owner request", () -> TaskClientNetwork.board(TaskPackets.BoardCommand.REMOVE_MEMBER,
                board, "", guest, BoardRole.VIEWER, false, ClientTaskState.board().orElseThrow().revision()), () -> exists("guest-revoked"));
        add("real two-client revocation verified", () -> LogUtils.getLogger().info("TASKS_TWO_CLIENTS_REVOCATION_OK"), () -> true);
    }

    private static void enqueueGuest(Minecraft c) {
        add("guest connected", () -> write("guest", c.player.getUUID().toString()), () -> exists("invited"));
        add("guest open authorized board", () -> TaskClientNetwork.board(TaskPackets.BoardCommand.OPEN, board, "", null,
                BoardRole.VIEWER, false, 0), () -> ClientTaskState.board().isPresent());
        add("member claims from task detail", () -> {
            require(ClientTaskState.board().orElseThrow().viewerRole() == BoardRole.MEMBER, "expected ordinary member");
            c.setScreen(new CardDetailScreen(board, manual)); press(c, "claim");
        }, () -> ClientTaskState.card(manual).orElseThrow().assignees().contains(c.player.getUUID()));
        add("member claim confirmed", () -> { write("guest-claimed", "true"); LogUtils.getLogger().info("TASKS_MEMBER_CLAIM_UI_OK"); },
                () -> ClientTaskState.board().orElseThrow().viewerRole() == BoardRole.EDITOR);
        add("guest personal pin", () -> TaskClientNetwork.personal(TaskPackets.PersonalCommand.PIN, board, manual),
                () -> ClientTaskState.pinned().size() == 1 && ClientTaskState.pinned().getFirst().card().equals(manual));
        add("guest personal tracking", () -> TaskClientNetwork.personal(TaskPackets.PersonalCommand.TRACK, board, craft),
                () -> ClientTaskState.tracking(craft));
        add("guest personal green stock", () -> c.setScreen(new RecipeScreen(board, craft)), () -> planIs(fr.lkdm.homelink.tasks.stock.AvailabilityState.READY));
        add("capture guest personal availability", () -> { capture(c, "personal-green"); BoardScreen.openOrRefresh(); }, () -> true);
        add("guest remembers revision", () -> { staleRevision = ClientTaskState.card(manual).orElseThrow().revision(); write("guest-ready", "true"); },
                () -> exists("edited") && ClientTaskState.card(manual).orElseThrow().title().startsWith("Modification"));
        add("guest stale revision request", () -> TaskClientNetwork.moveNode(board, manual, 900, 900, staleRevision),
                () -> ClientTaskState.card(manual).orElseThrow().nodeX() == 35);
        add("capture second client", () -> { capture(c, "shared-board"); write("guest-conflict-ok", "true"); }, () -> ClientTaskState.board().isEmpty());
        add("verify guest revocation", () -> {
            require(ClientTaskState.pinned().isEmpty(), "revoked pins remain visible");
            require(!ClientTaskState.tracking(craft), "revoked tracked card remains");
            capture(c, "revoked-board"); write("guest-revoked", "true");
        }, () -> true);
    }
    private static boolean planIs(fr.lkdm.homelink.tasks.stock.AvailabilityState state) { return ClientTaskState.plan(craft).filter(plan -> plan.state() == state).isPresent(); }
    private static void storage(Minecraft c, String method, Class<?>[] types, Object... args) {
        try { Class.forName("fr.lkdm.homelink.tasks.verification.StorageClientFixture").getMethod(method, types).invoke(null, args); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Run this integration profile with -PwithStorage", e); }
    }
    private static java.nio.file.Path exchange(String name) { return java.nio.file.Path.of(System.getProperty("tasks.exchange")).resolve(name + ".txt"); }
    private static boolean exists(String name) { return Files.isRegularFile(exchange(name)); }
    private static String read(String name) { try { return Files.readString(exchange(name)); } catch (java.io.IOException e) { throw new IllegalStateException(e); } }
    private static void write(String name, String value) { try { Files.createDirectories(exchange(name).getParent()); Files.writeString(exchange(name), value); } catch (java.io.IOException e) { throw new IllegalStateException(e); } }

    private static void enqueueGuiReview(Minecraft c) {
        add("create GUI review project", () -> TaskClientNetwork.createBoard("Atelier · Projet en cours"),
                () -> ClientTaskState.board().isPresent());
        add("create completed deletion fixture", () -> {
            board = ClientTaskState.board().orElseThrow().id();
            TaskClientNetwork.createManual(board, "Ranger l'atelier");
        }, () -> ClientTaskState.board().orElseThrow().cards().size() == 1);
        add("move deletion fixture to done", () -> {
            manual = ClientTaskState.board().orElseThrow().cards().getFirst().id();
            TaskClientNetwork.move(board, manual, TaskStatus.DONE, 0);
        }, () -> ClientTaskState.card(manual).orElseThrow().status() == TaskStatus.DONE);
        add("capture completed task trash", () -> { BoardScreen.openOrRefresh(); }, () -> c.screen instanceof BoardScreen);
        add("click completed task trash", () -> {
            capture(c, "review-completed-trash");
            var screen = (TaskScreen)c.screen;
            int column = (screen.width - 32) / 3;
            double x = screen.guiLeft() + 3 * column + 9;
            double y = screen.guiTop() + 117;
            screen.mouseClicked(x, y, 0);
            screen.mouseReleased(x, y, 0);
            require(c.screen == screen, "Trash click opened detail or started a drag");
        }, () -> ClientTaskState.card(manual).isEmpty());
        add("create compact deletion fixture", () -> {
            GLFW.glfwSetWindowSize(c.getWindow().getWindow(), 640, 480); c.resizeDisplay();
            TaskClientNetwork.createManual(board, "Ranger l'atelier");
        }, () -> ClientTaskState.board().orElseThrow().cards().size() == 1);
        add("complete compact deletion fixture", () -> {
            manual = ClientTaskState.board().orElseThrow().cards().getFirst().id();
            TaskClientNetwork.move(board, manual, TaskStatus.DONE, 0);
        }, () -> ClientTaskState.card(manual).orElseThrow().status() == TaskStatus.DONE);
        add("select compact done tab", () -> pressLabel(c, net.minecraft.network.chat.Component.translatable("column.homelink_tasks.done").getString() + " (1)"), () -> true);
        add("delete from compact done column", () -> {
            capture(c, "review-completed-trash-compact");
            var screen = (TaskScreen)c.screen;
            double x = screen.guiLeft() + screen.width - 23, y = screen.guiTop() + 131;
            screen.mouseClicked(x, y, 0); screen.mouseReleased(x, y, 0);
            require(c.screen == screen, "Compact trash opened card details");
        }, () -> ClientTaskState.card(manual).isEmpty());
        add("restore full board dimensions", () -> {
            GLFW.glfwSetWindowSize(c.getWindow().getWindow(), 1280, 720); c.resizeDisplay();
        }, () -> true);
        add("create crafting fixture", () -> {
            board = ClientTaskState.board().orElseThrow().id();
            TaskClientNetwork.createCraft(board, "Fabriquer 8 établis", new ItemStack(Items.CRAFTING_TABLE), 8, RECIPE, true);
        }, () -> ClientTaskState.board().orElseThrow().cards().size() == 1);
        add("ingredient quick action", () -> {
            craft = ClientTaskState.board().orElseThrow().cards().getFirst().id();
            c.setScreen(new CardDetailScreen(board, craft));
        }, () -> ClientTaskState.plan(craft).isPresent());
        add("capture recipe and choose component", () -> {
            capture(c, "review-ingredients"); pressLabel(c, "+");
        }, () -> c.screen instanceof RecipeExpansionScreen);
        add("confirm component recipe", () -> press(c, "create_component"),
                () -> ClientTaskState.board().orElseThrow().cards().size() == 2);
        add("component is sized and pinned", () -> {
            var child = ClientTaskState.board().orElseThrow().cards().stream().filter(card -> card.parent().isPresent()).findFirst().orElseThrow();
            require(child.objective().orElseThrow().targetQuantity() == 32, "wrong component quantity");
            require(child.objective().orElseThrow().completedQuantity() == 0, "component fabricated progress");
            require(ClientTaskState.pinned().stream().anyMatch(pin -> pin.card().equals(child.id())), "component was not pinned");
            require(c.screen.children().stream().noneMatch(widget -> widget instanceof net.minecraft.client.gui.components.Button b && b.active && b.getMessage().getString().equals("+")), "duplicate component action enabled");
            c.setScreen(null);
            TaskClient.VIEW_PROJECT.setDown(true);
            // Exercise the registered key's consumption path even while it is unbound by default.
            try {
                var click = net.minecraft.client.KeyMapping.class.getDeclaredField("clickCount");
                click.setAccessible(true); click.setInt(TaskClient.VIEW_PROJECT, 1);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }, () -> c.screen instanceof ProjectViewerScreen && ClientTaskState.board().isPresent());
        add("view project cannot edit", () -> {
            TaskClient.VIEW_PROJECT.setDown(false);
            require(((TaskScreen)c.screen).readOnly(), "consultation did not stay read only after snapshot");
            capture(c, "review-project"); pressLabel(c, "Fabriquer 8 établis");
        }, () -> c.screen instanceof RecipeScreen && ((TaskScreen)c.screen).readOnly() && ClientTaskState.plan(craft).isPresent());
        add("view recipe exposes only navigation", () -> {
            require(c.screen.children().size() == 1, "read-only recipe exposes mutation controls");
            capture(c, "review-readonly-recipe");
            c.screen.onClose(); require(c.screen instanceof ProjectViewerScreen, "back escaped consultation");
            c.screen.onClose(); require(c.screen == null, "close did not return to game");
        }, () -> true);
        for (int pixels : new int[]{1280, 640}) {
            add("visibility preferences " + pixels, () -> {
                GLFW.glfwSetWindowSize(c.getWindow().getWindow(), pixels, pixels == 1280 ? 720 : 480);
                c.options.guiScale().set(2); c.resizeDisplay();
                c.setScreen(new BoardSettingsScreen(board)); press(c, "visibility");
            }, () -> true);
            add("capture preferences " + pixels, () -> {
                capture(c, "review-settings-" + pixels);
                var toggle = c.screen.children().stream().filter(widget -> widget instanceof net.minecraft.client.gui.components.Button b
                        && b.getMessage().getString().startsWith(net.minecraft.network.chat.Component.translatable("screen.homelink_tasks.hud_visible").getString()))
                        .map(widget -> (net.minecraft.client.gui.components.Button)widget).findFirst().orElseThrow();
                boolean previous = TaskClientConfig.HUD_VISIBLE.get(); toggle.onPress();
                require(TaskClientConfig.HUD_VISIBLE.get() != previous, "HUD toggle failed");
                TaskClientConfig.HUD_VISIBLE.set(previous); TaskClientConfig.SPEC.save();
                c.setScreen(new RecipeScreen(board, craft, true));
            }, () -> true);
            add("capture small consultation " + pixels, () -> capture(c, "review-readonly-" + pixels), () -> true);
            for (int index = 0; index < 23; index++) {
                int view = index;
                add("check centered view " + pixels + "/" + index, () -> open(c, view), () -> true);
                add("capture centered view " + pixels + "/" + index, () -> capture(c, "review-centered-" + pixels + "-" + view), () -> true);
            }
        }
        add("display geometry fixture", () -> server(c, () -> {
            var level = c.getSingleplayerServer().overworld();
            level.setBlockAndUpdate(ORIGIN.relative(Direction.NORTH), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(ORIGIN, TaskRegistries.TASK_DISPLAY.get().defaultBlockState().setValue(TaskDisplayBlock.FACING, Direction.SOUTH));
            var display = (TaskDisplayBlockEntity)level.getBlockEntity(ORIGIN);
            display.setOwner(c.player.getUUID()); display.setSelectedBoard(board);
            var player = c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID());
            player.teleportTo(level, ORIGIN.getX() + .5, ORIGIN.getY() - 1, ORIGIN.getZ() + 2.5, java.util.Set.of(), 180, 0);
        }), () -> serverDone);
        add("view physical screen", () -> {
            c.setScreen(null); c.options.hideGui = true;
            GLFW.glfwSetWindowSize(c.getWindow().getWindow(), 1280, 720); c.resizeDisplay();
        }, () -> DisplayClientState.view(ORIGIN).isPresent());
        add("capture physical screen", () -> capture(c, "review-wall-screen"), () -> true);
        for (var size : TaskDisplayBlock.Size.values()) {
            add("assemble physical screen " + size, () -> server(c, () -> {
                var level = c.getSingleplayerServer().overworld();
                level.removeBlock(ORIGIN, false);
                var state = TaskRegistries.TASK_DISPLAY.get().defaultBlockState()
                        .setValue(TaskDisplayBlock.FACING, Direction.SOUTH).setValue(TaskDisplayBlock.SIZE, size);
                level.setBlock(ORIGIN, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
                var player = c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID());
                TaskRegistries.TASK_DISPLAY.get().setPlacedBy(level, ORIGIN, state, player, new ItemStack(TaskRegistries.TASK_DISPLAY_ITEM.get()));
                ((TaskDisplayBlockEntity)level.getBlockEntity(ORIGIN)).setSelectedBoard(board);
            }), () -> serverDone && DisplayClientState.view(ORIGIN).isPresent());
            add("capture size " + size, () -> capture(c, "review-wall-" + size), () -> true);
        }
    }

    private static void open(Minecraft c, int index) {
        switch (index) {
            case 0 -> BoardScreen.openOrRefresh();
            case 1 -> c.setScreen(new BoardListScreen());
            case 2 -> c.setScreen(new BoardSettingsScreen(board));
            case 3 -> c.setScreen(new MindMapScreen(board));
            case 4 -> c.setScreen(new CardDetailScreen(board, craft));
            case 5 -> c.setScreen(new CardActionsScreen(board, craft));
            case 6 -> c.setScreen(new ObjectiveSettingsScreen(board, craft));
            case 7 -> c.setScreen(new CardEditorScreen(board, null));
            case 8 -> c.setScreen(new CardEditorScreen(board, craft));
            case 9 -> c.setScreen(new RecipeScreen(board, craft));
            case 10 -> c.setScreen(new RecipeExpansionScreen(board, craft, 0, ClientRecipeLookup.describe(RECIPE).orElseThrow()));
            case 11 -> c.setScreen(new ProjectMaterialsScreen(board));
            case 12 -> c.setScreen(new ItemPickerScreen(new CardEditorScreen(board, null), item -> {}));
            case 13 -> { c.setScreen(new CardEditorScreen(board, null)); press(c, "type_craft"); }
            case 14 -> c.setScreen(new RecipePickerScreen(new CardEditorScreen(board, null), ClientRecipeLookup.alternatives(new ItemStack(Items.CRAFTING_TABLE)), 0, selected -> {}));
            case 15 -> { c.setScreen(new CardActionsScreen(board, craft)); press(c, "team"); }
            case 16 -> { c.setScreen(new CardActionsScreen(board, craft)); press(c, "advanced"); }
            case 17 -> { c.setScreen(new CardActionsScreen(board, craft)); press(c, "advanced"); press(c, "relationships"); }
            case 18 -> { c.setScreen(new BoardSettingsScreen(board)); press(c, "members"); }
            case 19 -> { c.setScreen(new BoardSettingsScreen(board)); press(c, "advanced"); }
            case 20 -> { BoardScreen.openOrRefresh(); pressLabel(c, "..."); }
            case 21 -> { c.setScreen(new CardDetailScreen(board, craft)); pressLabel(c, "..."); }
            case 22 -> { c.setScreen(new ItemPickerScreen(new CardEditorScreen(board, null), item -> {})); field(c, 0).setValue("zzzz-no-such-item"); }
            default -> throw new IllegalArgumentException();
        }
    }

    private static net.minecraft.client.gui.components.EditBox field(Minecraft c, int index) {
        return c.screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.EditBox).map(child -> (net.minecraft.client.gui.components.EditBox)child).toList().get(index);
    }
    private static net.minecraft.client.gui.components.Button button(Minecraft c, String key) {
        String label = net.minecraft.network.chat.Component.translatable("screen.homelink_tasks." + key).getString();
        return c.screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(label)).map(child -> (net.minecraft.client.gui.components.Button)child).findFirst().orElseThrow(() -> new IllegalStateException("Missing button " + key));
    }
    private static void press(Minecraft c, String key) { pressLabel(c, net.minecraft.network.chat.Component.translatable("screen.homelink_tasks." + key).getString()); }
    private static void pressLabel(Minecraft c, String label) {
        var widget = c.screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button b && b.active && b.getMessage().getString().equals(label)).map(child -> (net.minecraft.client.gui.components.Button)child).findFirst().orElseThrow(() -> new IllegalStateException("Missing active button " + label));
        var screen = c.screen;
        int offsetX = screen instanceof TaskScreen tasks ? tasks.guiLeft() : 0;
        int offsetY = screen instanceof TaskScreen tasks ? tasks.guiTop() : 0;
        screen.mouseClicked(offsetX + widget.getX() + widget.getWidth() / 2.0, offsetY + widget.getY() + widget.getHeight() / 2.0, 0);
        if (screen == c.screen) screen.mouseReleased(offsetX + widget.getX() + widget.getWidth() / 2.0, offsetY + widget.getY() + widget.getHeight() / 2.0, 0);
    }

    private static void capture(Minecraft c, String name) {
        try {
            var path = c.gameDirectory.toPath().resolve("screenshots"); Files.createDirectories(path);
            try (var image = Screenshot.takeScreenshot(c.getMainRenderTarget())) { image.writeToFile(path.resolve(name + ".png")); }
            if (c.screen instanceof TaskScreen) for (var child : c.screen.children()) if (child instanceof AbstractWidget w && w.visible) {
                require(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= c.screen.width && w.getBottom() <= c.screen.height,
                        name + " widget outside screen: " + w.getMessage().getString() + " " + w.getX() + "," + w.getY());
                if (w instanceof net.minecraft.client.gui.components.Button && c.font.width(w.getMessage()) > w.getWidth() - 8)
                    LogUtils.getLogger().info("TASKS_LABEL_CLIPPED {} {}", name, w.getMessage().getString());
                for (var other : c.screen.children()) if (other instanceof AbstractWidget o && o != w && o.visible)
                    require(w.getRight() <= o.getX() || o.getRight() <= w.getX() || w.getBottom() <= o.getY() || o.getBottom() <= w.getY(), "overlapping controls: " + name + " " + w.getMessage().getString() + " / " + o.getMessage().getString());
            }
            LogUtils.getLogger().info("TASKS_CAPTURE {} gui={}x{}", name, c.getWindow().getGuiScaledWidth(), c.getWindow().getGuiScaledHeight());
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static void server(Minecraft c, Runnable action) {
        serverDone = false;
        c.getSingleplayerServer().execute(() -> { try { action.run(); serverDone = true; } catch (Throwable e) { failure = e; } });
    }
    private static void add(String name, Runnable action, BooleanSupplier ready) { STEPS.add(new Step(name, action, ready, 12)); }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private static void shutdown(Minecraft c) {
        finished = true;
        if (c.level != null) c.level.disconnect();
        c.disconnect(); c.stop();
    }
}
