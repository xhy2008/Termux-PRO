package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;

import com.termux.pro.R;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;

/**
 * Installs the Debian guest rootfs the {@code com.termux.pro} sessions run in.
 *
 * (1) If the rootfs directory already exists, assume that it is correct and be done.
 * (2) A progress dialog is shown while the rootfs tarball is downloaded and extracted.
 * (3) The tarball is extracted into a staging directory, then moved into place, so that a failed
 *     install never leaves a half installed rootfs behind.
 * (4) Guest configuration files that depend on the host paths are written into the new rootfs.
 */
public final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /**
     * Directory of the pinned Debian lxc image on the mirrors. The image is pinned by date since
     * the mirrors only expose "latest" through a symlink that is not served, so this has to be
     * bumped manually to pick up a newer build.
     */
    private static final String ROOTFS_IMAGE_PATH =
        "lxc-images/images/debian/trixie/arm64/default/20261015_05%3A24/rootfs.tar.xz";

    /**
     * SHA-256 of the image above, as published in the {@code SHA256SUMS} file next to it, so that a
     * truncated or corrupted download is never extracted into a half installed rootfs.
     */
    private static final String ROOTFS_IMAGE_SHA256 =
        "7d4fd0be487a4ee54a574fba49312f95e9151a2a2052f2eab9ac46ca5860c6df";

    /**
     * Mirrors hosting the image. The image path is relative to each mirror root.
     *
     * Mirrors.tuna.tsinghua.edu.cn and its clones are deliberately not used: their anti-leech rule
     * answers HTTP 403 to every request made by a Java HTTP client, whatever the User-Agent header
     * says, so they can never be reached from the app.
     */
    private static final String[] ROOTFS_URLS = {
        "https://mirrors.nju.edu.cn/" + ROOTFS_IMAGE_PATH,
        "https://mirrors.sdu.edu.cn/" + ROOTFS_IMAGE_PATH,
        "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20261015_05%3A24/rootfs.tar.xz",
    };

    /** apt mirror used by the guest for the archive and its updates. */
    private static final String DEBIAN_MIRROR_URL = "https://mirrors.cloud.tencent.com/debian";

    /**
     * apt mirror used by the guest for security updates. Debian keeps the security suite in its own
     * archive instead of under {@code debian/}, so it needs a separate url.
     */
    private static final String DEBIAN_SECURITY_MIRROR_URL = "https://mirrors.cloud.tencent.com/debian-security";

    private static final String RESOLV_CONF =
        "nameserver 223.5.5.5\n" +
        "nameserver 119.29.29.29\n";

    private static final String SOURCES_LIST =
        "deb " + DEBIAN_MIRROR_URL + " trixie main contrib\n" +
        "deb " + DEBIAN_MIRROR_URL + " trixie-updates main contrib\n" +
        "deb " + DEBIAN_SECURITY_MIRROR_URL + " trixie-security main contrib\n";

    private TermuxInstaller() { }

    /** Performs rootfs setup if necessary. */
    public static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that the termux files
        // directory is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        if (isRootfsInstalled()) {
            whenDone.run();
            return;
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null,
            activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    installRootfs(progress, activity, whenDone);
                } catch (Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null,
                        Logger.getStackTracesStringArray(e)));
                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    /** Check if a usable rootfs is installed. */
    public static boolean isRootfsInstalled() {
        // The bash binary of the guest is what every session is started with, so its presence is
        // used as the marker for a fully installed rootfs.
        return new File(TermuxConstants.TERMUX_ROOTFS_DIR_PATH + "/bin/bash").isFile() ||
            new File(TermuxConstants.TERMUX_ROOTFS_DIR_PATH + "/usr/bin/bash").isFile();
    }

    private static void installRootfs(ProgressDialog progress, Activity activity, Runnable whenDone) throws Exception {
        Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " rootfs.");

        File stagingDir = new File(TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH);
        FileUtils.deleteFile("rootfs staging directory", stagingDir.getAbsolutePath(), true);
        FileUtils.deleteFile("rootfs directory", TermuxConstants.TERMUX_ROOTFS_DIR_PATH, true);

        Error error = FileUtils.createDirectoryFile("rootfs staging directory", stagingDir.getAbsolutePath());
        if (error != null)
            throw new IOException(error.getMessage());

        downloadRootfs(stagingDir);
        writeGuestConfig(stagingDir);

        File rootfsDir = new File(TermuxConstants.TERMUX_ROOTFS_DIR_PATH);
        if (!stagingDir.renameTo(rootfsDir))
            throw new IOException("Moving rootfs staging directory to \"" + rootfsDir.getAbsolutePath() + "\" failed");

        // Make sure the directories the sessions need exist inside the app files directory
        FileUtils.createDirectoryFile("termux home directory", TermuxConstants.TERMUX_HOME_DIR_PATH);
        FileUtils.createDirectoryFile("termux tmpfs directory", TermuxShellEnvironment.getProrootTmpDir(activity));

        Logger.logInfo(LOG_TAG, "Rootfs installed successfully.");

        activity.runOnUiThread(whenDone);
    }

    /** Try every mirror in order until one of them yields a verified rootfs. */
    private static void downloadRootfs(File stagingDir) throws IOException {
        IOException lastException = null;

        for (String urlString : ROOTFS_URLS) {
            Logger.logInfo(LOG_TAG, "Downloading rootfs from \"" + urlString + "\".");
            try {
                downloadAndExtractRootfs(urlString, stagingDir);
                Logger.logInfo(LOG_TAG, "Rootfs downloaded and extracted from \"" + urlString + "\".");
                return;
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Downloading rootfs from \"" + urlString + "\" failed: " + e.getMessage());
                lastException = e;
                FileUtils.deleteFile("rootfs staging directory", stagingDir.getAbsolutePath(), true);
                if (!stagingDir.mkdirs())
                    throw new IOException("Create rootfs staging directory \"" + stagingDir.getAbsolutePath() + "\" failed");
            }
        }

        throw lastException != null ? lastException : new IOException("No rootfs mirror available");
    }

    /**
     * Download the tarball of one mirror into a temporary file, check its hash and extract it into
     * {@code targetDir}.
     *
     * The download is buffered to a file instead of being piped into the extractor so that a
     * connection dropped in the middle fails loudly on the hash check instead of leaving a
     * partially extracted rootfs behind.
     */
    private static void downloadAndExtractRootfs(String urlString, File targetDir) throws IOException {
        File tarFile = new File(targetDir.getParentFile(), "rootfs.tar.xz");
        try {
            downloadRootfs(urlString, tarFile);

            String actual = sha256OfFile(tarFile);
            if (!ROOTFS_IMAGE_SHA256.equalsIgnoreCase(actual))
                throw new IOException("SHA-256 of \"" + tarFile.getName() + "\" is " + actual +
                    ", expected " + ROOTFS_IMAGE_SHA256);

            try (InputStream in = new BufferedInputStream(new FileInputStream(tarFile))) {
                extractRootfs(in, targetDir);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(tarFile.toPath());
        }
    }

    private static void downloadRootfs(String urlString, File destination) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK)
                throw new IOException("HTTP response code " + responseCode + " for \"" + urlString + "\"");

            long expectedLength = connection.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    total += read;
                }
                if (expectedLength > 0 && total != expectedLength)
                    throw new IOException("Downloaded " + total + " of " + expectedLength +
                        " bytes from \"" + urlString + "\"");
                Logger.logInfo(LOG_TAG, "Downloaded " + total + " bytes.");
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Compute the lowercase hex SHA-256 of a file. */
    private static String sha256OfFile(File file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1)
                    digest.update(buffer, 0, read);
            }
            StringBuilder builder = new StringBuilder(64);
            for (byte b : digest.digest())
                builder.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    /**
     * Extract the {@code rootfs.tar.xz} stream into {@code targetDir}.
     *
     * The tarball is owned by root and the app has neither root nor hardlink permissions on app
     * private storage, so ownership and modes are ignored, hardlinks are recreated as copies and
     * device and fifo entries are skipped since proroot provides {@code /dev} at runtime.
     */
    private static void extractRootfs(InputStream compressionStream, File targetDir) throws IOException {
        // The archive contains the "." entry itself, so the staging directory is a valid
        // extraction target and must be allowed next to its descendants.
        String targetPath = targetDir.getCanonicalPath();

        try (XZCompressorInputStream xzStream = new XZCompressorInputStream(compressionStream);
             TarArchiveInputStream tarStream = new TarArchiveInputStream(xzStream)) {

            TarArchiveEntry entry;
            while ((entry = tarStream.getNextEntry()) != null) {
                File targetFile = new File(targetDir, entry.getName());

                // Reject entries that would escape the target directory
                String entryPath = targetFile.getCanonicalPath();
                if (!entryPath.equals(targetPath) && !entryPath.startsWith(targetPath + File.separator))
                    throw new IOException("Rootfs entry outside of target directory: " + entry.getName());

                if (entry.isDirectory()) {
                    if (!targetFile.isDirectory() && !targetFile.mkdirs() && !targetFile.isDirectory())
                        throw new IOException("Create directory \"" + targetFile.getAbsolutePath() + "\" failed");
                    continue;
                }

                File parent = targetFile.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
                    throw new IOException("Create directory \"" + parent.getAbsolutePath() + "\" failed");

                if (entry.isSymbolicLink()) {
                    // Files.deleteIfExists() removes the link itself instead of following it, which
                    // is what a dangling or previously extracted link needs before replacement.
                    deleteIfExists(targetFile);
                    try {
                        Os.symlink(entry.getLinkName(), targetFile.getAbsolutePath());
                    } catch (android.system.ErrnoException e) {
                        throw new IOException("Create symlink \"" + targetFile.getAbsolutePath() + "\" failed", e);
                    }
                } else if (entry.isLink()) {
                    // Hardlinks are not permitted on app private storage, copy the target instead.
                    deleteIfExists(targetFile);
                    copyFile(new File(targetDir, entry.getLinkName()), targetFile);
                } else if (entry.isFile()) {
                    deleteIfExists(targetFile);
                    try (FileOutputStream out = new FileOutputStream(targetFile)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = tarStream.read(buffer)) != -1)
                            out.write(buffer, 0, read);
                    }
                    // Only the executable bit matters, the app is the owner of every file.
                    if ((entry.getMode() & 0100) != 0)
                        targetFile.setExecutable(true, false);
                }
            }
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (InputStream in = new java.io.FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1)
                out.write(buffer, 0, read);
        }
    }

    /** Write the guest files that the Debian image does not provide on its own. */
    private static void writeGuestConfig(File rootfsDir) throws IOException {
        // The image points /etc/resolv.conf at the systemd-resolved stub socket, which does not run here.
        File resolvConf = new File(rootfsDir, "etc/resolv.conf");
        deleteIfExists(resolvConf);
        writeFile(resolvConf, RESOLV_CONF);

        File aptDir = new File(rootfsDir, "etc/apt");
        File sourcesList = new File(aptDir, "sources.list");
        deleteIfExists(sourcesList);
        writeFile(sourcesList, SOURCES_LIST);

        // systemd and its helpers cannot run in the guest, so keep them from being pulled in as
        // recommends and from touching the init system on package install.
        writeFile(new File(rootfsDir, "etc/apt/apt.conf.d/90termux-pro"),
            "APT::Install-Recommends \"false\";\n" +
            "APT::Install-Suggests \"false\";\n" +
            "dpkg::options { \"--force-confnew\"; }\n");

        // The guest home of the user is the app files home directory, so seed it with the Debian
        // default dotfiles the image ships for new users.
        File skelDir = new File(rootfsDir, "etc/skel");
        File homeDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (!homeDir.isDirectory() && !homeDir.mkdirs() && !homeDir.isDirectory())
            throw new IOException("Create home directory \"" + homeDir.getAbsolutePath() + "\" failed");
        for (String dotFile : new String[]{".bashrc", ".profile"}) {
            File source = new File(skelDir, dotFile);
            File destination = new File(homeDir, dotFile);
            if (source.isFile() && !java.nio.file.Files.exists(destination.toPath()))
                copyFile(source, destination);
        }
    }

    /**
     * Delete a file, symlink or directory entry if it exists.
     *
     * {@link java.nio.file.Files#deleteIfExists(java.nio.file.Path)} is used instead of
     * {@link File#exists()} and {@link File#delete()} since it does not follow symlinks, so
     * dangling symlinks like the {@code /etc/resolv.conf} of the Debian image are removed as well
     * instead of being reported as non existent.
     */
    private static void deleteIfExists(File file) throws IOException {
        java.nio.file.Files.deleteIfExists(file.toPath());
    }

    private static void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
            throw new IOException("Create directory \"" + parent.getAbsolutePath() + "\" failed");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file))) {
            writer.write(content);
        }
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title)
                    .setMessage(activity.getString(R.string.bootstrap_error_body) + "\n\n" + message)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("rootfs directory", TermuxConstants.TERMUX_ROOTFS_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (android.view.WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, true, true);
    }

    /** Create the storage symlinks inside the host side home directory shown to the guest. */
    public static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, true, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks in \"" + storageDir.getAbsolutePath() + "\".");

                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    createPublicDirSymlink(storageDir, "documents", Environment.DIRECTORY_DOCUMENTS);
                    createPublicDirSymlink(storageDir, "downloads", Environment.DIRECTORY_DOWNLOADS);
                    createPublicDirSymlink(storageDir, "dcim", Environment.DIRECTORY_DCIM);
                    createPublicDirSymlink(storageDir, "pictures", Environment.DIRECTORY_PICTURES);
                    createPublicDirSymlink(storageDir, "music", Environment.DIRECTORY_MUSIC);
                    createPublicDirSymlink(storageDir, "movies", Environment.DIRECTORY_MOVIES);
                    createPublicDirSymlink(storageDir, "podcasts", Environment.DIRECTORY_PODCASTS);

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                        createPublicDirSymlink(storageDir, "audiobooks", Environment.DIRECTORY_AUDIOBOOKS);

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, true, true);
                }
            }
        }.start();
    }

    private static void createPublicDirSymlink(File storageDir, String name, String type) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(type);
            Os.symlink(dir.getAbsolutePath(), new File(storageDir, name).getAbsolutePath());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Create storage symlink for \"" + name + "\" failed: " + e.getMessage());
        }
    }

}
