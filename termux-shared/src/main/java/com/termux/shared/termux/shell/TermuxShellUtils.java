package com.termux.shared.termux.shell;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.file.filesystem.FileTypes;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.environment.UnixShellEnvironment;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TermuxShellUtils {

    private static final String LOG_TAG = "TermuxShellUtils";

    /**
     * Translate a host side path into the matching path seen by the guest. The guest rootfs is the
     * app rootfs directory, and the host home directory is bind mounted as the guest home.
     *
     * @param path The host side path.
     * @return Returns the guest side path.
     */
    @NonNull
    public static String getGuestPath(@NonNull String path) {
        if (path.equals(TermuxConstants.TERMUX_ROOTFS_DIR_PATH))
            return "/";

        String rootfsPrefix = TermuxConstants.TERMUX_ROOTFS_DIR_PATH + "/";
        if (path.startsWith(rootfsPrefix))
            return path.substring(rootfsPrefix.length() - 1);

        if (path.equals(TermuxConstants.TERMUX_HOME_DIR_PATH))
            return TermuxShellEnvironment.GUEST_HOME_DIR_PATH;

        String homePrefix = TermuxConstants.TERMUX_HOME_DIR_PATH + "/";
        if (path.startsWith(homePrefix))
            return TermuxShellEnvironment.GUEST_HOME_DIR_PATH + "/" + path.substring(homePrefix.length());

        return TermuxShellEnvironment.GUEST_HOME_DIR_PATH;
    }

    /**
     * Check if a host side path is visible inside the guest.
     *
     * @param path The host side path.
     * @return Returns {@code true} if the path exists inside the guest as well.
     */
    public static boolean isPathVisibleToGuest(@NonNull String path) {
        return path.equals(TermuxConstants.TERMUX_ROOTFS_DIR_PATH) ||
            path.startsWith(TermuxConstants.TERMUX_ROOTFS_DIR_PATH + "/") ||
            path.equals(TermuxConstants.TERMUX_HOME_DIR_PATH) ||
            path.startsWith(TermuxConstants.TERMUX_HOME_DIR_PATH + "/");
    }

    /**
     * Translate a guest side path into the matching host side path, like the working directory a
     * running session reports. Guest paths that have no existing host directory are mapped to the
     * host home directory.
     *
     * @param path The guest side path.
     * @return Returns the host side path.
     */
    @NonNull
    public static String getHostPath(@NonNull String path) {
        if (path.isEmpty() || !path.startsWith("/"))
            return TermuxConstants.TERMUX_HOME_DIR_PATH;

        // /proc/<pid>/cwd already reports host side paths when read by the app
        if (isPathVisibleToGuest(path))
            return path;

        String hostPath;
        String guestHomePrefix = TermuxShellEnvironment.GUEST_HOME_DIR_PATH;
        if (path.equals(guestHomePrefix))
            hostPath = TermuxConstants.TERMUX_HOME_DIR_PATH;
        else if (path.startsWith(guestHomePrefix + "/"))
            hostPath = TermuxConstants.TERMUX_HOME_DIR_PATH + path.substring(guestHomePrefix.length());
        else if (path.equals("/"))
            hostPath = TermuxConstants.TERMUX_ROOTFS_DIR_PATH;
        else
            hostPath = TermuxConstants.TERMUX_ROOTFS_DIR_PATH + path;

        if (!new File(hostPath).isDirectory())
            return TermuxConstants.TERMUX_HOME_DIR_PATH;

        return hostPath;
    }

    /**
     * Prefix a command with the proroot launcher so that it is executed inside the Debian guest
     * rootfs instead of the Android host.
     *
     * @param executable The host side path of the file to execute.
     * @param arguments The arguments to pass to the executable.
     * @param workingDirectory The host side working directory the command is started in.
     * @param isLoginShell If the guest executable is a login shell.
     * @return Returns the final process arguments, with the proroot launcher as the executable.
     */
    @NonNull
    public static String[] setupGuestCommandArguments(@NonNull String executable, @Nullable String[] arguments,
                                                      @Nullable String workingDirectory, boolean isLoginShell) {
        List<String> result = new ArrayList<>();
        result.add(TermuxShellEnvironment.getGuestLauncherPath());
        result.add("-r");
        result.add(TermuxConstants.TERMUX_ROOTFS_DIR_PATH);
        result.add("-0");
        result.add("--link2symlink");
        // The files of the user are kept outside the rootfs so that a rootfs update never touches
        // them, and are shown to the guest as its home directory.
        result.add("-b");
        result.add(TermuxConstants.TERMUX_HOME_DIR_PATH + ":" + TermuxShellEnvironment.GUEST_HOME_DIR_PATH);

        // The launcher of the command changes into the host side working directory before exec,
        // so the guest has to start in the matching guest directory instead.
        String guestWorkingDirectory = TermuxShellEnvironment.GUEST_HOME_DIR_PATH;
        if (workingDirectory != null && isPathVisibleToGuest(workingDirectory))
            guestWorkingDirectory = getGuestPath(workingDirectory);
        result.add("-w");
        result.add(guestWorkingDirectory);

        String guestExecutable = getGuestPath(executable);
        String interpreter = getGuestCommandInterpreter(executable);
        if (interpreter != null)
            result.add(interpreter);

        // Proroot does not allow an argv0 override for the guest command, so a login shell has to
        // be requested with its own option.
        boolean addLoginOption = isLoginShell && isGuestLoginShell(guestExecutable);
        if (addLoginOption) {
            List<String> shellArguments = new ArrayList<>();
            shellArguments.add("-l");
            if (arguments != null) Collections.addAll(shellArguments, arguments);
            arguments = shellArguments.toArray(new String[0]);
        }

        result.add(guestExecutable);
        if (arguments != null) Collections.addAll(result, arguments);
        return result.toArray(new String[0]);
    }

    /** Check if the guest executable is a shell that supports a login shell. */
    private static boolean isGuestLoginShell(@NonNull String guestExecutable) {
        String binary = new File(guestExecutable).getName();
        for (String shellBinary : TermuxShellEnvironment.GUEST_LOGIN_SHELL_BINARIES) {
            if (shellBinary.equals(binary)) return true;
        }
        return false;
    }

    /**
     * Get the guest interpreter to use for a command that is not an elf file, like a script without
     * a shebang or with a shebang pointing at a binary that the guest does not provide.
     *
     * @param executable The host side path of the file to execute.
     * @return Returns the guest interpreter path or {@code null} if the file can be executed directly.
     */
    @Nullable
    private static String getGuestCommandInterpreter(@NonNull String executable) {
        boolean isElfMagic = false;
        boolean isShebang = false;
        String shebangBinary = null;
        try {
            try (FileInputStream in = new FileInputStream(new File(executable))) {
                byte[] buffer = new byte[256];
                int bytesRead = in.read(buffer);
                if (bytesRead > 4) {
                    if (buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
                        isElfMagic = true;
                    } else if (buffer[0] == '#' && buffer[1] == '!') {
                        isShebang = true;
                        StringBuilder builder = new StringBuilder();
                        for (int i = 2; i < bytesRead; i++) {
                            char c = (char) buffer[i];
                            if (c == ' ' || c == '\n') {
                                if (builder.length() == 0) {
                                    // Skip whitespace after shebang.
                                } else {
                                    // End of shebang.
                                    break;
                                }
                            } else {
                                builder.append(c);
                            }
                        }
                        String shebangExecutable = builder.toString();
                        shebangBinary = shebangExecutable.substring(shebangExecutable.lastIndexOf('/') + 1);
                    }
                }
            }
        } catch (IOException e) {
            // Ignore, the guest will report the error for a missing file.
        }

        if (isElfMagic) return null;

        // Keep the shebang interpreter if the guest provides it, otherwise fall back to the guest shell.
        if (isShebang && shebangBinary != null && !shebangBinary.isEmpty() &&
            new File(TermuxConstants.TERMUX_ROOTFS_DIR_PATH + "/usr/bin/" + shebangBinary).exists())
            return null;

        return TermuxShellEnvironment.GUEST_SHELL;
    }

    /**
     * Setup shell command arguments for the execute. The file interpreter may be prefixed to
     * command arguments if needed.
     */
    @NonNull
    public static String[] setupShellCommandArguments(@NonNull String executable, @Nullable String[] arguments) {
        // The file to execute may either be:
        // - An elf file, in which we execute it directly.
        // - A script file without shebang, which we execute with our standard shell $PREFIX/bin/sh instead of the
        //   system /system/bin/sh. The system shell may vary and may not work at all due to LD_LIBRARY_PATH.
        // - A file with shebang, which we try to handle with e.g. /bin/foo -> $PREFIX/bin/foo.
        String interpreter = null;
        try {
            File file = new File(executable);
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[256];
                int bytesRead = in.read(buffer);
                if (bytesRead > 4) {
                    if (buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
                        // Elf file, do nothing.
                    } else if (buffer[0] == '#' && buffer[1] == '!') {
                        // Try to parse shebang.
                        StringBuilder builder = new StringBuilder();
                        for (int i = 2; i < bytesRead; i++) {
                            char c = (char) buffer[i];
                            if (c == ' ' || c == '\n') {
                                if (builder.length() == 0) {
                                    // Skip whitespace after shebang.
                                } else {
                                    // End of shebang.
                                    String shebangExecutable = builder.toString();
                                    if (shebangExecutable.startsWith("/usr") || shebangExecutable.startsWith("/bin")) {
                                        String[] parts = shebangExecutable.split("/");
                                        String binary = parts[parts.length - 1];
                                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + binary;
                                    }
                                    break;
                                }
                            } else {
                                builder.append(c);
                            }
                        }
                    } else {
                        // No shebang and no ELF, use standard shell.
                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
                    }
                }
            }
        } catch (IOException e) {
            // Ignore.
        }

        List<String> result = new ArrayList<>();
        if (interpreter != null) result.add(interpreter);
        result.add(executable);
        if (arguments != null) Collections.addAll(result, arguments);
        return result.toArray(new String[0]);
    }

    /** Clear files under {@link TermuxConstants#TERMUX_TMP_PREFIX_DIR_PATH}. */
    public static void clearTermuxTMPDIR(boolean onlyIfExists) {
        // Existence check before clearing may be required since clearDirectory() will automatically
        // re-create empty directory if doesn't exist, which should not be done for things like
        // termux-reset (d6eb5e35). Moreover, TMPDIR must be a directory and not a symlink, this can
        // also allow users who don't want TMPDIR to be cleared automatically on termux exit, since
        // it may remove files still being used by background processes (#1159).
        if(onlyIfExists && !FileUtils.directoryFileExists(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, false))
            return;

        Error error;

        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        int days = properties.getDeleteTMPDIRFilesOlderThanXDaysOnExit();

        // Disable currently until FileUtils.deleteFilesOlderThanXDays() is fixed.
        if (days > 0)
            days = 0;

        if (days < 0) {
            Logger.logInfo(LOG_TAG, "Not clearing termux $TMPDIR");
        } else if (days == 0) {
            error = FileUtils.clearDirectory("$TMPDIR",
                FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null));
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Failed to clear termux $TMPDIR\n" + error);
            }
        } else {
            error = FileUtils.deleteFilesOlderThanXDays("$TMPDIR",
                FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null),
                TrueFileFilter.INSTANCE, days, true, FileTypes.FILE_TYPE_ANY_FLAGS);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Failed to delete files from termux $TMPDIR older than " + days + " days\n" + error);
            }
        }
    }

}
