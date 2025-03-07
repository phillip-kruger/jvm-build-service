package com.redhat.hacbs.artifactcache.services;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import org.jboss.resteasy.reactive.ClientWebApplicationException;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.HashingOutputStream;
import com.redhat.hacbs.classfile.tracker.TrackingData;

import io.quarkus.logging.Log;

/**
 * A per repository cache implementation
 */
public class RepositoryCache {

    public static final String SHA_1 = ".sha1";
    public static final String CACHEMISS = ".cachemiss";
    public static final String DOWNLOADS = ".downloads";
    public static final String HEADERS = ".hacbs-http-headers";
    public static final String ORIGINAL = "original";
    public static final String TRANSFORMED = "transformed";
    final Path path;
    final Path downloaded;
    final Path transformed;
    final Path tempDownloads;
    final Repository repository;

    /**
     * Tracks in progress downloads to prevent concurrency issues
     */
    final ConcurrentMap<String, DownloadingFile> inProgressDownloads = new ConcurrentHashMap<>();
    final ConcurrentMap<String, CountDownLatch> inProgressTransformations = new ConcurrentHashMap<>();

    public RepositoryCache(Path path, Repository repository) throws Exception {
        this.path = path;
        this.downloaded = path.resolve(ORIGINAL);
        this.transformed = path.resolve(TRANSFORMED);
        this.tempDownloads = path.resolve(DOWNLOADS);
        this.repository = repository;
        Files.createDirectories(downloaded);
        Files.createDirectories(transformed);
        Files.createDirectories(tempDownloads);
        Log.infof("Creating cache with path %s", path.toAbsolutePath());
    }

    public Optional<ArtifactResult> getArtifactFile(String group, String artifact, String version, String target,
            boolean tracked) {
        //TODO: we don't really care about the policy when using standard maven repositories
        String targetFile = group.replaceAll("\\.", File.separator) + File.separator + artifact
                + File.separator + version + File.separator + target;
        return handleFile(targetFile, group.replaceAll("/", ".") + ":" + artifact + ":" + version,
                (c) -> c.getArtifactFile(group, artifact, version, target), tracked);
    }

    public Optional<ArtifactResult> getMetadataFile(String group, String target) {
        try {
            return repository.getClient().getMetadataFile(group, target);
        } catch (Exception e) {
            Log.debugf(e, "Failed to metadata %s/%s from %s", group, target, repository.getUri());
            return Optional.empty();
        }
    }

    private Optional<ArtifactResult> handleFile(String targetFile, String gav,
            Function<RepositoryClient, Optional<ArtifactResult>> clientInvocation, boolean tracked) {
        try {
            var check = inProgressDownloads.get(targetFile);
            if (check != null) {
                check.awaitReady();
            }
            Path actual = downloaded.resolve(targetFile);
            Path trackedFile = transformed.resolve(targetFile);
            if (Files.exists(actual)) {
                //we need to double check, there is a small window for a race here
                //it should not matter as we do an atomic move, but better to be safe
                check = inProgressDownloads.get(targetFile);
                if (check != null) {
                    check.awaitReady();
                }
                return handleDownloadedFile(actual, trackedFile, tracked, gav);
            }
            DownloadingFile newFile = new DownloadingFile(targetFile);
            var existing = inProgressDownloads.putIfAbsent(targetFile, newFile);
            if (existing != null) {
                //another thread is downloading this
                existing.awaitReady();
                //the result may have been a miss, so we need to check the file is there
                //if the file is not there it may mean that the sha1 was wrong
                //so we never cache it
                if (Files.exists(actual)) {
                    return handleDownloadedFile(actual, trackedFile, tracked, gav);
                }
            }
            return newFile.download(clientInvocation, repository.getClient(), actual, trackedFile,
                    tempDownloads, tracked, gav);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<ArtifactResult> handleDownloadedFile(Path downloaded, Path trackedFileTarget, boolean tracked, String gav)
            throws IOException, InterruptedException {

        //same headers for both
        String fileName = downloaded.getFileName().toString();
        Path headers = downloaded.getParent().resolve(fileName + HEADERS);
        Path sha1 = downloaded.getParent().resolve(fileName + SHA_1);
        Map<String, String> headerMap = new HashMap<>();
        if (Files.exists(headers)) {
            try (InputStream in = Files.newInputStream(headers)) {
                Properties p = new Properties();
                p.load(in);
                for (var i : p.entrySet()) {
                    headerMap.put(i.getKey().toString().toLowerCase(), i.getValue().toString());
                }
            }
        }
        boolean jarFile = downloaded.toString().endsWith(".jar");
        boolean shaFile = downloaded.toString().endsWith(".jar.sha1");
        if (tracked && (jarFile || shaFile)) {
            Path instrumentedSha;
            Path trackedJarFile;
            if (jarFile) {
                instrumentedSha = trackedFileTarget.getParent().resolve(fileName + SHA_1);
                trackedJarFile = trackedFileTarget;
            } else {
                instrumentedSha = trackedFileTarget;
                trackedJarFile = trackedFileTarget.getParent()
                        .resolve(fileName.substring(0, fileName.length() - SHA_1.length()));
            }
            CountDownLatch existing = inProgressTransformations.get(gav);
            if (existing != null) {
                existing.await();
            }
            if (!Files.exists(trackedJarFile)) {
                CountDownLatch myLatch = new CountDownLatch(1);
                existing = inProgressTransformations.putIfAbsent(gav, myLatch);
                if (existing != null) {
                    existing.await();
                } else {
                    Files.createDirectories(trackedJarFile.getParent());
                    try (OutputStream out = Files.newOutputStream(trackedJarFile); var in = Files.newInputStream(downloaded)) {
                        HashingOutputStream hashingOutputStream = new HashingOutputStream(out);
                        ClassFileTracker.addTrackingDataToJar(in,
                                new TrackingData(gav, repository.getName()), hashingOutputStream);
                        hashingOutputStream.close();

                        Files.writeString(instrumentedSha, hashingOutputStream.getHash());
                    } catch (Throwable e) {
                        Log.errorf(e, "Failed to track jar %s", downloaded);
                        Files.delete(trackedJarFile);
                    } finally {
                        myLatch.countDown();
                        inProgressTransformations.remove(gav);
                    }
                }
            }
            if (Files.exists(trackedJarFile)) {
                if (jarFile) {
                    String sha = null;
                    if (Files.exists(instrumentedSha)) {
                        sha = Files.readString(instrumentedSha, StandardCharsets.UTF_8);
                    }
                    return Optional
                            .of(new ArtifactResult(Files.newInputStream(trackedJarFile), Files.size(trackedJarFile),
                                    Optional.ofNullable(sha), headerMap));
                } else {
                    return Optional
                            .of(new ArtifactResult(Files.newInputStream(instrumentedSha), Files.size(instrumentedSha),
                                    Optional.empty(), Map.of()));
                }
            }

        }
        String sha = null;
        if (Files.exists(sha1)) {
            sha = Files.readString(sha1, StandardCharsets.UTF_8);
        }
        return Optional
                .of(new ArtifactResult(Files.newInputStream(downloaded), Files.size(downloaded), Optional.ofNullable(sha),
                        headerMap));

    }

    /**
     * Represents a file that is in the process of being downloaded into the cache
     */
    final class DownloadingFile {

        final String key;
        boolean ready = false;

        DownloadingFile(String key) {
            this.key = key;
        }

        void awaitReady() {
            synchronized (this) {
                while (!ready) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        Optional<ArtifactResult> download(Function<RepositoryClient, Optional<ArtifactResult>> clientInvocation,
                RepositoryClient repositoryClient,
                Path downloadTarget,
                Path trackedFile,
                Path downloadTempDir,
                boolean tracked,
                String gav) {
            try {
                Optional<ArtifactResult> result = clientInvocation.apply(repositoryClient);
                if (result.isPresent()) {
                    MessageDigest md = MessageDigest.getInstance("SHA-1");
                    Path tempFile = Files.createTempFile(downloadTempDir, "download", ".part");
                    InputStream in = result.get().getData();
                    try (OutputStream out = Files.newOutputStream(tempFile)) {
                        byte[] buffer = new byte[1024];
                        int r;
                        while ((r = in.read(buffer)) > 0) {
                            out.write(buffer, 0, r);
                            md.update(buffer, 0, r);
                        }
                    } finally {
                        try {
                            in.close();
                        } catch (IOException e) {
                            Log.errorf(e, "Failed to close HTTP stream");
                        }
                    }
                    if (result.get().getExpectedSha().isPresent()) {
                        byte[] digest = md.digest();
                        StringBuilder sb = new StringBuilder(40);
                        for (int i = 0; i < digest.length; ++i) {
                            sb.append(Integer.toHexString((digest[i] & 0xFF) | 0x100).substring(1, 3));
                        }
                        String hash = sb.toString();

                        if (!hash.equalsIgnoreCase(result.get().getExpectedSha().get())) {
                            Log.error("Filed to cache " + downloadTarget + " from " + repositoryClient.getName()
                                    + " calculated sha '" + hash
                                    + "' did not match expected '" + result.get().getExpectedSha().get() + "'");
                            if (tracked) {
                                Path tempTransformedFile = Files.createTempFile(downloadTempDir, "transformed", ".part");
                                try (var inFromFile = Files.newInputStream(tempFile);
                                        var transformedOut = Files.newOutputStream(tempTransformedFile)) {
                                    ClassFileTracker.addTrackingDataToJar(inFromFile,
                                            new TrackingData(gav, repository.getName()), transformedOut);
                                }
                                Files.delete(tempFile);
                                return Optional.of(new ArtifactResult(Files.newInputStream(tempTransformedFile),
                                        Files.size(tempTransformedFile),
                                        Optional.empty(), result.get().getMetadata(), () -> {
                                            try {
                                                Files.delete(tempTransformedFile);
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }
                                        }));

                            } else {
                                return Optional.of(new ArtifactResult(Files.newInputStream(tempFile), Files.size(tempFile),
                                        Optional.empty(), result.get().getMetadata(), () -> {
                                            try {
                                                Files.delete(tempFile);
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }
                                        }));
                            }
                        }
                    }

                    Files.createDirectories(downloadTarget.getParent());
                    Files.move(tempFile, downloadTarget, StandardCopyOption.ATOMIC_MOVE);

                    if (result.get().getExpectedSha().isPresent()) {
                        Files.writeString(downloadTarget.getParent().resolve(downloadTarget.getFileName().toString() + SHA_1),
                                result.get().getExpectedSha().get(), StandardCharsets.UTF_8);
                    }
                    Properties p = new Properties();
                    for (var e : result.get().getMetadata().entrySet()) {
                        p.put(e.getKey().toLowerCase(), e.getValue());
                    }
                    p.remove("content-length"); //use the actual on disk length
                    try (OutputStream out = Files.newOutputStream(
                            downloadTarget.getParent().resolve(downloadTarget.getFileName().toString() + HEADERS))) {
                        p.store(out, "");
                    }
                    return handleDownloadedFile(downloadTarget, trackedFile, tracked, gav);
                }
                return Optional.empty();
            } catch (ClientWebApplicationException e) {
                if (e.getResponse().getStatus() == 404) {
                    return Optional.empty();
                }
                Log.errorf(e, "Failed to download artifact %s from %s", downloadTarget, repositoryClient);
                return Optional.empty();
            } catch (Exception e) {
                Log.errorf(e, "Failed to download artifact %s from %s", downloadTarget, repositoryClient);
                return Optional.empty();
            } finally {
                inProgressDownloads.remove(key);
                synchronized (this) {
                    ready = true;
                    notifyAll();
                }
            }
        }
    }

}
