package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.network.CardView;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import fr.lkdm.homelink.tasks.task.TaskType;
import net.minecraft.client.Minecraft;

/** Mirrors server permissions for controls; requests are still validated by the server. */
final class ClientCardPermissions {
    private ClientCardPermissions() { }

    static boolean editor() {
        return ClientTaskState.board().map(board -> board.viewerRole().atLeast(BoardRole.EDITOR)).orElse(false);
    }

    static boolean hasExpandedIngredients(CardView parent) {
        return ClientTaskState.board().map(board -> board.cards().stream()
                .anyMatch(child -> child.derivedIngredient() >= 0 && child.parent().filter(parent.id()::equals).isPresent())).orElse(false);
    }

    static boolean canClaim(CardView card) {
        return !card.archived() && card.assignees().isEmpty() && ClientTaskState.board()
                .map(board -> board.viewerRole().atLeast(BoardRole.MEMBER)).orElse(false);
    }

    static boolean canMove(CardView card) {
        if (card.archived()) return false;
        if (editor()) return true;
        var player = Minecraft.getInstance().player;
        return player != null && ClientTaskState.board().map(board -> board.viewerRole() == BoardRole.MEMBER).orElse(false)
                && (card.assignees().contains(player.getUUID())
                || card.type() == TaskType.MANUAL && card.creator().equals(player.getUUID()));
    }

    static boolean canMoveTo(CardView card, TaskStatus target) {
        if (!canMove(card)) return false;
        if (card.type() != TaskType.CRAFT || card.status() == target) return true;
        var objective = card.objective().orElse(null);
        if (objective == null) return target != TaskStatus.DONE;
        if (objective.remaining() == 0) return target == TaskStatus.DONE;
        return target != TaskStatus.DONE && (target != TaskStatus.TODO || objective.completedQuantity() == 0);
    }
}
