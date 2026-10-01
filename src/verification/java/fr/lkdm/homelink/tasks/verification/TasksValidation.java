package fr.lkdm.homelink.tasks.verification;

import net.neoforged.fml.common.Mod;

/**
 * Development-only companion mod that carries the runtime checks.
 *
 * <p>It is never part of a release: the release check refuses a JAR that contains these
 * classes.</p>
 */
@Mod(TasksValidation.MOD_ID)
public final class TasksValidation {
    /** Namespace of the validation mod and of its game test templates. */
    public static final String MOD_ID = "homelink_tasks_validation";

    /** Registers nothing; the tests are discovered by the game test framework. */
    public TasksValidation() { }
}
