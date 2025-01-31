/*
 * Copyright ${inceptionYear} - ${year} ${owner}
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.vectoriadb.server;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import jetbrains.vectoriadb.index.DataStore;
import jetbrains.vectoriadb.index.Distance;
import jetbrains.vectoriadb.index.IndexBuilder;
import jetbrains.vectoriadb.index.IndexReader;
import jetbrains.vectoriadb.index.diskcache.DiskCache;
import jetbrains.vectoriadb.service.base.IndexManagerGrpc;
import jetbrains.vectoriadb.service.base.IndexManagerOuterClass;
import net.devh.boot.grpc.server.service.GrpcService;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@GrpcService
public class IndexManagerServiceImpl extends IndexManagerGrpc.IndexManagerImplBase {
    public static final long EIGHT_TB = 8L * 1024 * 1024 * 1024 * 1024;

    public static final String INDEX_DIMENSIONS_PROPERTY = "vectoriadb.index.dimensions";
    public static final String MAX_CONNECTIONS_PER_VERTEX_PROPERTY = "vectoriadb.index.max-connections-per-vertex";
    public static final String MAX_CANDIDATES_RETURNED_PROPERTY = "vectoriadb.index.max-candidates-returned";
    public static final String COMPRESSION_RATIO_PROPERTY = "vectoriadb.index.compression-ratio";
    public static final String DISTANCE_MULTIPLIER_PROPERTY = "vectoriadb.index.distance-multiplier";
    public static final String INDEX_BUILDING_MAX_MEMORY_CONSUMPTION_PROPERTY =
            "vectoriadb.index.building.max-memory-consumption";
    public static final String INDEX_SEARCH_DISK_CACHE_MEMORY_CONSUMPTION =
            "vectoriadb.index.search.disk-cache-memory-consumption";

    public static final String BASE_PATH_PROPERTY = "vectoriadb.server.base-path";
    public static final String DEFAULT_MODE_PROPERTY = "vectoriadb.server.default-mode";

    public static final String BUILD_MODE = "build";

    public static final String SEARCH_MODE = "search";

    private static final Logger logger = LoggerFactory.getLogger(IndexManagerServiceImpl.class);
    public static final String STATUS_FILE_NAME = "status";
    public static final String METADATA_FILE_NAME = "metadata";

    public static final String INDEXES_DIR = "indexes";
    public static final String LOGS_DIR = "logs";
    public static final String CONFIG_DIR = "config";
    public static final String CONFIG_YAML = "vectoriadb.yml";

    private final ConcurrentHashMap<String, IndexState> indexStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IndexMetadata> indexMetadatas = new ConcurrentHashMap<>();

    private final ListenerBasedPeriodicProgressTracker progressTracker = new ListenerBasedPeriodicProgressTracker(5);
    private static final int MAXIMUM_UPLOADERS_COUNT = 64;
    private final Set<String> uploadingIndexes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ReentrantLock uploaderLock = new ReentrantLock();

    private final int dimensions;
    private final int maxConnectionsPerVertex;
    private final int maxCandidatesReturned;
    private final int compressionRatio;
    private final float distanceMultiplier;
    private final long indexBuildingMaxMemoryConsumption;
    private final long diskCacheMemoryConsumption;
    private final Semaphore operationsSemaphore = new Semaphore(Integer.MAX_VALUE);
    private final ReentrantLock modeLock = new ReentrantLock();

    private boolean closed = false;

    private volatile Mode mode;

    private final Path basePath;

    public IndexManagerServiceImpl(Environment environment) throws IOException {
        dimensions = environment.getRequiredProperty(INDEX_DIMENSIONS_PROPERTY, Integer.class);

        maxConnectionsPerVertex = environment.getRequiredProperty(MAX_CONNECTIONS_PER_VERTEX_PROPERTY, Integer.class);
        maxCandidatesReturned = environment.getRequiredProperty(MAX_CANDIDATES_RETURNED_PROPERTY, Integer.class);
        compressionRatio = environment.getRequiredProperty(COMPRESSION_RATIO_PROPERTY, Integer.class);
        distanceMultiplier = environment.getRequiredProperty(DISTANCE_MULTIPLIER_PROPERTY, Float.class);

        basePath = Path.of(environment.getProperty(BASE_PATH_PROPERTY, String.class, "."));

        Files.createDirectories(basePath.resolve(INDEXES_DIR));
        Files.createDirectories(basePath.resolve(LOGS_DIR));

        var configDirPath = basePath.resolve(CONFIG_DIR);
        Files.createDirectories(configDirPath);

        var configPath = configDirPath.resolve(CONFIG_YAML);

        if (!Files.exists(configPath)) {
            logger.info("Server config file {} does not exist. Using default one.", configPath);
            var defaultConfigStream = IndexManagerServiceImpl.class.getResourceAsStream("/" + CONFIG_YAML);
            assert defaultConfigStream != null;

            Files.copy(defaultConfigStream, configPath);
        }

        var availableRAM = fetchAvailableRAM();

        var heapSize = Runtime.getRuntime().maxMemory();
        var availableDirectMemory = availableRAM - heapSize;
        var osMemory = Math.min(512 * 1024 * 1024, availableDirectMemory / 100);

        long maxMemoryConsumption = availableDirectMemory - osMemory;
        logger.info("Available direct memory size : "
                + printMemoryNumbers(maxMemoryConsumption) +
                ", heap size : " + printMemoryNumbers(heapSize) +
                ", available RAM : " + printMemoryNumbers(availableRAM) +
                ", memory booked for OS needs " + printMemoryNumbers(osMemory));

        if (getMemoryProperty(environment, INDEX_BUILDING_MAX_MEMORY_CONSUMPTION_PROPERTY) <= 0) {
            indexBuildingMaxMemoryConsumption = maxMemoryConsumption / 2;

            logger.info("Property " + INDEX_BUILDING_MAX_MEMORY_CONSUMPTION_PROPERTY + " is not set. " +
                    "Using " + printMemoryNumbers(indexBuildingMaxMemoryConsumption) + " for index building. "
                    + printMemoryNumbers(maxMemoryConsumption - indexBuildingMaxMemoryConsumption) +
                    " will be used for disk page cache.");
        } else {
            indexBuildingMaxMemoryConsumption = getMemoryProperty(environment, INDEX_BUILDING_MAX_MEMORY_CONSUMPTION_PROPERTY);
            logger.info("Using " + printMemoryNumbers(indexBuildingMaxMemoryConsumption) + " for index building. " +
                    printMemoryNumbers(maxMemoryConsumption - indexBuildingMaxMemoryConsumption) +
                    " will be used for disk page cache.");
        }

        if (getMemoryProperty(environment, INDEX_SEARCH_DISK_CACHE_MEMORY_CONSUMPTION) < 0) {
            diskCacheMemoryConsumption = 4 * maxMemoryConsumption / 5;

            logger.info("Property " + INDEX_SEARCH_DISK_CACHE_MEMORY_CONSUMPTION + " is not set. " +
                    "Using " + printMemoryNumbers(diskCacheMemoryConsumption) +
                    " for disk page cache. " + printMemoryNumbers(maxMemoryConsumption - diskCacheMemoryConsumption) +
                    " bytes will be used to keep primary index in memory.");
        } else {
            diskCacheMemoryConsumption = getMemoryProperty(environment, INDEX_SEARCH_DISK_CACHE_MEMORY_CONSUMPTION);
            logger.info("Using " + printMemoryNumbers(diskCacheMemoryConsumption) +
                    " for disk page cache. " +
                    printMemoryNumbers(maxMemoryConsumption - diskCacheMemoryConsumption) +
                    " will be used to keep primary index in memory.");
        }

        var modeName = environment.getProperty(DEFAULT_MODE_PROPERTY, String.class, BUILD_MODE).toLowerCase(Locale.ROOT);

        if (modeName.equals(BUILD_MODE)) {
            mode = new BuildMode();
        } else if (modeName.equals(SEARCH_MODE)) {
            mode = new SearchMode();
        } else {
            var msg = "Unknown mode " + modeName;
            logger.error(msg);
            throw new IllegalArgumentException(msg);
        }


        logger.info("Index manager initialized with parameters " +
                        "dimensions = {}, " +
                        "maxConnectionsPerVertex = {}, " +
                        "maxCandidatesReturned = {}, " +
                        "compressionRatio = {}, " +
                        "distanceMultiplier = {}, " +
                        "mode = {}",
                dimensions, maxConnectionsPerVertex, maxCandidatesReturned, compressionRatio,
                distanceMultiplier, modeName);


        findIndexesOnDisk();
    }

    private static String printMemoryNumbers(long bytes) {
        return bytes + "/" + bytesToMb(bytes) + "Mb/" + bytesToGb(bytes) + "Gb";
    }

    private static long getMemoryProperty(Environment environment, String name) {
        var memoryProperty = environment.getProperty(name);
        if (memoryProperty == null) {
            return -1;
        }

        try {
            memoryProperty = memoryProperty.toLowerCase(Locale.ROOT);

            if (memoryProperty.endsWith("g")) {
                return Long.parseLong(memoryProperty.substring(0, memoryProperty.length() - 1)) * 1024 * 1024 * 1024;
            }
            if (memoryProperty.endsWith("gb")) {
                return Long.parseLong(memoryProperty.substring(0, memoryProperty.length() - 2)) * 1024 * 1024 * 1024;
            }
            if (memoryProperty.endsWith("m")) {
                return Long.parseLong(memoryProperty.substring(0, memoryProperty.length() - 1)) * 1024 * 1024;
            }
            if (memoryProperty.endsWith("mb")) {
                return Long.parseLong(memoryProperty.substring(0, memoryProperty.length() - 2)) * 1024 * 1024;
            }
            if (memoryProperty.endsWith("k")) {
                return Long.parseLong(memoryProperty.substring(0, memoryProperty.length() - 1)) * 1024;
            }
            if (memoryProperty.endsWith("kb")) {
                return Long.parseLong(memoryProperty.substring(0, memoryProperty.length() - 2)) * 1024;
            }
            if (memoryProperty.endsWith("b")) {
                return Long.parseLong(memoryProperty.substring(0, memoryProperty.length() - 1));
            }

            return Long.parseLong(memoryProperty);
        } catch (Exception e) {
            logger.error("Error during parsin property " + name, e);
            return -1;
        }
    }

    private static long bytesToMb(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static long bytesToGb(long bytes) {
        return bytes / (1024 * 1024 * 1024);
    }

    private void findIndexesOnDisk() throws IOException {
        var indexesDir = basePath.resolve(INDEXES_DIR);
        logger.info("Scanning existing indexes on disk {}", indexesDir.toAbsolutePath());
        //noinspection resource
        Files.list(indexesDir)
                .filter(Files::isDirectory)
                .forEach(this::loadIndex);
        logger.info("Scanning of existing indexes on disk {} completed", indexesDir.toAbsolutePath());
    }

    private void loadIndex(Path path) {
        var indexName = path.getFileName().toString();
        logger.info("Loading index `{}`", indexName);

        if (indexStates.containsKey(indexName)) {
            logger.warn("Index {} already exists", indexName);
        }

        try {
            var statusFile = path.resolve(STATUS_FILE_NAME);
            if (!Files.exists(statusFile)) {
                logger.error("Status file {} does not exist for index {}", statusFile, indexName);
                return;
            }

            var status = Files.readString(statusFile);
            IndexState indexState;
            try {
                indexState = IndexState.valueOf(status);
            } catch (Exception e) {
                logger.error("Failed to parse index state " + status + " for index " + indexName, e);
                return;
            }

            if (indexState == IndexState.CREATING ||
                    indexState == IndexState.UPLOADING ||
                    indexState == IndexState.BUILDING ||
                    indexState == IndexState.IN_BUILD_QUEUE ||
                    indexState == IndexState.BROKEN) {
                logger.error("Index {} is in invalid state {}. Will not load it", indexName, indexState);
                return;
            }

            var metadataFile = path.resolve(METADATA_FILE_NAME);
            if (!Files.exists(metadataFile)) {
                logger.error("Metadata file {} does not exist for index {}", metadataFile, indexName);
                return;
            }

            var distance = Distance.valueOf(Files.readString(metadataFile));
            indexMetadatas.put(indexName, new IndexMetadata(distance, path));
            indexStates.put(indexName, indexState);

            logger.info("Index {} loaded", indexName);

        } catch (IOException e) {
            logger.error("Failed to load index " + indexName, e);
            throw new RuntimeException(e);
        }
    }


    @Override
    public void createIndex(IndexManagerOuterClass.CreateIndexRequest request,
                            StreamObserver<IndexManagerOuterClass.CreateIndexResponse> responseObserver) {
        operationsSemaphore.acquireUninterruptibly();
        try {
            if (closed) {
                responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
                return;
            }

            mode.createIndex(request, responseObserver);
        } finally {
            operationsSemaphore.release();
        }
    }


    @Override
    public void triggerIndexBuild(IndexManagerOuterClass.IndexNameRequest request, StreamObserver<Empty> responseObserver) {
        operationsSemaphore.acquireUninterruptibly();
        try {
            if (closed) {
                responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
                return;
            }

            mode.buildIndex(request, responseObserver);
        } finally {
            operationsSemaphore.release();
        }
    }

    @Override
    public StreamObserver<IndexManagerOuterClass.UploadVectorsRequest> uploadVectors(
            StreamObserver<Empty> responseObserver) {

        operationsSemaphore.acquireUninterruptibly();
        if (closed) {
            responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
            operationsSemaphore.release();
            return null;
        }

        return mode.uploadVectors(responseObserver);
    }

    @Override
    public void buildStatus(final Empty request,
                            final StreamObserver<IndexManagerOuterClass.BuildStatusResponse> responseObserver) {
        operationsSemaphore.acquireUninterruptibly();
        try {
            if (closed) {
                responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
                return;
            }

            mode.buildStatus(request, responseObserver);
        } finally {
            operationsSemaphore.release();
        }
    }

    @Override
    public void retrieveIndexState(IndexManagerOuterClass.IndexNameRequest request,
                                   StreamObserver<IndexManagerOuterClass.IndexStateResponse> responseObserver) {
        operationsSemaphore.acquireUninterruptibly();
        try {
            if (closed) {
                responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
                return;
            }

            try {
                var indexName = request.getIndexName();
                var state = indexStates.get(indexName);
                if (state == null) {
                    responseObserver.onError(new StatusException(Status.NOT_FOUND));
                } else {
                    responseObserver.onNext(IndexManagerOuterClass.IndexStateResponse.newBuilder()
                            .setState(convertToAPIState(state))
                            .build());
                    responseObserver.onCompleted();
                }
            } catch (StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
            }
        } finally {
            operationsSemaphore.release();
        }
    }

    @Override
    public void listIndexes(Empty request, StreamObserver<IndexManagerOuterClass.IndexListResponse> responseObserver) {
        operationsSemaphore.acquireUninterruptibly();
        try {
            if (closed) {
                responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
                return;
            }

            var responseBuilder = IndexManagerOuterClass.IndexListResponse.newBuilder();
            try {
                for (var entry : indexStates.entrySet()) {
                    if (entry.getValue() != IndexState.BROKEN) {
                        responseBuilder.addIndexNames(entry.getKey());
                    }
                }

                responseObserver.onNext(responseBuilder.build());
                responseObserver.onCompleted();
            } catch (StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                logger.error("Failed to list indexes", e);
                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
            }
        } finally {
            operationsSemaphore.release();
        }

    }

    @Override
    public void switchToBuildMode(Empty request, StreamObserver<Empty> responseObserver) {
        logger.info("Switching to build mode");

        var releasePermits = false;
        modeLock.lock();
        try {
            if (mode instanceof BuildMode) {
                logger.info("Will not switch to build mode, because it is already active");

                responseObserver.onNext(Empty.newBuilder().build());
                responseObserver.onCompleted();
                return;
            }

            if (!operationsSemaphore.tryAcquire(Integer.MAX_VALUE)) {
                logger.error("Failed to switch to build mode because of ongoing operations");
                responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
                return;
            }

            releasePermits = true;
            if (closed) {
                responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
                return;
            }

            mode.shutdown();

            mode = new BuildMode();
            responseObserver.onNext(Empty.newBuilder().build());
            responseObserver.onCompleted();

        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            logger.error("Failed to switch to build mode", e);
            responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
        } finally {
            if (releasePermits) {
                operationsSemaphore.release(Integer.MAX_VALUE);
            }
            modeLock.unlock();
        }

        logger.info("Switched to build mode");
    }

    @Override
    public void switchToSearchMode(Empty request, StreamObserver<Empty> responseObserver) {
        logger.info("Switching to search mode");

        modeLock.lock();
        var releasePermits = false;
        try {
            if (mode instanceof SearchMode) {
                logger.info("Will not switch to search mode, because it is already active");

                responseObserver.onNext(Empty.newBuilder().build());
                responseObserver.onCompleted();
                return;
            }

            if (!operationsSemaphore.tryAcquire(Integer.MAX_VALUE, 5, TimeUnit.SECONDS)) {
                var msg = "Failed to switch to search mode because of ongoing operations";
                logger.error(msg, new RuntimeException());

                responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE.withDescription(msg)));
                return;
            }
            releasePermits = true;

            if (closed) {
                responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
                return;
            }

            mode.shutdown();

            mode = new SearchMode();
            responseObserver.onNext(Empty.newBuilder().build());
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            logger.error("Failed to switch to search mode", e);
            responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
        } finally {
            if (releasePermits) {
                operationsSemaphore.release(Integer.MAX_VALUE);
            }
            modeLock.unlock();
        }

        logger.info("Switched to search mode");
    }

    @Override
    public void findNearestNeighbours(IndexManagerOuterClass.FindNearestNeighboursRequest request,
                                      StreamObserver<IndexManagerOuterClass.FindNearestNeighboursResponse> responseObserver) {
        operationsSemaphore.acquireUninterruptibly();
        try {
            if (closed) {
                responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
                return;
            }

            mode.findNearestNeighbours(request, responseObserver);
        } finally {
            operationsSemaphore.release();
        }
    }

    @Override
    public void dropIndex(IndexManagerOuterClass.IndexNameRequest request, StreamObserver<Empty> responseObserver) {
        operationsSemaphore.acquireUninterruptibly();
        try {
            if (closed) {
                responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE));
                return;
            }

            mode.dropIndex(request, responseObserver);
        } finally {
            operationsSemaphore.release();
        }
    }

    private IndexManagerOuterClass.IndexState convertToAPIState(IndexState indexState) {
        return switch (indexState) {
            case BROKEN -> IndexManagerOuterClass.IndexState.BROKEN;
            case BUILDING -> IndexManagerOuterClass.IndexState.BUILDING;
            case BUILT -> IndexManagerOuterClass.IndexState.BUILT;
            case CREATED -> IndexManagerOuterClass.IndexState.CREATED;
            case CREATING -> IndexManagerOuterClass.IndexState.CREATING;
            case IN_BUILD_QUEUE -> IndexManagerOuterClass.IndexState.IN_BUILD_QUEUE;
            case UPLOADING -> IndexManagerOuterClass.IndexState.UPLOADING;
            case UPLOADED -> IndexManagerOuterClass.IndexState.UPLOADED;
        };
    }

    public static long fetchAvailableRAM() {
        var result = Long.MAX_VALUE;
        var osName = System.getProperty("os.name").toLowerCase(Locale.US);

        if (osName.contains("linux")) {
            result = availableMemoryLinux();
        } else if (osName.contains("windows")) {
            result = availableMemoryWindows();
        }

        if (result >= EIGHT_TB) {
            var msg = "Unable to detect amount of RAM available on server";
            logger.error(msg);
            throw new IllegalArgumentException(msg);
        }

        return result;
    }

    private static long availableMemoryWindows() {
        try (var arena = Arena.ofShared()) {
            var memoryStatusExLayout = MemoryLayout.structLayout(
                    ValueLayout.JAVA_INT.withName("dwLength"),
                    ValueLayout.JAVA_INT.withName("dwMemoryLoad"),
                    ValueLayout.JAVA_LONG.withName("ullTotalPhys"),
                    ValueLayout.JAVA_LONG.withName("ullAvailPhys"),
                    ValueLayout.JAVA_LONG.withName("ullTotalPageFile"),
                    ValueLayout.JAVA_LONG.withName("ullAvailPageFile"),
                    ValueLayout.JAVA_LONG.withName("ullTotalVirtual"),
                    ValueLayout.JAVA_LONG.withName("ullAvailVirtual"),
                    ValueLayout.JAVA_LONG.withName("ullAvailExtendedVirtual")
            );

            var memoryStatusExSize = memoryStatusExLayout.byteSize();
            var memoryStatusExSegment = arena.allocate(memoryStatusExLayout);

            memoryStatusExSegment.set(ValueLayout.JAVA_LONG, memoryStatusExLayout.byteOffset(
                    MemoryLayout.PathElement.groupElement("dwLength")), memoryStatusExSize);

            var linker = Linker.nativeLinker();

            var lookup = SymbolLookup.libraryLookup("kernel32.dll", arena);
            var globalMemoryStatusExOptional = lookup.find("GlobalMemoryStatusEx");
            if (globalMemoryStatusExOptional.isEmpty()) {
                logger.error("Failed to find GlobalMemoryStatusEx in kernel32.dll");
                return Integer.MAX_VALUE;
            }

            var globalMemoryStatusEx = globalMemoryStatusExOptional.get();
            var globalMemoryStatusExHandle = linker.downcallHandle(globalMemoryStatusEx,
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
            try {
                globalMemoryStatusExHandle.invoke(memoryStatusExSegment);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }


            return memoryStatusExSegment.get(ValueLayout.JAVA_LONG,
                    memoryStatusExLayout.byteOffset(MemoryLayout.PathElement.groupElement("ullTotalPhys")));
        }
    }

    public static long availableMemoryLinux() {
        var memInfoMemory = fetchMemInfoMemory();
        var cGroupV1Memory = fetchCGroupV1Memory();
        var cGroupV2Memory = fetchCGroupV2Memory();

        return Math.min(memInfoMemory, Math.min(cGroupV1Memory, cGroupV2Memory));
    }

    private static long fetchMemInfoMemory() {
        try (var bufferedReader = new BufferedReader(new FileReader("/proc/meminfo"))) {

            String memTotalLine = bufferedReader.readLine();

            String[] memTotalParts = memTotalLine.split("\\s+");
            return Long.parseLong(memTotalParts[1]) * 1024;
        } catch (NumberFormatException | IOException e) {
            logger.error("Failed to read /proc/meminfo", e);
            return Integer.MAX_VALUE;
        }
    }

    private static long fetchCGroupV1Memory() {
        if (!Files.exists(Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"))) {
            return Long.MAX_VALUE;
        }

        try (var bufferedReader = new BufferedReader(new FileReader("/sys/fs/cgroup/memory/memory.limit_in_bytes"))) {
            var memoryLimitLine = bufferedReader.readLine();

            var memoryLimitPeace = memoryLimitLine.split("\\s+")[0];
            if (memoryLimitPeace.equals("max")) {
                return Long.MAX_VALUE;
            }

            return Long.parseLong(memoryLimitPeace);
        } catch (IOException | NumberFormatException e) {
            logger.error("Failed to read /sys/fs/cgroup/memory/memory.limit_in_bytes", e);
            return Integer.MAX_VALUE;
        }
    }

    private static long fetchCGroupV2Memory() {
        if (!Files.exists(Path.of("/sys/fs/cgroup/memory.max"))) {
            return Long.MAX_VALUE;
        }

        try (var bufferedReader = new BufferedReader(new FileReader("/sys/fs/cgroup/memory.max"))) {
            var memoryLimitLine = bufferedReader.readLine();

            var memoryLimitPeace = memoryLimitLine.split("\\s+")[0];
            if (memoryLimitPeace.equals("max")) {
                return Long.MAX_VALUE;
            }

            return Long.parseLong(memoryLimitPeace);
        } catch (IOException | NumberFormatException e) {
            logger.error("Failed to read /sys/fs/cgroup/memory.max", e);
            return Integer.MAX_VALUE;
        }
    }


    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down index manager");
        while (true) {
            try {
                var acquired = operationsSemaphore.tryAcquire(Integer.MAX_VALUE, 5, TimeUnit.SECONDS);
                if (!acquired) {
                    logger.warn("Failed to acquire semaphore to shutdown index manager because of running operations." +
                            " Will retry in 5 seconds");
                    continue;
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            try {
                if (closed) {
                    return;
                }

                closed = true;

                mode.shutdown();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                operationsSemaphore.release(Integer.MAX_VALUE);
            }
            break;
        }
        logger.info("Shutdown of index manager completed");
    }

    private final class IndexBuilderTask implements Runnable {
        private final String indexName;

        public IndexBuilderTask(String indexName) {
            this.indexName = indexName;
        }

        @Override
        public void run() {
            operationsSemaphore.acquireUninterruptibly();
            try {
                if (closed) {
                    return;
                }

                try {
                    var metadata = indexMetadatas.get(indexName);
                    if (indexStates.replace(indexName, IndexState.IN_BUILD_QUEUE, IndexState.BUILDING)) {
                        updateIndexStatusInFS(metadata.dir, IndexState.BUILDING);
                        try {
                            IndexBuilder.buildIndex(indexName, dimensions, compressionRatio,
                                    distanceMultiplier, metadata.dir,
                                    DataStore.dataLocation(indexName, metadata.dir),
                                    indexBuildingMaxMemoryConsumption, maxConnectionsPerVertex,
                                    maxCandidatesReturned,
                                    metadata.distance, progressTracker);
                        } catch (Exception e) {
                            logger.error("Failed to build index " + indexName, e);
                            indexStates.put(indexName, IndexState.BROKEN);
                            updateIndexStatusInFS(metadata.dir, IndexState.BROKEN);
                            return;
                        }

                        indexStates.put(indexName, IndexState.BUILT);
                        updateIndexStatusInFS(metadata.dir, IndexState.BUILT);
                    } else {
                        logger.warn("Failed to build index " + indexName + " because it is not in IN_BUILD_QUEUE state");
                    }
                } catch (IOException e) {
                    logger.error("Index builder task failed", e);
                    throw new RuntimeException(e);
                } catch (Throwable t) {
                    logger.error("Index builder task failed", t);
                    throw t;
                }
            } finally {
                operationsSemaphore.release();
            }
        }
    }

    private record IndexMetadata(Distance distance, Path dir) {
    }

    private interface Mode {
        void createIndex(IndexManagerOuterClass.CreateIndexRequest request,
                         StreamObserver<IndexManagerOuterClass.CreateIndexResponse> responseObserver);

        void buildIndex(IndexManagerOuterClass.IndexNameRequest request, StreamObserver<Empty> responseObserver);

        StreamObserver<IndexManagerOuterClass.UploadVectorsRequest> uploadVectors(
                StreamObserver<Empty> responseObserver);

        void buildStatus(final Empty request,
                         final StreamObserver<IndexManagerOuterClass.BuildStatusResponse> responseObserver);

        void findNearestNeighbours(IndexManagerOuterClass.FindNearestNeighboursRequest request,
                                   StreamObserver<IndexManagerOuterClass.FindNearestNeighboursResponse> responseObserver);

        void dropIndex(IndexManagerOuterClass.IndexNameRequest request, StreamObserver<Empty> responseObserver);

        void shutdown() throws IOException;
    }

    private final class SearchMode implements Mode {
        private final DiskCache diskCache;

        private final ConcurrentHashMap<String, IndexReader> indexReaders = new ConcurrentHashMap<>();

        private SearchMode() {
            diskCache = new DiskCache(diskCacheMemoryConsumption, dimensions, maxConnectionsPerVertex);
        }

        @Override
        public void createIndex(IndexManagerOuterClass.CreateIndexRequest request,
                                StreamObserver<IndexManagerOuterClass.CreateIndexResponse> responseObserver) {
            searchOnly(responseObserver);
        }

        @Override
        public void buildIndex(IndexManagerOuterClass.IndexNameRequest request, StreamObserver<Empty> responseObserver) {
            searchOnly(responseObserver);
        }

        @Override
        public StreamObserver<IndexManagerOuterClass.UploadVectorsRequest> uploadVectors(StreamObserver<Empty> responseObserver) {
            searchOnly(responseObserver);
            return null;
        }

        @Override
        public void buildStatus(Empty request, StreamObserver<IndexManagerOuterClass.BuildStatusResponse> responseObserver) {
            searchOnly(responseObserver);
        }

        @Override
        public void findNearestNeighbours(IndexManagerOuterClass.FindNearestNeighboursRequest request,
                                          StreamObserver<IndexManagerOuterClass.FindNearestNeighboursResponse> responseObserver) {
            var indexName = request.getIndexName();
            if (checkBuildState(responseObserver, indexName)) {
                return;
            }

            var responseBuilder = IndexManagerOuterClass.FindNearestNeighboursResponse.newBuilder();
            try {
                @SuppressWarnings("resource") var indexReader = fetchIndexReader(indexName);

                var neighboursCount = request.getK();
                var queryVector = request.getVectorComponentsList();

                var vector = new float[dimensions];
                for (int i = 0; i < dimensions; i++) {
                    vector[i] = queryVector.get(i);
                }

                var ids = indexReader.nearest(vector, neighboursCount);


                for (var id : ids) {
                    var vectorId = IndexManagerOuterClass.VectorId.newBuilder();
                    vectorId.setId(ByteString.copyFrom(id));

                    responseBuilder.addIds(vectorId);
                }
            } catch (Exception e) {
                logger.error("Failed to find nearest neighbours", e);
                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));

                return;
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        }

        @Override
        public void dropIndex(IndexManagerOuterClass.IndexNameRequest request, StreamObserver<Empty> responseObserver) {
            var indexName = request.getIndexName();
            if (checkBuildState(responseObserver, indexName)) {
                return;
            }

            try {
                @SuppressWarnings("resource") var indexReader = fetchIndexReader(indexName);
                indexReader.deleteIndex();

                //noinspection resource
                indexReaders.remove(indexName);
                indexStates.remove(indexName);
                indexMetadatas.remove(indexName);

                responseObserver.onNext(Empty.newBuilder().build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.error("Failed dropping an index '" + indexName + "'", e);
                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
            }
        }

        @NotNull
        private IndexReader fetchIndexReader(final String indexName) {
            return indexReaders.computeIfAbsent(indexName, r -> {
                var metadata = indexMetadatas.get(indexName);
                return new IndexReader(indexName, dimensions, maxConnectionsPerVertex, maxCandidatesReturned,
                        compressionRatio, metadata.dir, metadata.distance, diskCache);

            });
        }

        private boolean checkBuildState(final StreamObserver<?> responseObserver, final String indexName) {
            var indexState = indexStates.get(indexName);

            if (indexState != IndexState.BUILT) {
                var msg = "Index " + indexName + " is not in BUILT state";
                logger.error(msg, new RuntimeException());

                responseObserver.onError(new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription(msg)));
                return true;
            }

            return false;
        }

        @Override
        public void shutdown() throws IOException {
            for (var indexReader : indexReaders.values()) {
                indexReader.close();
            }

            diskCache.close();
        }

        private void searchOnly(StreamObserver<?> responseObserver) {
            responseObserver.onError(new StatusRuntimeException(
                    Status.PERMISSION_DENIED.withDescription("Index manager is in search mode")));
        }
    }

    private final class BuildMode implements Mode {
        private final ExecutorService indexBuilderExecutor;
        private final ReentrantLock indexCreationLock = new ReentrantLock();

        private BuildMode() {
            indexBuilderExecutor = Executors.newFixedThreadPool(1, r -> {
                var thread = new Thread(r, "Index builder");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        public void createIndex(IndexManagerOuterClass.CreateIndexRequest request,
                                StreamObserver<IndexManagerOuterClass.CreateIndexResponse> responseObserver) {
            indexCreationLock.lock();
            try {
                var indexName = request.getIndexName();
                var indexState = indexStates.putIfAbsent(indexName, IndexState.CREATING);

                if (indexState != null) {
                    var msg = "Index " + indexName + " already exists";
                    logger.error(msg, new RuntimeException());

                    responseObserver.onError(new StatusRuntimeException(Status.ALREADY_EXISTS.withDescription(msg)));
                    return;
                }

                var responseBuilder = IndexManagerOuterClass.CreateIndexResponse.newBuilder();
                try {
                    var indexDir = basePath.resolve(INDEXES_DIR).resolve(indexName);
                    Files.createDirectories(indexDir);

                    updateIndexStatusInFS(indexDir, IndexState.CREATING);
                    var distance = request.getDistance().name();
                    indexMetadatas.put(indexName,
                            new IndexMetadata(Distance.valueOf(distance), indexDir));

                    if (!indexStates.replace(indexName, IndexState.CREATING, IndexState.CREATED)) {
                        var msg = "Failed to create index " + indexName;
                        logger.error(msg, new RuntimeException());
                        responseObserver.onError(new IllegalStateException(msg));

                        indexStates.put(indexName, IndexState.BROKEN);
                        updateIndexStatusInFS(indexDir, IndexState.BROKEN);
                        return;
                    }

                    Files.writeString(indexDir.resolve(METADATA_FILE_NAME), distance, StandardOpenOption.SYNC,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    updateIndexStatusInFS(indexDir, IndexState.CREATED);

                    responseObserver.onNext(responseBuilder.build());
                    responseObserver.onCompleted();

                    logger.info("Index {} created", indexName);
                } catch (Exception e) {
                    indexMetadatas.remove(indexName);
                    logger.error("Failed to create index " + indexName, e);
                    responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
                }
            } catch (StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                logger.error("Failed to create index", e);
                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
            } finally {
                indexCreationLock.unlock();
            }
        }

        @Override
        public void buildIndex(IndexManagerOuterClass.IndexNameRequest request, StreamObserver<Empty> responseObserver) {
            try {
                var indexName = request.getIndexName();
                var indexState = indexStates.compute(indexName, (k, state) -> {
                    if (state == IndexState.UPLOADED || state == IndexState.CREATED) {
                        return IndexState.IN_BUILD_QUEUE;
                    } else {
                        return state;
                    }
                });

                if (indexState == null) {
                    var msg = "Index " + indexName + " does not exist";
                    logger.error(msg, new RuntimeException());

                    responseObserver.onError(new StatusRuntimeException(Status.NOT_FOUND.withDescription(msg)));
                    return;
                }

                if (indexState != IndexState.IN_BUILD_QUEUE) {
                    var msg = "Index " + indexName + " is not in UPLOADED or CREATED state : " + indexState;
                    logger.error(msg, new RuntimeException());

                    responseObserver.onError(new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription(msg)));
                    return;
                }

                updateIndexStatusInFS(indexMetadatas.get(indexName).dir, IndexState.IN_BUILD_QUEUE);
                indexBuilderExecutor.execute(new IndexBuilderTask(indexName));

                responseObserver.onNext(Empty.newBuilder().build());
                responseObserver.onCompleted();
            } catch (StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
            }
        }

        @Override
        public StreamObserver<IndexManagerOuterClass.UploadVectorsRequest> uploadVectors(StreamObserver<Empty> responseObserver) {
            return new StreamObserver<>() {
                private DataStore store;
                private String indexName;

                private final Lock streamObserverLock = new ReentrantLock();

                @Override
                public void onNext(IndexManagerOuterClass.UploadVectorsRequest value) {
                    streamObserverLock.lock();
                    try {
                        var indexName = value.getIndexName();
                        if (this.indexName == null) {
                            if (!indexStates.replace(indexName, IndexState.CREATED, IndexState.UPLOADING)) {
                                var msg = "Index " + indexName + " is not in CREATED state";
                                logger.error(msg, new RuntimeException());

                                responseObserver.onError(
                                        new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription(msg)));
                                return;
                            }

                            try {
                                updateIndexStatusInFS(indexMetadatas.get(indexName).dir, IndexState.UPLOADING);
                            } catch (IOException e) {
                                logger.error("Failed to update index status in FS", e);
                                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
                                return;
                            }

                            uploaderLock.lock();
                            try {
                                if (!uploadingIndexes.contains(indexName)) {
                                    if (uploadingIndexes.size() == MAXIMUM_UPLOADERS_COUNT) {
                                        indexStates.put(indexName, IndexState.CREATED);

                                        try {
                                            updateIndexStatusInFS(indexMetadatas.get(indexName).dir, IndexState.CREATED);
                                        } catch (IOException e) {
                                            logger.error("Failed to update index status in FS", e);
                                            responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
                                            return;
                                        }

                                        responseObserver.onError(new StatusRuntimeException(
                                                Status.RESOURCE_EXHAUSTED.withDescription("Maximum uploaders count reached")));
                                        return;
                                    }

                                    uploadingIndexes.add(indexName);
                                }
                            } finally {
                                uploaderLock.unlock();
                            }

                            var metadata = indexMetadatas.get(indexName);

                            try {
                                store = DataStore.create(indexName, dimensions, metadata.distance.buildDistanceFunction(),
                                        metadata.dir);
                            } catch (IOException e) {
                                var msg = "Failed to create data store for index " + indexName;
                                logger.error(msg, e);

                                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));

                                indexStates.put(indexName, IndexState.BROKEN);
                                try {
                                    updateIndexStatusInFS(indexMetadatas.get(indexName).dir, IndexState.UPLOADING);
                                } catch (IOException ioe) {
                                    logger.error("Failed to update index status in FS", ioe);
                                    responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
                                    return;
                                }
                            }

                            this.indexName = indexName;
                        } else {
                            var indexState = indexStates.get(indexName);
                            if (indexState != IndexState.UPLOADING) {
                                var msg = "Index " + indexName + " is not in UPLOADING state";
                                logger.error(msg, new RuntimeException());

                                responseObserver.onError(
                                        new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription(msg)));
                                return;
                            }

                            if (!indexName.equals(this.indexName)) {
                                var msg = "Index name mismatch: expected " + this.indexName + ", got " + indexName;
                                logger.error(msg, new RuntimeException());
                                responseObserver.onError(
                                        new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription(msg)));
                            }
                        }

                        var componentsCount = value.getVectorComponentsCount();
                        var indexMetadata = IndexManagerServiceImpl.this.indexMetadatas.get(indexName);
                        if (indexMetadata == null) {
                            var msg = "Index " + indexName + " does not exist";
                            logger.error(msg, new RuntimeException());

                            responseObserver.onError(new StatusRuntimeException(Status.NOT_FOUND.withDescription(msg)));
                            return;
                        }

                        if (componentsCount != dimensions) {
                            var msg = "Index " + indexName + " has " + dimensions + " dimensions, but " +
                                    componentsCount + " were provided";
                            logger.error(msg, new RuntimeException());

                            responseObserver.onError(new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription(msg)));
                            return;
                        }


                        var vector = new float[componentsCount];
                        for (var i = 0; i < componentsCount; i++) {
                            vector[i] = value.getVectorComponents(i);
                        }
                        try {
                            store.add(vector, value.getId().getId().toByteArray());
                        } catch (Exception e) {
                            var msg = "Failed to add vector to index " + indexName;
                            logger.error(msg, e);

                            responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
                            indexStates.put(indexName, IndexState.BROKEN);

                            try {
                                updateIndexStatusInFS(indexMetadatas.get(indexName).dir, IndexState.BROKEN);
                            } catch (IOException ioe) {
                                logger.error("Failed to update index status in FS", ioe);
                                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
                            }
                        }
                    } finally {
                        streamObserverLock.unlock();
                    }

                }

                @Override
                public void onError(Throwable t) {
                    streamObserverLock.lock();
                    try {
                        var indexName = this.indexName;

                        if (indexName != null) {
                            indexStates.put(indexName, IndexState.BROKEN);

                            try {
                                updateIndexStatusInFS(indexMetadatas.get(indexName).dir, IndexState.BROKEN);
                            } catch (IOException ioe) {
                                logger.error("Failed to update index status in FS", ioe);
                                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(ioe)));
                            }

                            uploadingIndexes.remove(indexName);
                        }
                        logger.error("Failed to upload vectors for index " + indexName, t);
                        try {
                            if (store != null) {
                                store.close();
                            }
                        } catch (IOException e) {
                            logger.error("Failed to close data store for index " + indexName, e);
                        }

                        responseObserver.onError(t);
                    } finally {
                        operationsSemaphore.release();
                        streamObserverLock.unlock();
                    }
                }

                @Override
                public void onCompleted() {
                    streamObserverLock.lock();
                    try {
                        try {
                            if (store != null) {
                                store.close();
                            }

                            uploadingIndexes.remove(indexName);
                            indexStates.put(indexName, IndexState.UPLOADED);
                            try {
                                updateIndexStatusInFS(indexMetadatas.get(indexName).dir, IndexState.UPLOADED);
                            } catch (IOException ioe) {
                                logger.error("Failed to update index status in FS", ioe);
                                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(ioe)));
                            }
                        } catch (IOException e) {
                            var msg = "Failed to close data store for index " + indexName;
                            logger.error(msg, e);
                            responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
                        }

                        responseObserver.onNext(Empty.newBuilder().build());
                        responseObserver.onCompleted();
                    } finally {
                        operationsSemaphore.release();
                        streamObserverLock.unlock();
                    }
                }
            };
        }

        @Override
        public void buildStatus(Empty request,
                                StreamObserver<IndexManagerOuterClass.BuildStatusResponse> responseObserver) {
            var buildListener = new ServiceIndexBuildProgressListener(responseObserver);
            progressTracker.addListener(buildListener);
        }

        @Override
        public void findNearestNeighbours(IndexManagerOuterClass.FindNearestNeighboursRequest request,
                                          StreamObserver<IndexManagerOuterClass.FindNearestNeighboursResponse> responseObserver) {
            responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE.augmentDescription(
                    "Index manager is in build mode. Please switch to search mode.")));
        }

        @Override
        public void dropIndex(IndexManagerOuterClass.IndexNameRequest request, StreamObserver<Empty> responseObserver) {
            indexCreationLock.lock();
            try {
                var indexName = request.getIndexName();

                indexStates.compute(indexName, (k, state) -> {
                    if (state == IndexState.CREATED || state == IndexState.BUILT || state == IndexState.UPLOADED) {
                        return IndexState.BROKEN;
                    } else {
                        return state;
                    }
                });

                var state = indexStates.get(indexName);
                if (state != IndexState.BROKEN) {
                    var msg = "Index " + request.getIndexName() + " is not in CREATED or BUILT state";
                    logger.error(msg, new RuntimeException());

                    responseObserver.onError(new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription(msg)));
                    return;
                }

                var indexDir = indexMetadatas.get(indexName).dir;
                FileUtils.deleteDirectory(indexDir.toFile());

                indexMetadatas.remove(indexName);
                indexStates.remove(indexName);
                responseObserver.onNext(Empty.newBuilder().build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                indexStates.put(request.getIndexName(), IndexState.BROKEN);
                logger.error("Failed to drop index " + request.getIndexName(), e);
                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
            } finally {
                indexCreationLock.unlock();
            }
        }

        @Override
        public void shutdown() {
            indexBuilderExecutor.shutdown();
        }
    }

    private static void updateIndexStatusInFS(Path indexDir, IndexState state) throws IOException {
        var statusFilePath = indexDir.resolve(STATUS_FILE_NAME);
        if (state == IndexState.CREATING) {
            if (Files.exists(statusFilePath)) {
                throw new RuntimeException("Index already exist on disk in path " + indexDir);
            }
        }

        var tmpFile = Files.createTempFile(indexDir, "status", ".tmp");
        Files.writeString(tmpFile, state.name(), StandardOpenOption.SYNC, StandardOpenOption.WRITE);

        try {
            Files.move(tmpFile, statusFilePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmpFile, statusFilePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private class ServiceIndexBuildProgressListener implements IndexBuildProgressListener {
        private final Context context;
        private final StreamObserver<IndexManagerOuterClass.BuildStatusResponse> responseObserver;

        public ServiceIndexBuildProgressListener(StreamObserver<IndexManagerOuterClass.BuildStatusResponse> responseObserver) {
            this.responseObserver = responseObserver;
            context = Context.current();
        }

        @Override
        public void progress(IndexBuildProgressInfo progressInfo) {
            if (context.isCancelled()) {
                try {
                    responseObserver.onCompleted();
                } catch (Exception e) {
                    progressTracker.removeListener(this);
                    responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
                }
            }

            try {
                var responseBuilder = IndexManagerOuterClass.BuildStatusResponse.newBuilder();
                responseBuilder.setIndexName(progressInfo.indexName());

                for (var phase : progressInfo.phases()) {
                    var name = phase.name();
                    var progress = phase.progress();
                    var parameters = phase.parameters();

                    var phaseBuilder = IndexManagerOuterClass.BuildPhase.newBuilder();

                    phaseBuilder.setName(name);
                    phaseBuilder.setCompletionPercentage(progress);
                    for (var parameter : parameters) {
                        phaseBuilder.addParameters(parameter);
                    }

                    responseBuilder.addPhases(phaseBuilder);
                }


                responseObserver.onNext(responseBuilder.build());
            } catch (StatusRuntimeException e) {
                if (e.getStatus() == Status.CANCELLED) {
                    progressTracker.removeListener(this);
                    responseObserver.onCompleted();
                } else {
                    logger.error("Failed to send build status", e);
                    progressTracker.removeListener(this);
                    responseObserver.onError(e);
                }
            } catch (Exception e) {
                logger.error("Failed to send build status", e);
                progressTracker.removeListener(this);
                responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withCause(e)));
            }
        }
    }
}
