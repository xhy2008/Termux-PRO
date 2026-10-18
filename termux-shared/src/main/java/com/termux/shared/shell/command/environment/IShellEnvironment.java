package com.termux.shared.shell.command.environment;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.shell.command.ExecutionCommand;

import java.util.HashMap;

public interface IShellEnvironment {

    /**
     * Get the default working directory path for the environment in case the path that was passed
     * was {@code null} or empty.
     *
     * @return Should return the default working directory path.
     */
    @NonNull
    String getDefaultWorkingDirectoryPath();

    /**
     * Get the default "/bin" path, like $PREFIX/bin.
     *
     * @return Should return the "/bin" path.
     */
    @NonNull
    String getDefaultBinPath();

    /**
     * Get the names of the binaries to look for, in order, when a session was started without an
     * explicit executable. The first executable match becomes the session shell.
     *
     * An environment may override this since its userland can ship binaries that are not shells.
     *
     * @return Should return the login shell binary names.
     */
    @NonNull
    default String[] getLoginShellBinaryNames() {
        return UnixShellEnvironment.LOGIN_SHELL_BINARIES;
    }

    /**
     * Setup shell command arguments for the file to execute, like interpreter, etc.
     *
     * @param fileToExecute The file to execute.
     * @param arguments The arguments to pass to the executable.
     * @param workingDirectory The host side working directory the command is started in, if the
     *                         command needs to know it.
     * @return Should return the final process arguments.
     */
    @NonNull
    String[] setupShellCommandArguments(@NonNull String fileToExecute, @Nullable String[] arguments,
                                        @Nullable String workingDirectory);

    /**
     * Setup shell command environment to be used for commands.
     *
     * @param currentPackageContext The {@link Context} for the current package.
     * @param executionCommand The {@link ExecutionCommand} for which to set environment.
     * @return Should return the shell environment.
     */
    @NonNull
    HashMap<String, String> setupShellCommandEnvironment(@NonNull Context currentPackageContext,
                                                         @NonNull ExecutionCommand executionCommand);

}
