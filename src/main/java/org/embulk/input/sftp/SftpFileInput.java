package org.embulk.input.sftp;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.commons.vfs2.provider.UriParser;
import org.apache.commons.vfs2.provider.local.GenericFileNameParser;
import org.apache.commons.vfs2.provider.local.LocalFileNameParser;
import org.apache.commons.vfs2.provider.sftp.IdentityInfo;
import org.apache.commons.vfs2.provider.sftp.SftpFileNameParser;
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.embulk.config.ConfigException;
import org.embulk.config.TaskReport;
import org.embulk.spi.Exec;
import org.embulk.spi.TransactionalFileInput;
import org.embulk.spi.unit.LocalFile;
import org.embulk.spi.util.InputStreamFileInput;
import org.embulk.spi.util.RetryExecutor.RetryGiveupException;
import org.embulk.spi.util.RetryExecutor.Retryable;
import org.slf4j.Logger;
import static org.embulk.spi.util.RetryExecutor.retryExecutor;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

public class SftpFileInput
        extends InputStreamFileInput
        implements TransactionalFileInput
{
    private static final Logger log = Exec.getLogger(SftpFileInput.class);
    private static boolean isMatchLastKey = false;

    public SftpFileInput(PluginTask task, int taskIndex)
    {
        super(task.getBufferAllocator(), new SingleFileProvider(task, taskIndex, initializeStandardFileSystemManager(), initializeFsOptions(task)));
    }

    public void abort()
    {
    }

    public TaskReport commit()
    {
        return Exec.newTaskReport();
    }

    @Override
    public void close()
    {
    }

    private static StandardFileSystemManager initializeStandardFileSystemManager()
    {
        if (!log.isDebugEnabled()) {
            // TODO: change logging format: org.apache.commons.logging.Log
            System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
        }

        StandardFileSystemManager manager = new StandardFileSystemManager();
        manager.setClassLoader(SftpFileInput.class.getClassLoader());
        try {
            manager.init();
        }
        catch (FileSystemException ex) {
            Throwables.propagate(ex);
        }

        return manager;
    }

    private static String initializeUserInfo(PluginTask task)
    {
        String userInfo = task.getUser();
        if (task.getPassword().isPresent()) {
            userInfo += ":" + task.getPassword().get();
        }
        return userInfo;
    }

    public static FileSystemOptions initializeFsOptions(PluginTask task)
    {
        FileSystemOptions fsOptions = new FileSystemOptions();

        try {
            SftpFileSystemConfigBuilder builder = SftpFileSystemConfigBuilder.getInstance();
            builder.setUserDirIsRoot(fsOptions, task.getUserDirIsRoot());
            builder.setTimeout(fsOptions, task.getSftpConnectionTimeout() * 1000);
            builder.setStrictHostKeyChecking(fsOptions, "no");

            if (task.getSecretKeyFile().isPresent()) {
                IdentityInfo identityInfo = new IdentityInfo(
                        new File((task.getSecretKeyFile().transform(localFileToPathString()).get())),
                        task.getSecretKeyPassphrase().getBytes()
                );
                builder.setIdentityInfo(fsOptions, identityInfo);
                log.info("set identity: {}", task.getSecretKeyFile().get());
            }

            if (task.getProxy().isPresent()) {
                ProxyTask proxy = task.getProxy().get();

                ProxyTask.ProxyType.setProxyType(builder, fsOptions, proxy.getType());

                if (proxy.getHost().isPresent()) {
                    builder.setProxyHost(fsOptions, proxy.getHost().get());
                    builder.setProxyPort(fsOptions, proxy.getPort());
                    log.info("Using proxy {}:{} proxy_type:{}", proxy.getHost().get(), proxy.getPort(), proxy.getType().toString());
                }

                if (proxy.getUser().isPresent()) {
                    builder.setProxyUser(fsOptions, proxy.getUser().get());
                }

                if (proxy.getPassword().isPresent()) {
                    builder.setProxyPassword(fsOptions, proxy.getPassword().get());
                }

                if (proxy.getCommand().isPresent()) {
                    builder.setProxyCommand(fsOptions, proxy.getCommand().get());
                }
            }
        }
        catch (FileSystemException ex) {
            Throwables.propagate(ex);
        }

        return fsOptions;
    }

    public static String getSftpFileUri(PluginTask task, String path)
    {
        try {
            String uri = new URI("sftp", initializeUserInfo(task), task.getHost(), task.getPort(), path, null, null).toString();
            log.info("Connecting to sftp://{}:***@{}:{}/", task.getUser(), task.getHost(), task.getPort());
            return uri;
        }
        catch (URISyntaxException ex) {
            throw new ConfigException(ex);
        }
    }

    public static String getRelativePath(PluginTask task, Optional<String> uri)
    {
        try {
            if (!uri.isPresent()) {
                return null;
            }
            else if (!task.getSecretKeyFile().isPresent() && task.getPassword().isPresent()) {
                return getRelativePathFromURIwithPassword(task, uri);
            }
            else {
                String uriString = uri.get();
                String scheme = UriParser.extractScheme(uriString);
                if (scheme.equals("sftp")) {
                    return SftpFileNameParser.getInstance().parseUri(null, null, uriString).getPath();
                } else if (scheme.isEmpty()) {
                    return GenericFileNameParser.getInstance().parseUri(null, null, uriString).getPath();
                } else {
                    throw new ConfigException("SFTP Plugin only support SFTP scheme");
                }
            }
        }
        catch (FileSystemException ex) {
            throw new ConfigException("Failed to generate last_path due to sftp file name parse failure", ex);
        }
    }

    private static String getRelativePathFromURIwithPassword(final PluginTask task, final Optional<String> uri)
    {
        try {
            return retryExecutor()
                    .withRetryLimit(task.getMaxConnectionRetry())
                    .withInitialRetryWait(500)
                    .withMaxRetryWait(30 * 1000)
                    .runInterruptible(new Retryable<String>() {
                        @Override
                        public String call() throws URISyntaxException, IOException
                        {
                            log.info("Creating last_path from URI contains password in FileList.");
                            StandardFileSystemManager manager = initializeStandardFileSystemManager();

                            String prefix = new URI("sftp", initializeUserInfo(task), task.getHost(), task.getPort(), null, null, null).toString();
                            prefix = manager.resolveFile(prefix).toString();
                            // To avoid URI parse failure when password contains special characters
                            String newUri = uri.get().replace(prefix, "sftp://user:password@example.com/");

                            return new URI(newUri).getPath();
                        }

                        @Override
                        public boolean isRetryableException(Exception exception)
                        {
                            if (exception instanceof URISyntaxException) {
                                // Don't throw cause because URISyntaxException shows password
                                throw new ConfigException("Failed to generate last_path due to URI parse failure that contains invalid file path or password.");
                            }
                            return true;
                        }

                        @Override
                        public void onRetry(Exception exception, int retryCount, int retryLimit, int retryWait) throws RetryGiveupException
                        {
                            String message = String.format("SFTP List request failed. Retrying %d/%d after %d seconds. Message: %s",
                                    retryCount, retryLimit, retryWait / 1000, exception.getMessage());
                            if (retryCount % 3 == 0) {
                                log.warn(message, exception);
                            }
                            else {
                                log.warn(message);
                            }
                        }

                        @Override
                        public void onGiveup(Exception firstException, Exception lastException) throws RetryGiveupException
                        {
                        }
                    });
        }
        catch (RetryGiveupException ex) {
            throw new ConfigException("Failed to generate last_path due to SFTP connection failure");
        }
        catch (InterruptedException ex) {
            Throwables.propagate(ex);
        }
        return null;
    }

    public static FileList listFilesByPrefix(final PluginTask task)
    {
        final FileList.Builder builder = new FileList.Builder(task);
        int maxConnectionRetry = task.getMaxConnectionRetry();

        try {
            return retryExecutor()
                    .withRetryLimit(maxConnectionRetry)
                    .withInitialRetryWait(500)
                    .withMaxRetryWait(30 * 1000)
                    .runInterruptible(new Retryable<FileList>() {
                        @Override
                        public FileList call() throws IOException
                        {
                            String lastKey = null;
                            log.info("Getting to download file list");
                            StandardFileSystemManager manager = initializeStandardFileSystemManager();
                            FileSystemOptions fsOptions = initializeFsOptions(task);

                            if (task.getLastPath().isPresent() && !task.getLastPath().get().isEmpty()) {
                                lastKey = manager.resolveFile(getSftpFileUri(task, task.getLastPath().get()), fsOptions).toString();
                            }

                            FileObject files = manager.resolveFile(getSftpFileUri(task, task.getPathPrefix()), fsOptions);
                            String basename = FilenameUtils.getBaseName(task.getPathPrefix());
                            if (files.isFolder()) {
                                //path_prefix is a folder, we add everything in that folder
                                FileObject[] children = files.getChildren();
                                Arrays.sort(children);
                                for (FileObject f : children) {
                                    if (f.isFile()) {
                                        addFileToList(builder, f.toString(), f.getContent().getSize(), "", lastKey);
                                    }
                                }
                            } else if (files.isFile()) {
                                //path_prefix is a file then we just need to add that file
                                addFileToList(builder, files.toString(), files.getContent().getSize(), "", lastKey);
                            } else {
                                // path_prefix is neither file or folder, then we scan the parent folder to file path
                                // that match the path_prefix basename
                                FileObject parent = files.getParent();
                                FileObject[] children = parent.getChildren();
                                Arrays.sort(children);
                                for (FileObject f : children) {
                                    if (f.isFile()) {
                                        addFileToList(builder, f.toString(), f.getContent().getSize(), basename, lastKey);
                                    }
                                }
                            }
                            return builder.build();
                        }

                        @Override
                        public boolean isRetryableException(Exception exception)
                        {
                            return true;
                        }

                        @Override
                        public void onRetry(Exception exception, int retryCount, int retryLimit, int retryWait)
                                throws RetryGiveupException
                        {
                            String message = String.format("SFTP GET request failed. Retrying %d/%d after %d seconds. Message: %s",
                                    retryCount, retryLimit, retryWait / 1000, exception.getMessage());
                            if (retryCount % 3 == 0) {
                                log.warn(message, exception);
                            }
                            else {
                                log.warn(message);
                            }
                        }

                        @Override
                        public void onGiveup(Exception firstException, Exception lastException)
                                throws RetryGiveupException
                        {
                        }
                    });
        }
        catch (RetryGiveupException ex) {
            throw Throwables.propagate(ex.getCause());
        }
        catch (InterruptedException ex) {
            throw Throwables.propagate(ex);
        }
    }

    private static void addFileToList(FileList.Builder builder, String fileName, long fileSize, String basename, String lastKey)
    {
        if (!basename.isEmpty()) {
            String remoteBasename = FilenameUtils.getBaseName(fileName);
            if (remoteBasename.startsWith(basename)) {
                if (lastKey != null && !isMatchLastKey) {
                    if (fileName.equals(lastKey)) {
                        isMatchLastKey = true;
                    }
                    return;
                }
                builder.add(fileName, fileSize);
            }
        }
        else {
            if (lastKey != null && !isMatchLastKey) {
                if (fileName.equals(lastKey)) {
                    isMatchLastKey = true;
                }
                return;
            }
            builder.add(fileName, fileSize);
        }
    }

    private static Function<LocalFile, String> localFileToPathString()
    {
        return new Function<LocalFile, String>()
        {
            public String apply(LocalFile file)
            {
                return file.getPath().toString();
            }
        };
    }
}
