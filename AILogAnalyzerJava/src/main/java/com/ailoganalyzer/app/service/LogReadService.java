package com.ailoganalyzer.app.service;

import com.ailoganalyzer.app.config.AnalyzerSettings;
import com.ailoganalyzer.app.model.FileMeta;
import com.ailoganalyzer.app.model.LogPayload;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobClientBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.ReadChannel;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

@Service
public class LogReadService {
  private static final List<String> LOCAL_ROTATED_FALLBACK_PREFIXES = List.of("systemout", "trace");
  private static final Pattern S3_PATH = Pattern.compile("^s3://([^/]+)/?(.*)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern GS_PATH = Pattern.compile("^gs://([^/]+)/?(.*)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern AZURE_EXPLICIT_PATH =
      Pattern.compile("^(?:az|azure)://([^/]+)/([^/]+)/?(.*)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern AZURE_CONTAINER_PATH =
      Pattern.compile("^(?:az|azure)://([^/]+)/?(.*)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern AZURE_HTTPS_URL =
      Pattern.compile(
          "^https?://([^.]+)\\.blob\\.core\\.windows\\.net/([^/?#]+)/?([^?#]*)(?:\\?([^#]+))?$",
          Pattern.CASE_INSENSITIVE);

  private final AnalyzerSettings settings;

  public LogReadService(AnalyzerSettings settings) {
    this.settings = settings;
  }

  public LogPayload readLogs(String inputPath) {
    String normalized = PathHistoryService.normalizeInputPath(inputPath);
    if (!hasText(normalized)) {
      throw new BadRequestException("Missing query param: path");
    }

    CloudRef cloudRef = parseCloudPath(normalized);
    if (cloudRef != null) {
      return buildCloudLogPayload(cloudRef);
    }

    Path resolvedPath;
    try {
      resolvedPath = Paths.get(normalized).toAbsolutePath().normalize();
    } catch (InvalidPathException ex) {
      throw new BadRequestException("Invalid path: " + ex.getInput());
    }

    boolean allowed =
        settings.isAllowAnyPath()
            || settings.getAllowedRoots().stream().anyMatch(root -> isWithinRoot(resolvedPath, root));
    if (!allowed) {
      throw new PathNotAllowedException(
          "Path not allowed. Add parent directory to LOG_ALLOWED_ROOTS or set LOG_ALLOW_ANY_PATH=true.");
    }

    return buildLocalLogPayload(resolvedPath);
  }

  public String readRawLogs(String inputPath) {
    return readLogs(inputPath).getContent();
  }

  private LogPayload buildLocalLogPayload(Path resolvedPath) {
    try {
      if (Files.isDirectory(resolvedPath)) {
        List<Path> directoryLogs = readDirectoryLogs(resolvedPath, settings.getMaxFiles());
        List<FileChunk> fileChunks = new ArrayList<>();
        for (Path file : directoryLogs) {
          TailResult tail = readFileTailUtf8(file, settings.getMaxBytesPerFile());
          fileChunks.add(new FileChunk(file.toString(), tail.content, tail.totalBytes, tail.truncated));
        }
        return buildPayloadFromFileChunks(resolvedPath.toString(), "directory", fileChunks);
      }

      if (Files.isRegularFile(resolvedPath)) {
        TailResult tail = readFileTailUtf8(resolvedPath, settings.getMaxBytesPerFile());
        List<String> files = List.of(resolvedPath.toString());
        List<FileMeta> meta = List.of(new FileMeta(resolvedPath.toString(), tail.totalBytes, tail.truncated));
        return new LogPayload(resolvedPath.toString(), "file", files, tail.content, meta);
      }

      LogPayload fallbackPayload = buildMissingFilePrefixFallbackPayload(resolvedPath);
      if (fallbackPayload != null) {
        return fallbackPayload;
      }

      throw new IllegalStateException("Path must be a file or directory.");
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read logs: " + exception.getMessage(), exception);
    }
  }

  private List<Path> readDirectoryLogs(Path directoryPath, int maxFiles) throws IOException {
    List<Path> preferredLogs =
        readDirectoryLogsByPrefixes(directoryPath, maxFiles, LOCAL_ROTATED_FALLBACK_PREFIXES);
    if (!preferredLogs.isEmpty()) {
      return preferredLogs;
    }

    try (Stream<Path> stream = Files.list(directoryPath)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(path -> isLogLikeFile(path.getFileName().toString()))
          .sorted(Comparator.comparingLong(this::safeLastModified).reversed())
          .limit(Math.max(1, maxFiles))
          .collect(Collectors.toList());
    }
  }

  private LogPayload buildMissingFilePrefixFallbackPayload(Path requestedPath) throws IOException {
    Path fileNamePath = requestedPath.getFileName();
    Path parent = requestedPath.getParent();
    if (fileNamePath == null || parent == null || !Files.isDirectory(parent)) {
      return null;
    }

    String requestedName = fileNamePath.toString();
    if (!matchesAnyPrefix(requestedName, LOCAL_ROTATED_FALLBACK_PREFIXES)) {
      return null;
    }

    List<Path> fallbackLogs = readDirectoryLogsByPrefixes(parent, settings.getMaxFiles(), LOCAL_ROTATED_FALLBACK_PREFIXES);
    if (fallbackLogs.isEmpty()) {
      return null;
    }

    List<FileChunk> fileChunks = new ArrayList<>();
    for (Path file : fallbackLogs) {
      TailResult tail = readFileTailUtf8(file, settings.getMaxBytesPerFile());
      fileChunks.add(new FileChunk(file.toString(), tail.content, tail.totalBytes, tail.truncated));
    }
    return buildPayloadFromFileChunks(parent.toString(), "directory", fileChunks);
  }

  private List<Path> readDirectoryLogsByPrefixes(Path directoryPath, int maxFiles, List<String> prefixes)
      throws IOException {
    try (Stream<Path> stream = Files.list(directoryPath)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(path -> isLogLikeFile(path.getFileName().toString()))
          .filter(path -> matchesAnyPrefix(path.getFileName().toString(), prefixes))
          .sorted(Comparator.comparingLong(this::safeLastModified).reversed())
          .limit(Math.max(1, maxFiles))
          .collect(Collectors.toList());
    }
  }

  private boolean matchesAnyPrefix(String fileName, List<String> prefixes) {
    if (fileName == null) {
      return false;
    }
    String normalized = fileName.toLowerCase(Locale.ROOT);
    for (String prefix : prefixes) {
      if (normalized.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private long safeLastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException ignored) {
      return 0L;
    }
  }

  private TailResult readFileTailUtf8(Path filePath, int maxBytes) throws IOException {
    long totalBytes = Files.size(filePath);
    int safeMaxBytes = Math.max(1, maxBytes);
    int bytesToRead = (int) Math.min(totalBytes, safeMaxBytes);
    long offset = Math.max(0, totalBytes - bytesToRead);

    if (bytesToRead <= 0) {
      return new TailResult("", totalBytes, false);
    }

    ByteBuffer buffer = ByteBuffer.allocate(bytesToRead);
    try (SeekableByteChannel channel = Files.newByteChannel(filePath, StandardOpenOption.READ)) {
      channel.position(offset);
      while (buffer.hasRemaining()) {
        int read = channel.read(buffer);
        if (read < 0) {
          break;
        }
      }
    }

    byte[] contentBytes = buffer.array();
    return buildTailContent(contentBytes, totalBytes, safeMaxBytes);
  }

  private TailResult buildTailContent(byte[] bytes, long totalBytes, int maxBytes) {
    long safeTotal = Math.max(0L, totalBytes);
    int safeMax = Math.max(1, maxBytes);
    boolean truncated = safeTotal > safeMax;
    String prefix =
        truncated
            ? "[truncated] showing last " + Math.min(safeTotal, safeMax) + " bytes of " + safeTotal + " bytes\n"
            : "";
    return new TailResult(prefix + new String(bytes, StandardCharsets.UTF_8), safeTotal, truncated);
  }

  private LogPayload buildPayloadFromFileChunks(String basePath, String mode, List<FileChunk> filesWithContent) {
    List<String> files = filesWithContent.stream().map(chunk -> chunk.path).collect(Collectors.toList());
    String content =
        filesWithContent.stream()
            .map(chunk -> "--- FILE: " + chunk.path + " ---\n" + chunk.content)
            .collect(Collectors.joining("\n\n"));
    List<FileMeta> meta =
        filesWithContent.stream()
            .map(chunk -> new FileMeta(chunk.path, chunk.totalBytes, chunk.truncated))
            .collect(Collectors.toList());
    return new LogPayload(basePath, mode, files, content, meta);
  }

  private boolean isWithinRoot(Path targetPath, Path rootPath) {
    Path target = targetPath.toAbsolutePath().normalize();
    Path root = rootPath.toAbsolutePath().normalize();
    return target.startsWith(root);
  }

  private boolean isLogLikeFile(String name) {
    return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(log|txt|json|out|err|trc)$");
  }

  private LogPayload buildCloudLogPayload(CloudRef cloudRef) {
    if (!settings.isCloudEnabled()) {
      throw new IllegalStateException("Cloud path reading is disabled. Set LOG_ENABLE_CLOUD_PATHS=true.");
    }
    Set<String> providers = settings.getAllowedCloudProviders();
    if (!providers.contains(cloudRef.provider)) {
      throw new IllegalStateException(
          "Cloud provider \"" + cloudRef.provider + "\" is not allowed. Update LOG_CLOUD_PROVIDERS to enable it.");
    }

    if ("aws".equals(cloudRef.provider)) {
      return buildS3Payload(cloudRef);
    }
    if ("gcp".equals(cloudRef.provider)) {
      return buildGcsPayload(cloudRef);
    }
    if ("azure".equals(cloudRef.provider)) {
      return buildAzurePayload(cloudRef);
    }
    throw new IllegalStateException("Unsupported cloud provider: " + cloudRef.provider);
  }

  private LogPayload buildS3Payload(CloudRef cloudRef) {
    try (S3Client client = S3Client.builder().region(Region.of(settings.getAwsRegion())).build()) {
      String key = cloudRef.objectPath == null ? "" : cloudRef.objectPath;
      boolean explicitPrefix = key.isEmpty() || key.endsWith("/");

      if (!explicitPrefix) {
        try {
          TailResult tail = readS3ObjectTail(client, cloudRef.bucket, key);
          String objectPath = "s3://" + cloudRef.bucket + "/" + key;
          return new LogPayload(
              cloudRef.rawPath,
              "file",
              List.of(objectPath),
              tail.content,
              List.of(new FileMeta(objectPath, tail.totalBytes, tail.truncated)));
        } catch (RuntimeException ex) {
          if (!isObjectNotFound(ex)) {
            throw ex;
          }
        }
      }

      String prefix = explicitPrefix ? key : key;
      List<CloudObject> listed = listS3ObjectsByPrefix(client, cloudRef.bucket, prefix, settings.getMaxFiles());
      if (listed.isEmpty()) {
        throw new IllegalStateException("No log objects found for s3://" + cloudRef.bucket + "/" + prefix);
      }

      List<FileChunk> fileChunks = new ArrayList<>();
      for (CloudObject item : listed) {
        TailResult tail = readS3ObjectTail(client, cloudRef.bucket, item.key);
        String filePath = "s3://" + cloudRef.bucket + "/" + item.key;
        fileChunks.add(new FileChunk(filePath, tail.content, tail.totalBytes, tail.truncated));
      }
      return buildPayloadFromFileChunks(cloudRef.rawPath, "directory", fileChunks);
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to read S3 logs: " + exception.getMessage(), exception);
    }
  }

  private TailResult readS3ObjectTail(S3Client client, String bucket, String key) {
    try {
      HeadObjectResponse head =
          client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      long totalBytes = head.contentLength() == null ? 0L : head.contentLength();
      int bytesToRead = (int) Math.min(totalBytes, Math.max(1, settings.getMaxBytesPerFile()));
      long rangeStart = Math.max(0, totalBytes - bytesToRead);

      GetObjectRequest.Builder getBuilder = GetObjectRequest.builder().bucket(bucket).key(key);
      if (totalBytes > 0 && bytesToRead > 0) {
        getBuilder.range("bytes=" + rangeStart + "-" + (totalBytes - 1));
      }

      try (ResponseInputStream<GetObjectResponse> stream = client.getObject(getBuilder.build())) {
        byte[] buffer = stream.readAllBytes();
        return buildTailContent(buffer, totalBytes, settings.getMaxBytesPerFile());
      }
    } catch (NoSuchKeyException ex) {
      throw new IllegalStateException("Object not found", ex);
    } catch (S3Exception ex) {
      if (ex.statusCode() == 404) {
        throw new IllegalStateException("Object not found", ex);
      }
      throw ex;
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read S3 object", ex);
    }
  }

  private List<CloudObject> listS3ObjectsByPrefix(S3Client client, String bucket, String prefix, int maxFiles) {
    List<CloudObject> results = new ArrayList<>();
    int scanCap = Math.max(maxFiles * 12, 120);
    String continuationToken = null;

    do {
      int maxKeys = Math.min(1000, Math.max(1, scanCap - results.size()));
      ListObjectsV2Request request =
          ListObjectsV2Request.builder()
              .bucket(bucket)
              .prefix(hasText(prefix) ? prefix : null)
              .continuationToken(continuationToken)
              .maxKeys(maxKeys)
              .build();
      ListObjectsV2Response response = client.listObjectsV2(request);
      List<S3Object> contents = response.contents();
      if (contents != null) {
        for (S3Object object : contents) {
          if (!hasText(object.key())) {
            continue;
          }
          long modified = object.lastModified() == null ? 0L : object.lastModified().toEpochMilli();
          results.add(new CloudObject(object.key(), object.size(), modified));
        }
      }
      continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
    } while (hasText(continuationToken) && results.size() < scanCap);

    return selectRecentCloudObjects(results, maxFiles);
  }

  private LogPayload buildGcsPayload(CloudRef cloudRef) {
    try {
      Storage storage = createStorageClient();
      String key = cloudRef.objectPath == null ? "" : cloudRef.objectPath;
      boolean explicitPrefix = key.isEmpty() || key.endsWith("/");

      if (!explicitPrefix) {
        try {
          TailResult tail = readGcsObjectTail(storage, cloudRef.bucket, key);
          String objectPath = "gs://" + cloudRef.bucket + "/" + key;
          return new LogPayload(
              cloudRef.rawPath,
              "file",
              List.of(objectPath),
              tail.content,
              List.of(new FileMeta(objectPath, tail.totalBytes, tail.truncated)));
        } catch (RuntimeException ex) {
          if (!isObjectNotFound(ex)) {
            throw ex;
          }
        }
      }

      String prefix = explicitPrefix ? key : key;
      List<CloudObject> listed = listGcsObjectsByPrefix(storage, cloudRef.bucket, prefix, settings.getMaxFiles());
      if (listed.isEmpty()) {
        throw new IllegalStateException("No log objects found for gs://" + cloudRef.bucket + "/" + prefix);
      }

      List<FileChunk> fileChunks = new ArrayList<>();
      for (CloudObject item : listed) {
        TailResult tail = readGcsObjectTail(storage, cloudRef.bucket, item.key);
        String filePath = "gs://" + cloudRef.bucket + "/" + item.key;
        fileChunks.add(new FileChunk(filePath, tail.content, tail.totalBytes, tail.truncated));
      }
      return buildPayloadFromFileChunks(cloudRef.rawPath, "directory", fileChunks);
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to read GCS logs: " + exception.getMessage(), exception);
    }
  }

  private Storage createStorageClient() {
    StorageOptions.Builder builder = StorageOptions.newBuilder();
    if (hasText(settings.getGcpProjectId())) {
      builder.setProjectId(settings.getGcpProjectId());
    }

    if (hasText(settings.getGcpCredentialsJson())) {
      try (InputStream in =
          new ByteArrayInputStream(settings.getGcpCredentialsJson().getBytes(StandardCharsets.UTF_8))) {
        builder.setCredentials(GoogleCredentials.fromStream(in));
      } catch (IOException exception) {
        throw new IllegalStateException("Invalid GOOGLE_APPLICATION_CREDENTIALS_JSON payload.", exception);
      }
    }

    return builder.build().getService();
  }

  private TailResult readGcsObjectTail(Storage storage, String bucket, String key) {
    Blob blob = storage.get(BlobId.of(bucket, key));
    if (blob == null) {
      throw new IllegalStateException("Object not found");
    }

    long totalBytes = blob.getSize() == null ? 0L : blob.getSize();
    if (totalBytes <= 0) {
      return new TailResult("", 0, false);
    }

    int bytesToRead = (int) Math.min(totalBytes, Math.max(1, settings.getMaxBytesPerFile()));
    long start = Math.max(0, totalBytes - bytesToRead);
    ByteArrayOutputStream out = new ByteArrayOutputStream(bytesToRead);

    try (ReadChannel reader = blob.reader()) {
      reader.seek(start);
      ByteBuffer buffer = ByteBuffer.allocate(Math.min(8192, bytesToRead));
      while (out.size() < bytesToRead) {
        int toRead = Math.min(buffer.capacity(), bytesToRead - out.size());
        buffer.clear();
        buffer.limit(toRead);
        int read = reader.read(buffer);
        if (read < 0) {
          break;
        }
        out.write(buffer.array(), 0, read);
      }
    } catch (StorageException ex) {
      if (ex.getCode() == 404) {
        throw new IllegalStateException("Object not found", ex);
      }
      throw ex;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read GCS object.", exception);
    }

    return buildTailContent(out.toByteArray(), totalBytes, settings.getMaxBytesPerFile());
  }

  private List<CloudObject> listGcsObjectsByPrefix(Storage storage, String bucket, String prefix, int maxFiles) {
    int scanCap = Math.max(maxFiles * 12, 120);
    List<BlobListOption> options = new ArrayList<>();
    if (hasText(prefix)) {
      options.add(BlobListOption.prefix(prefix));
    }
    options.add(BlobListOption.pageSize(scanCap));

    Page<Blob> page = storage.list(bucket, options.toArray(new BlobListOption[0]));
    List<CloudObject> results = new ArrayList<>();
    for (Blob blob : page.iterateAll()) {
      if (blob == null || !hasText(blob.getName())) {
        continue;
      }
      Long updatedAt = blob.getUpdateTime();
      Long createdAt = blob.getCreateTime();
      long modified = updatedAt != null ? updatedAt : (createdAt != null ? createdAt : 0L);
      results.add(new CloudObject(blob.getName(), blob.getSize() == null ? 0L : blob.getSize(), modified));
      if (results.size() >= scanCap) {
        break;
      }
    }
    return selectRecentCloudObjects(results, maxFiles);
  }

  private LogPayload buildAzurePayload(CloudRef cloudRef) {
    String key = cloudRef.objectPath == null ? "" : cloudRef.objectPath;
    boolean explicitPrefix = key.isEmpty() || key.endsWith("/");

    if (cloudRef.isHttpsUrl && !explicitPrefix) {
      BlobClient blobClient = new BlobClientBuilder().endpoint(cloudRef.rawPath).buildClient();
      TailResult tail = readAzureBlobTail(blobClient);
      return new LogPayload(
          cloudRef.rawPath,
          "file",
          List.of(cloudRef.rawPath),
          tail.content,
          List.of(new FileMeta(cloudRef.rawPath, tail.totalBytes, tail.truncated)));
    }

    BlobServiceClient serviceClient = createAzureBlobServiceClient(cloudRef.account);
    BlobContainerClient containerClient = serviceClient.getBlobContainerClient(cloudRef.container);

    if (!explicitPrefix) {
      BlobClient blobClient = containerClient.getBlobClient(key);
      try {
        TailResult tail = readAzureBlobTail(blobClient);
        String accountName = hasText(cloudRef.account) ? cloudRef.account : settings.getAzureDefaultAccount();
        String objectPath = "az://" + accountName + "/" + cloudRef.container + "/" + key;
        return new LogPayload(
            cloudRef.rawPath,
            "file",
            List.of(objectPath),
            tail.content,
            List.of(new FileMeta(objectPath, tail.totalBytes, tail.truncated)));
      } catch (RuntimeException ex) {
        if (!isObjectNotFound(ex)) {
          throw ex;
        }
      }
    }

    String prefix = explicitPrefix ? key : key;
    List<CloudObject> listed = listAzureBlobsByPrefix(containerClient, prefix, settings.getMaxFiles());
    if (listed.isEmpty()) {
      String accountName = hasText(cloudRef.account) ? cloudRef.account : settings.getAzureDefaultAccount();
      throw new IllegalStateException(
          "No log blobs found for az://" + accountName + "/" + cloudRef.container + "/" + prefix);
    }

    String accountName = hasText(cloudRef.account) ? cloudRef.account : settings.getAzureDefaultAccount();
    List<FileChunk> fileChunks = new ArrayList<>();
    for (CloudObject item : listed) {
      BlobClient blobClient = containerClient.getBlobClient(item.key);
      TailResult tail = readAzureBlobTail(blobClient);
      String filePath = "az://" + accountName + "/" + cloudRef.container + "/" + item.key;
      fileChunks.add(new FileChunk(filePath, tail.content, tail.totalBytes, tail.truncated));
    }
    return buildPayloadFromFileChunks(cloudRef.rawPath, "directory", fileChunks);
  }

  private BlobServiceClient createAzureBlobServiceClient(String requestedAccount) {
    if (hasText(settings.getAzureConnectionString())) {
      return new BlobServiceClientBuilder().connectionString(settings.getAzureConnectionString()).buildClient();
    }

    String account = hasText(requestedAccount) ? requestedAccount : settings.getAzureDefaultAccount();
    if (!hasText(account)) {
      throw new IllegalStateException(
          "Azure account is missing. Use az://<account>/<container>/<path> or set AZURE_STORAGE_ACCOUNT.");
    }

    BlobServiceClientBuilder builder =
        new BlobServiceClientBuilder().endpoint("https://" + account + ".blob.core.windows.net");

    if (hasText(settings.getAzureAccountKey())) {
      StorageSharedKeyCredential credential = new StorageSharedKeyCredential(account, settings.getAzureAccountKey());
      return builder.credential(credential).buildClient();
    }

    String sas = normalizeSasToken(settings.getAzureSasToken());
    if (hasText(sas)) {
      return builder.sasToken(sas).buildClient();
    }

    throw new IllegalStateException(
        "Azure credentials missing. Set AZURE_STORAGE_CONNECTION_STRING or AZURE_STORAGE_ACCOUNT + AZURE_STORAGE_KEY.");
  }

  private TailResult readAzureBlobTail(BlobClient blobClient) {
    try {
      BlobProperties properties = blobClient.getProperties();
      long totalBytes = properties.getBlobSize();
      if (totalBytes <= 0) {
        return new TailResult("", 0, false);
      }

      long bytesToRead = Math.min(totalBytes, Math.max(1, settings.getMaxBytesPerFile()));
      long offset = Math.max(0, totalBytes - bytesToRead);
      BlobRange range = new BlobRange(offset, bytesToRead);
      ByteArrayOutputStream out = new ByteArrayOutputStream((int) bytesToRead);
      blobClient.downloadStreamWithResponse(out, range, null, null, false, Duration.ofMinutes(2), Context.NONE);
      return buildTailContent(out.toByteArray(), totalBytes, settings.getMaxBytesPerFile());
    } catch (BlobStorageException ex) {
      if (ex.getStatusCode() == 404) {
        throw new IllegalStateException("Object not found", ex);
      }
      throw ex;
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Unable to read Azure blob: " + exception.getMessage(), exception);
    }
  }

  private List<CloudObject> listAzureBlobsByPrefix(BlobContainerClient containerClient, String prefix, int maxFiles) {
    int scanCap = Math.max(maxFiles * 12, 120);
    ListBlobsOptions options = new ListBlobsOptions();
    if (hasText(prefix)) {
      options.setPrefix(prefix);
    }

    List<CloudObject> results = new ArrayList<>();
    for (BlobItem blob : containerClient.listBlobs(options, Duration.ofMinutes(2))) {
      if (blob == null || !hasText(blob.getName()) || blob.getName().endsWith("/")) {
        continue;
      }
      BlobItemProperties props = blob.getProperties();
      long size = props == null || props.getContentLength() == null ? 0L : props.getContentLength();
      long modified = 0L;
      if (props != null && props.getLastModified() != null) {
        modified = props.getLastModified().toInstant().toEpochMilli();
      }
      results.add(new CloudObject(blob.getName(), size, modified));
      if (results.size() >= scanCap) {
        break;
      }
    }
    return selectRecentCloudObjects(results, maxFiles);
  }

  private List<CloudObject> selectRecentCloudObjects(List<CloudObject> items, int maxFiles) {
    List<CloudObject> normalized =
        items.stream()
            .filter(item -> hasText(item.key) && !item.key.endsWith("/"))
            .collect(Collectors.toList());

    List<CloudObject> logLike =
        normalized.stream()
            .filter(item -> isLogLikeFile(fileNameFromObjectKey(item.key)))
            .collect(Collectors.toList());

    List<CloudObject> source = logLike.isEmpty() ? normalized : logLike;
    source.sort(Comparator.comparingLong((CloudObject item) -> item.modifiedAtEpochMs).reversed());

    int size = Math.min(source.size(), Math.max(1, maxFiles));
    return new ArrayList<>(source.subList(0, size));
  }

  private String fileNameFromObjectKey(String key) {
    if (!hasText(key)) {
      return "";
    }
    int slash = key.lastIndexOf('/');
    return slash >= 0 ? key.substring(slash + 1) : key;
  }

  private CloudRef parseCloudPath(String rawPath) {
    Matcher s3 = S3_PATH.matcher(rawPath);
    if (s3.matches()) {
      return new CloudRef("aws", s3.group(1), "", "", safe(s3.group(2)), rawPath, "", false);
    }

    Matcher gs = GS_PATH.matcher(rawPath);
    if (gs.matches()) {
      return new CloudRef("gcp", gs.group(1), "", "", safe(gs.group(2)), rawPath, "", false);
    }

    Matcher azureExplicit = AZURE_EXPLICIT_PATH.matcher(rawPath);
    if (azureExplicit.matches()) {
      return new CloudRef(
          "azure",
          "",
          azureExplicit.group(1),
          azureExplicit.group(2),
          safe(azureExplicit.group(3)),
          rawPath,
          "",
          false);
    }

    Matcher azureContainer = AZURE_CONTAINER_PATH.matcher(rawPath);
    if (azureContainer.matches()) {
      return new CloudRef(
          "azure", "", "", azureContainer.group(1), safe(azureContainer.group(2)), rawPath, "", false);
    }

    Matcher azureUrl = AZURE_HTTPS_URL.matcher(rawPath);
    if (azureUrl.matches()) {
      return new CloudRef(
          "azure",
          "",
          azureUrl.group(1),
          azureUrl.group(2),
          safe(azureUrl.group(3)),
          rawPath,
          safe(azureUrl.group(4)),
          true);
    }

    return null;
  }

  private boolean isObjectNotFound(Throwable error) {
    if (error == null) {
      return false;
    }
    if (error instanceof BlobStorageException) {
      return ((BlobStorageException) error).getStatusCode() == 404;
    }
    if (error instanceof StorageException) {
      return ((StorageException) error).getCode() == 404;
    }
    if (error instanceof NoSuchKeyException) {
      return true;
    }
    if (error instanceof S3Exception) {
      return ((S3Exception) error).statusCode() == 404;
    }
    if (error.getCause() != null && error != error.getCause()) {
      return isObjectNotFound(error.getCause());
    }
    String message = error.getMessage();
    return hasText(message)
        && (message.toLowerCase(Locale.ROOT).contains("not found")
            || message.toLowerCase(Locale.ROOT).contains("nosuchkey"));
  }

  private String normalizeSasToken(String value) {
    String token = value == null ? "" : value.trim();
    if (!hasText(token)) {
      return "";
    }
    return token.startsWith("?") ? token.substring(1) : token;
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private static class TailResult {
    final String content;
    final long totalBytes;
    final boolean truncated;

    TailResult(String content, long totalBytes, boolean truncated) {
      this.content = content;
      this.totalBytes = totalBytes;
      this.truncated = truncated;
    }
  }

  private static class FileChunk {
    final String path;
    final String content;
    final long totalBytes;
    final boolean truncated;

    FileChunk(String path, String content, long totalBytes, boolean truncated) {
      this.path = path;
      this.content = content;
      this.totalBytes = totalBytes;
      this.truncated = truncated;
    }
  }

  private static class CloudObject {
    final String key;
    final long size;
    final long modifiedAtEpochMs;

    CloudObject(String key, long size, long modifiedAtEpochMs) {
      this.key = key;
      this.size = size;
      this.modifiedAtEpochMs = modifiedAtEpochMs;
    }
  }

  private static class CloudRef {
    final String provider;
    final String bucket;
    final String account;
    final String container;
    final String objectPath;
    final String rawPath;
    final String sasToken;
    final boolean isHttpsUrl;

    CloudRef(
        String provider,
        String bucket,
        String account,
        String container,
        String objectPath,
        String rawPath,
        String sasToken,
        boolean isHttpsUrl) {
      this.provider = provider;
      this.bucket = bucket;
      this.account = account;
      this.container = container;
      this.objectPath = objectPath;
      this.rawPath = rawPath;
      this.sasToken = sasToken;
      this.isHttpsUrl = isHttpsUrl;
    }
  }

  public static class PathNotAllowedException extends RuntimeException {
    public PathNotAllowedException(String message) {
      super(message);
    }
  }

  public static class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
      super(message);
    }
  }
}
