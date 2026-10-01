package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.network.BoardView;
import fr.lkdm.homelink.tasks.network.MemberPackets;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Board administration, with UUID-backed local invitations and explicit destructive confirmations. */
public final class BoardSettingsScreen extends TaskScreen {
    private final UUID boardId;
    private EditBox name;
    private EditBox description;
    private int selected;
    private int section;
    private BoardRole inviteRole = BoardRole.MEMBER;

    public BoardSettingsScreen(UUID boardId) {
        super(Component.translatable("screen.homelink_tasks.manage"));
        this.boardId = boardId;
        PacketDistributor.sendToServer(new MemberPackets.Request(boardId));
    }

    private BoardView board() { return ClientTaskState.board().filter(board -> board.id().equals(boardId)).orElse(null); }
    private int left() { return Math.max(12, width / 2 - 190); }
    private int contentWidth() { return Math.min(380, width - 24); }

    @Override protected void initContent() {
        var board = board();
        if (board == null) return;
        int left = left(), wide = contentWidth();
        boolean editor = board.viewerRole().atLeast(BoardRole.EDITOR);
        boolean owner = board.viewerRole() == BoardRole.OWNER;
        int third = (wide - 12) / 4;
        String[] sections = {"general", "members", "advanced", "visibility"};
        for (int i = 0; i < 4; i++) { int tab = i; addRenderableWidget(TaskButton.tab(Component.translatable("screen.homelink_tasks." + sections[i]), ignored -> { section = tab; dataChanged(); }, section == i).bounds(left + i * (third + 4), 51, third, 20).build()); }
        if (section == 0) {
            String oldName = name == null ? board.title() : name.getValue();
            String oldDescription = description == null ? board.description() : description.getValue();
            name = new EditBox(font, left, 94, wide - 84, 20, Component.translatable("screen.homelink_tasks.title"));
            name.setMaxLength(80); name.setValue(oldName); name.setEditable(editor); addRenderableWidget(name);
            description = new EditBox(font, left, 142, wide - 84, 20, Component.translatable("screen.homelink_tasks.description"));
            description.setMaxLength(2000); description.setValue(oldDescription); description.setEditable(editor); addRenderableWidget(description);
            button("save", left + wide - 78, 94, 78, () -> send(TaskPackets.BoardCommand.RENAME, name.getValue(), null, false)).active = editor;
            button("save", left + wide - 78, 142, 78, () -> send(TaskPackets.BoardCommand.DESCRIBE, description.getValue(), null, false)).active = editor;
        } else if (section == 1) {
            button("previous", left, 90, 30, () -> { selected--; dataChanged(); }).active = ClientTaskState.members().size() > 1;
            button("next", left + wide - 30, 90, 30, () -> { selected++; dataChanged(); }).active = ClientTaskState.members().size() > 1;
            var roleButton = button("member_role", left, 126, wide / 2 - 2, () -> {
                inviteRole = switch (inviteRole) { case MEMBER -> BoardRole.EDITOR; case EDITOR -> BoardRole.VIEWER; default -> BoardRole.MEMBER; }; dataChanged();
            });
            roleButton.active = owner;
            roleButton.setMessage(Component.translatable("role.homelink_tasks." + inviteRole.name().toLowerCase(java.util.Locale.ROOT)));
            button("invite", left + wide / 2 + 2, 126, wide / 2 - 2, () -> selectedPlayer(id -> TaskClientNetwork.board(TaskPackets.BoardCommand.SET_MEMBER, boardId, "", id, inviteRole, false, board().revision()))).active = owner && !ClientTaskState.members().isEmpty();
            button("remove_member", left, 150, wide, () -> selectedPlayer(id -> confirm("remove_member", () -> send(TaskPackets.BoardCommand.REMOVE_MEMBER, "", id, false)))).active = owner && !ClientTaskState.members().isEmpty();
            button("transfer_owner", left, 176, wide, () -> selectedPlayer(id -> confirm("transfer_owner", () -> send(TaskPackets.BoardCommand.TRANSFER_OWNERSHIP, "", id, false)))).active = owner && !ClientTaskState.members().isEmpty();
        } else if (section == 3) {
            int half = (wide - 6) / 2;
            toggle("hud_visible", TaskClientConfig.HUD_VISIBLE, left, 84, half);
            toggle("display_visible", TaskClientConfig.DISPLAY_VISIBLE, left + half + 6, 84, half);
            toggle("hud_compact", TaskClientConfig.HUD_COMPACT, left, 114, half);
            toggle("hud_contrast", TaskClientConfig.HUD_HIGH_CONTRAST, left + half + 6, 114, half);
            var corner = button("hud_corner", left, 144, half, () -> {
                var values = TaskClientConfig.Corner.values();
                TaskClientConfig.HUD_CORNER.set(values[(TaskClientConfig.HUD_CORNER.get().ordinal() + 1) % values.length]);
                TaskClientConfig.SPEC.save(); dataChanged();
            });
            corner.setMessage(Component.translatable("screen.homelink_tasks.corner." +
                    TaskClientConfig.HUD_CORNER.get().name().toLowerCase(java.util.Locale.ROOT)));
            var scale = button("hud_scale", left + half + 6, 144, half, () -> {
                double value = TaskClientConfig.HUD_SCALE.get();
                TaskClientConfig.HUD_SCALE.set(value >= 2.0 ? 0.5 : Math.min(2.0, value + 0.25));
                TaskClientConfig.SPEC.save(); dataChanged();
            });
            scale.setMessage(Component.translatable("screen.homelink_tasks.hud_scale", Math.round(TaskClientConfig.HUD_SCALE.get() * 100)));
            toggle("hud_finished", TaskClientConfig.HUD_KEEP_FINISHED, left, 174, wide);
        } else {
            if (ClientTaskState.screen().isPresent()) {
                button("screen_network", left, 84, wide / 2 - 3, () -> minecraft.setScreen(new ScreenNetworkScreen(this, ClientTaskState.screen().orElseThrow())));
                button(board.attached() ? "detach_network" : "attach_network", left + wide / 2 + 3, 84, wide / 2 - 3, () -> send(TaskPackets.BoardCommand.ATTACH_NETWORK, "", null, !board().attached())).active = owner;
            }
            button("archived_tasks", left, 112, wide, () -> {
                var menu = new TaskMenuScreen(this);
                board().cards().stream().filter(fr.lkdm.homelink.tasks.network.CardView::archived).forEach(card -> menu.action(Component.literal(card.title()), () -> minecraft.setScreen(new CardDetailScreen(boardId, card.id()))));
                minecraft.setScreen(menu);
            }).active = board.cards().stream().anyMatch(fr.lkdm.homelink.tasks.network.CardView::archived);
            button(board.archived() ? "reopen_board" : "archive_board", left, 148, wide, () -> send(TaskPackets.BoardCommand.ARCHIVE, "", null, !board().archived())).active = editor;
            button("delete_board", left, 176, wide, () -> confirm("delete_board", () -> send(TaskPackets.BoardCommand.DELETE, "", null, true))).active = owner;
        }        button("back", left, height - 26, wide, BoardScreen::openOrRefresh);
    }

    private net.minecraft.client.gui.components.Button button(String key, int x, int y, int wide, Runnable run) {
        return addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks." + key),
                ignored -> run.run()).bounds(x, y, wide, 20).build());
    }

    private void toggle(String key, net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value,
                        int x, int y, int wide) {
        var control = button(key, x, y, wide, () -> {
            value.set(!value.get()); TaskClientConfig.SPEC.save(); dataChanged();
        });
        control.setMessage(Component.translatable("screen.homelink_tasks." + key).append(": ")
                .append(Component.translatable(value.get() ? "options.on" : "options.off")));
        control.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.homelink_tasks.personal_visibility")));
    }

    private void selectedPlayer(java.util.function.Consumer<UUID> run) {
        var players = ClientTaskState.members();
        if (!players.isEmpty()) run.accept(players.get(Math.floorMod(selected, players.size())).id());
    }

    private void send(TaskPackets.BoardCommand command, String text, UUID target, boolean flag) {
        if (board() == null) return;
        TaskClientNetwork.board(command, boardId, text, target, inviteRole, flag, board().revision());
    }

    private void confirm(String action, Runnable run) {
        minecraft.setScreen(new ConfirmScreen(accepted -> {
            minecraft.setScreen(this);
            if (accepted) run.run();
        }, Component.translatable("screen.homelink_tasks." + action),
                Component.translatable("screen.homelink_tasks.confirm_action")));
    }

    @Override public void onClose() { BoardScreen.openOrRefresh(); }
    @Override public void dataChanged() {
        String keptName = name == null ? null : name.getValue();
        String keptDescription = description == null ? null : description.getValue();
        clearWidgets(); initContent();
        if (keptName != null && name != null) name.setValue(keptName);
        if (keptDescription != null && description != null) description.setValue(keptDescription);
    }

    @Override public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = left();
        graphics.drawString(font, title, left, 33, TaskTheme.TEXT, false);
        if (section == 0) {
            graphics.drawString(font, Component.translatable("screen.homelink_tasks.title"), left, 81, TaskTheme.TEXT_MUTED, false);
            graphics.drawString(font, Component.translatable("screen.homelink_tasks.description"), left, 129, TaskTheme.TEXT_MUTED, false);
        }
        var players = ClientTaskState.members();
        if (section == 1 && !players.isEmpty()) {
            var player = players.get(Math.floorMod(selected, players.size()));
            String role = board() != null && board().members().containsKey(player.id())
                    ? Component.translatable("role.homelink_tasks." + board().members().get(player.id()).name().toLowerCase(java.util.Locale.ROOT)).getString()
                    : Component.translatable("screen.homelink_tasks.known_player").getString();
            graphics.drawString(font, TaskTheme.clip(font, player.name() + " / " + role, contentWidth() - 76), left + 36, 96, TaskTheme.TEXT, false);
        }        super.renderContent(graphics, mouseX, mouseY, partialTick);
    }
}
