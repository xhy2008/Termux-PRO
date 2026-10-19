package com.termux.shared.termux.shell.command.environment;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.shell.command.environment.ShellCommandShellEnvironment;
import com.termux.shared.shell.command.environment.UnixShellEnvironment;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellUtils;

import java.io.File;
import java.nio.charset.Charset;
import java.util.HashMap;

/**
 * Environment for the Debian guest running under proroot.
 *
 * The command is always started by the {@code libproroot.so} launcher, so the environment handed
 * to it is a plain Debian guest environment and not an Android environment.
 */
public class TermuxShellEnvironment extends UnixShellEnvironment {

    private static final String LOG_TAG = "TermuxShellEnvironment";

    protected ShellCommandShellEnvironment shellCommandShellEnvironment;

    /** Environment variable for the proroot temporary directory. */
    public static final String ENV_PROROOT_TMP_DIR = "PROROOT_TMP_DIR";

    /** Directory inside the guest that commands are started in. The host app home directory is
     * bind mounted here.
     *
     * The guest path is the host path instead of `/root` on purpose. Proroot translates paths from
     * the libc calls of injected libraries only, so a program that issues `mkdirat` itself, like
     * the Zig file layer inside the Bun runtime of `opencode`, reaches the Android root, which is
     * read-only and fails with `EROFS ... mkdir "/root"`. With the home directory bound to the same
     * absolute path on both sides, a translated path and a raw one name the same directory. */
    public static final String GUEST_HOME_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH;

    /** The launcher that enters the guest rootfs. */
    public static final String GUEST_LAUNCHER = "libproroot.so";

    /** Guest shell used to interpret guest scripts without a usable shebang. */
    public static final String GUEST_SHELL = "/bin/bash";

    private static String mGuestLauncherPath;

    /** The host side path of the proroot launcher executable. */
    @NonNull
    public static String getGuestLauncherPath() {
        String path = mGuestLauncherPath;
        return path != null ? path : GUEST_LAUNCHER;
    }

    /** Guest $PATH. */
    public static final String GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    /** Guest $TMPDIR. Inside the home directory for the same reason as {@link #GUEST_HOME_DIR_PATH},
     * since `/tmp` is only translated and does not exist on the Android host. */
    public static final String GUEST_TMPDIR = GUEST_HOME_DIR_PATH + "/tmp";

    /**
     * Shells that accept the `-l` option.
     *
     * A Debian userland ships {@code /usr/bin/login}, which the generic list matches before a shell
     * and which is a util-linux program that rejects `-l`, so it has to be left out.
     */
    public static final String[] GUEST_LOGIN_SHELL_BINARIES = new String[]{"bash", "zsh", "fish", "sh"};

    @NonNull
    @Override
    public String[] getLoginShellBinaryNames() {
        return GUEST_LOGIN_SHELL_BINARIES;
    }

    public TermuxShellEnvironment() {
        shellCommandShellEnvironment = new TermuxShellCommandShellEnvironment();
    }

    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void init(@NonNull Context currentPackageContext) {
        TermuxAppShellEnvironment.setTermuxAppEnvironment(currentPackageContext);
        mGuestLauncherPath = new File(currentPackageContext.getApplicationInfo().nativeLibraryDir, GUEST_LAUNCHER).getAbsolutePath();
    }

    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void writeEnvironmentToFile(@NonNull Context currentPackageContext) {
        HashMap<String, String> environmentMap = new TermuxShellEnvironment().getEnvironment(currentPackageContext, false);
        String environmentString = ShellEnvironmentUtils.convertEnvironmentToDotEnvFile(environmentMap);

        // Write environment string to temp file and then move to final location since otherwise
        // writing may happen while file is being sourced/read
        Error error = FileUtils.writeTextToFile("termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH,
            Charset.defaultCharset(), environmentString, false);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
            return;
        }

        error = FileUtils.moveRegularFile("termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH, TermuxConstants.TERMUX_ENV_FILE_PATH, true);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
        }
    }

    /** Get environment for the Debian guest. */
    @NonNull
    @Override
    public HashMap<String, String> getEnvironment(@NonNull Context currentPackageContext, boolean isFailSafe) {
        HashMap<String, String> environment = new HashMap<>();

        if (isFailSafe) {
            // The failsafe session runs the Android shell outside the guest, so it keeps the
            // Android environment.
            environment.put(ENV_HOME, TermuxConstants.TERMUX_HOME_DIR_PATH);
            environment.put(ENV_PATH, System.getenv(ENV_PATH));
            environment.put(ENV_TMPDIR, "/data/local/tmp");
            environment.put(ENV_TERM, "xterm-256color");
            ShellEnvironmentUtils.putToEnvIfInSystemEnv(environment, "ANDROID_DATA");
            ShellEnvironmentUtils.putToEnvIfInSystemEnv(environment, "ANDROID_ROOT");
            ShellEnvironmentUtils.createHomeDir(environment);
            return environment;
        }

        environment.put(ENV_HOME, GUEST_HOME_DIR_PATH);
        // Both paths are host side, so they can be created here and are then simply visible to the
        // guest through the identity bind of the home directory.
        ShellEnvironmentUtils.createHomeDir(environment);
        FileUtils.createDirectoryFile("shell tmpdir", GUEST_TMPDIR);
        environment.put(ENV_PATH, GUEST_PATH);
        environment.put(ENV_TMPDIR, GUEST_TMPDIR);
        environment.put(ENV_LANG, "C.UTF-8");
        environment.put(ENV_COLORTERM, "truecolor");
        environment.put(ENV_TERM, "xterm-256color");

        // Proroot needs a writable directory for its generated config, fifos and symlinks. It is a
        // host side path, so it is passed via the environment instead of the command line.
        String prorootTmpDir = getProrootTmpDir(currentPackageContext);
        if (prorootTmpDir != null)
            environment.put(ENV_PROROOT_TMP_DIR, prorootTmpDir);

        // Android runtime paths, some guest binaries like `am` style wrappers expect them
        ShellEnvironmentUtils.putToEnvIfInSystemEnv(environment, "ANDROID_DATA");
        ShellEnvironmentUtils.putToEnvIfInSystemEnv(environment, "ANDROID_ROOT");
        ShellEnvironmentUtils.putToEnvIfInSystemEnv(environment, "ANDROID_STORAGE");
        ShellEnvironmentUtils.putToEnvIfInSystemEnv(environment, "EXTERNAL_STORAGE");

        return environment;
    }

    /** The host side directory proroot uses for its temporary files, created if missing. */
    public static String getProrootTmpDir(@NonNull Context currentPackageContext) {
        File dir = new File(currentPackageContext.getCacheDir(), "proroot-tmp");
        if (!dir.exists() && !dir.mkdirs()) {
            Logger.logError(LOG_TAG, "Failed to create proroot tmp directory \"" + dir.getAbsolutePath() + "\"");
            return null;
        }
        return dir.getAbsolutePath();
    }

    /** The host side path of the directory the guest commands are started in. */
    @NonNull
    @Override
    public String getDefaultWorkingDirectoryPath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    /** The guest binaries directory, used to look for login shell binaries inside the rootfs. */
    @NonNull
    @Override
    public String getDefaultBinPath() {
        return TermuxConstants.TERMUX_ROOTFS_DIR_PATH + "/usr/bin";
    }

    @NonNull
    @Override
    public HashMap<String, String> setupShellCommandEnvironment(@NonNull Context currentPackageContext,
                                                                @NonNull ExecutionCommand executionCommand) {
        HashMap<String, String> environment = getEnvironment(currentPackageContext, executionCommand.isFailsafe);

        String workingDirectory = executionCommand.workingDirectory;
        environment.put(ENV_PWD,
            workingDirectory != null && !workingDirectory.isEmpty() ? new File(workingDirectory).getAbsolutePath() :
                getDefaultWorkingDirectoryPath());

        // $HOME is a host side path that getEnvironment() already created, so nothing to do here.
        // It must not be looked up in the rootfs, since the guest home is the bind mounted app home
        // directory and not the /root directory of the Debian image.

        if (executionCommand.setShellCommandShellEnvironment && shellCommandShellEnvironment != null)
            environment.putAll(shellCommandShellEnvironment.getEnvironment(currentPackageContext, executionCommand));

        return environment;
    }

    @NonNull
    @Override
    public String[] setupShellCommandArguments(@NonNull String executable, String[] arguments,
                                               @Nullable String workingDirectory) {
        // Commands are executed inside the Debian guest rootfs through the proroot launcher. Host
        // paths that are not visible to the guest, like the /system/bin/sh failsafe shell, are
        // executed on the host directly.
        if (TermuxShellUtils.isPathVisibleToGuest(executable))
            return TermuxShellUtils.setupGuestCommandArguments(executable, arguments, workingDirectory,
                isLoginShell(executable));
        return TermuxShellUtils.setupShellCommandArguments(executable, arguments);
    }

    /** Check if the command is a guest login shell, like a new terminal session. */
    private static boolean isLoginShell(@NonNull String executable) {
        String guestExecutable = TermuxShellUtils.getGuestPath(executable);
        String binary = new File(guestExecutable).getName();
        if (!executable.startsWith("/")) return true;
        for (String shellBinary : GUEST_LOGIN_SHELL_BINARIES) {
            if (shellBinary.equals(binary)) return true;
        }
        return false;
    }

}
