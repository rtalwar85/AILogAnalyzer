package com.ailoganalyzer.app.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AnalyzerSettings {
  public static final int DEFAULT_PATH_HISTORY_LIMIT = 30;
  public static final int DEFAULT_WEB_SOLUTION_LIMIT = 5;
  public static final String DEFAULT_CHATGPT_WEB_SEARCH_MODEL = "gpt-4.1-mini";
  public static final String DEFAULT_GEMINI_FREE_MODEL = "gemini-2.0-flash-lite";
  public static final String DEFAULT_HUGGINGFACE_MODEL = "Qwen/Qwen2.5-7B-Instruct";
  public static final String DEFAULT_GROQ_FREE_MODEL = "llama-3.1-8b-instant";
  public static final String DEFAULT_OPENROUTER_FREE_MODEL = "meta-llama/llama-3.2-3b-instruct:free";

  private final List<Path> allowedRoots;
  private final boolean allowAnyPath;
  private final int maxFiles;
  private final int maxBytesPerFile;
  private final int pathHistoryLimit;
  private final Path configFilePath;
  private final boolean cloudEnabled;
  private final Set<String> allowedCloudProviders;
  private final String awsRegion;
  private final String gcpProjectId;
  private final String gcpCredentialsJson;
  private final String azureConnectionString;
  private final String azureDefaultAccount;
  private final String azureAccountKey;
  private final String azureSasToken;
  private final int webSolutionLimit;
  private final boolean enableGoogleSearch;
  private final boolean enableGeminiFreeSearch;
  private final boolean enableHuggingFaceSearch;
  private final boolean enableGroqFreeSearch;
  private final boolean enableOpenRouterFreeSearch;
  private final boolean enableChatgptWebSearch;
  private final boolean enableGithubIssueSearch;
  private final String googleApiKey;
  private final String googleCseCx;
  private final String geminiApiKey;
  private final String huggingFaceApiKey;
  private final String groqApiKey;
  private final String openRouterApiKey;
  private final String openAiApiKey;
  private final String githubToken;
  private final String geminiFreeModel;
  private final String huggingFaceModel;
  private final String groqFreeModel;
  private final String openRouterFreeModel;
  private final String chatgptWebSearchModel;
  private final Environment environment;
  private final Map<String, String> dotEnvValues;

  public AnalyzerSettings(Environment env) {
    this.environment = env;
    this.dotEnvValues = loadDotEnvValues();
    String userDir = Paths.get("").toAbsolutePath().normalize().toString();

    this.allowedRoots = parseAllowedRoots(readFirst("LOG_ALLOWED_ROOTS"), userDir);
    this.allowAnyPath = "true".equalsIgnoreCase(readFirst("LOG_ALLOW_ANY_PATH"));
    this.maxFiles = clamp(readInt(readFirst("LOG_MAX_FILES"), 30), 1, 30);
    this.maxBytesPerFile =
        clamp(readInt(readFirst("LOG_MAX_BYTES"), 2 * 1024 * 1024), 64 * 1024, 50 * 1024 * 1024);
    this.pathHistoryLimit =
        clamp(
            readInt(readFirst("LOG_PATH_HISTORY_LIMIT"), DEFAULT_PATH_HISTORY_LIMIT),
            1,
            DEFAULT_PATH_HISTORY_LIMIT);

    String rawConfigPath = readFirst("LOG_ANALYZER_CONFIG_FILE");
    this.configFilePath =
        hasText(rawConfigPath)
            ? Paths.get(rawConfigPath).toAbsolutePath().normalize()
            : Paths.get(userDir, ".log-analyzer.config.json").toAbsolutePath().normalize();

    this.cloudEnabled = !"false".equalsIgnoreCase(readFirst("LOG_ENABLE_CLOUD_PATHS"));
    this.allowedCloudProviders = parseCloudProviders(readFirst("LOG_CLOUD_PROVIDERS"));
    this.awsRegion =
        firstNonBlank(
            readFirst("LOG_AWS_REGION"),
            readFirst("AWS_REGION"),
            readFirst("AWS_DEFAULT_REGION"),
            "us-east-1");
    this.gcpProjectId =
        firstNonBlank(readFirst("GCP_PROJECT_ID"), readFirst("GOOGLE_CLOUD_PROJECT"), "");
    this.gcpCredentialsJson = readFirst("GOOGLE_APPLICATION_CREDENTIALS_JSON");
    this.azureConnectionString = readFirst("AZURE_STORAGE_CONNECTION_STRING");
    this.azureDefaultAccount =
        firstNonBlank(
            readFirst("AZURE_STORAGE_ACCOUNT"),
            readFirst("AZURE_STORAGE_ACCOUNT_NAME"),
            "");
    this.azureAccountKey =
        firstNonBlank(
            readFirst("AZURE_STORAGE_KEY"),
            readFirst("AZURE_STORAGE_ACCOUNT_KEY"),
            "");
    this.azureSasToken = readFirst("AZURE_STORAGE_SAS_TOKEN");

    this.webSolutionLimit =
        clamp(readInt(readFirst("LOG_WEB_SOLUTION_LIMIT"), DEFAULT_WEB_SOLUTION_LIMIT), 1, 10);
    this.enableGoogleSearch = !"false".equalsIgnoreCase(readFirst("LOG_ENABLE_GOOGLE_SEARCH"));
    this.enableGeminiFreeSearch = !"false".equalsIgnoreCase(readFirst("LOG_ENABLE_GEMINI_FREE_SEARCH"));
    this.enableHuggingFaceSearch =
        !"false".equalsIgnoreCase(readFirst("LOG_ENABLE_HUGGINGFACE_SEARCH"));
    this.enableGroqFreeSearch = !"false".equalsIgnoreCase(readFirst("LOG_ENABLE_GROQ_FREE_SEARCH"));
    this.enableOpenRouterFreeSearch =
        !"false".equalsIgnoreCase(readFirst("LOG_ENABLE_OPENROUTER_FREE_SEARCH"));
    this.enableChatgptWebSearch = "true".equalsIgnoreCase(readFirst("LOG_ENABLE_CHATGPT_WEB_SEARCH"));
    this.enableGithubIssueSearch = !"false".equalsIgnoreCase(readFirst("LOG_ENABLE_GITHUB_ISSUE_SEARCH"));
    this.googleApiKey = firstNonBlank(readFirst("GOOGLE_CSE_API_KEY"), readFirst("GOOGLE_API_KEY"), "");
    this.googleCseCx = firstNonBlank(readFirst("GOOGLE_CSE_CX"), readFirst("GOOGLE_SEARCH_ENGINE_ID"), "");
    this.geminiApiKey = readFirst("GEMINI_API_KEY");
    this.huggingFaceApiKey =
        firstNonBlank(readFirst("HUGGINGFACE_API_KEY"), readFirst("HF_TOKEN"), "");
    this.groqApiKey = readFirst("GROQ_API_KEY");
    this.openRouterApiKey = readFirst("OPENROUTER_API_KEY");
    this.openAiApiKey =
        firstNonBlank(readFirst("OPENAI_API_KEY"), readFirst("VITE_OPENAI_API_KEY"), "");
    this.githubToken = readFirst("GITHUB_TOKEN");
    this.geminiFreeModel =
        firstNonBlank(readFirst("LOG_GEMINI_FREE_MODEL"), DEFAULT_GEMINI_FREE_MODEL);
    this.huggingFaceModel =
        firstNonBlank(readFirst("LOG_HUGGINGFACE_MODEL"), DEFAULT_HUGGINGFACE_MODEL);
    this.groqFreeModel = firstNonBlank(readFirst("LOG_GROQ_FREE_MODEL"), DEFAULT_GROQ_FREE_MODEL);
    this.openRouterFreeModel =
        firstNonBlank(readFirst("LOG_OPENROUTER_FREE_MODEL"), DEFAULT_OPENROUTER_FREE_MODEL);
    this.chatgptWebSearchModel =
        firstNonBlank(
            readFirst("LOG_CHATGPT_WEB_SEARCH_MODEL"), DEFAULT_CHATGPT_WEB_SEARCH_MODEL);
  }

  private static List<Path> parseAllowedRoots(String raw, String defaultRoot) {
    if (!hasText(raw)) {
      return List.of(Paths.get(defaultRoot).toAbsolutePath().normalize());
    }
    List<Path> roots = new ArrayList<>();
    for (String token : raw.split("[;,\\n]")) {
      String item = token == null ? "" : token.trim();
      if (!hasText(item)) {
        continue;
      }
      roots.add(Paths.get(item).toAbsolutePath().normalize());
    }
    if (roots.isEmpty()) {
      roots.add(Paths.get(defaultRoot).toAbsolutePath().normalize());
    }
    return roots;
  }

  private static Set<String> parseCloudProviders(String raw) {
    if (!hasText(raw)) {
      return new LinkedHashSet<>(Arrays.asList("aws", "gcp", "azure"));
    }

    Set<String> providers =
        Arrays.stream(raw.split("[;,\\n]"))
            .map(AnalyzerSettings::normalizeCloudProviderName)
            .filter(AnalyzerSettings::hasText)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    if (providers.isEmpty()) {
      providers.add("aws");
      providers.add("gcp");
      providers.add("azure");
    }
    return providers;
  }

  private static String normalizeCloudProviderName(String value) {
    String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (!hasText(raw)) {
      return "";
    }
    if ("aws".equals(raw) || "s3".equals(raw)) {
      return "aws";
    }
    if ("gcp".equals(raw) || "gcs".equals(raw) || "gs".equals(raw)) {
      return "gcp";
    }
    if ("azure".equals(raw) || "az".equals(raw) || "blob".equals(raw)) {
      return "azure";
    }
    return "";
  }

  private String readFirst(String key) {
    String fromSpring = environment.getProperty(key);
    if (hasText(fromSpring)) {
      return fromSpring.trim();
    }
    String fromEnv = System.getenv(key);
    if (hasText(fromEnv)) {
      return fromEnv.trim();
    }
    String fromDotEnv = dotEnvValues.get(key);
    if (hasText(fromDotEnv)) {
      return fromDotEnv.trim();
    }
    return "";
  }

  private Map<String, String> loadDotEnvValues() {
    List<Path> candidates = new ArrayList<>();
    Path cursor = Paths.get("").toAbsolutePath().normalize();
    for (int i = 0; i < 4 && cursor != null; i++) {
      candidates.add(cursor.resolve(".env").toAbsolutePath().normalize());
      cursor = cursor.getParent();
    }

    Map<String, String> values = new LinkedHashMap<>();
    for (int i = candidates.size() - 1; i >= 0; i--) {
      Path candidate = candidates.get(i);
      if (!Files.isRegularFile(candidate)) {
        continue;
      }
      values.putAll(parseDotEnvFile(candidate));
    }
    return values;
  }

  private Map<String, String> parseDotEnvFile(Path filePath) {
    Map<String, String> values = new LinkedHashMap<>();
    List<String> lines;
    try {
      lines = Files.readAllLines(filePath);
    } catch (IOException ignored) {
      return values;
    }

    for (String rawLine : lines) {
      String line = rawLine == null ? "" : rawLine.trim();
      if (!hasText(line) || line.startsWith("#")) {
        continue;
      }

      if (line.startsWith("export ")) {
        line = line.substring("export ".length()).trim();
      }

      int separator = line.indexOf('=');
      if (separator <= 0) {
        continue;
      }

      String key = line.substring(0, separator).trim();
      String value = line.substring(separator + 1).trim();
      if (!hasText(key)) {
        continue;
      }

      if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
        value =
            value
                .substring(1, value.length() - 1)
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
      } else if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
        value = value.substring(1, value.length() - 1);
      } else {
        int inlineComment = value.indexOf(" #");
        if (inlineComment >= 0) {
          value = value.substring(0, inlineComment).trim();
        }
      }

      values.put(key, value);
    }

    return values;
  }

  private static int readInt(String value, int fallback) {
    if (!hasText(value)) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (hasText(value)) {
        return value.trim();
      }
    }
    return "";
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  public List<Path> getAllowedRoots() {
    return allowedRoots;
  }

  public boolean isAllowAnyPath() {
    return allowAnyPath;
  }

  public int getMaxFiles() {
    return maxFiles;
  }

  public int getMaxBytesPerFile() {
    return maxBytesPerFile;
  }

  public int getPathHistoryLimit() {
    return pathHistoryLimit;
  }

  public Path getConfigFilePath() {
    return configFilePath;
  }

  public boolean isCloudEnabled() {
    return cloudEnabled;
  }

  public Set<String> getAllowedCloudProviders() {
    return allowedCloudProviders;
  }

  public String getAwsRegion() {
    return awsRegion;
  }

  public String getGcpProjectId() {
    return gcpProjectId;
  }

  public String getGcpCredentialsJson() {
    return gcpCredentialsJson;
  }

  public String getAzureConnectionString() {
    return azureConnectionString;
  }

  public String getAzureDefaultAccount() {
    return azureDefaultAccount;
  }

  public String getAzureAccountKey() {
    return azureAccountKey;
  }

  public String getAzureSasToken() {
    return azureSasToken;
  }

  public int getWebSolutionLimit() {
    return webSolutionLimit;
  }

  public String getOpenAiApiKey() {
    return openAiApiKey;
  }

  public boolean isEnableGoogleSearch() {
    return enableGoogleSearch;
  }

  public boolean isEnableGeminiFreeSearch() {
    return enableGeminiFreeSearch;
  }

  public boolean isEnableHuggingFaceSearch() {
    return enableHuggingFaceSearch;
  }

  public boolean isEnableGroqFreeSearch() {
    return enableGroqFreeSearch;
  }

  public boolean isEnableOpenRouterFreeSearch() {
    return enableOpenRouterFreeSearch;
  }

  public boolean isEnableChatgptWebSearch() {
    return enableChatgptWebSearch;
  }

  public boolean isEnableGithubIssueSearch() {
    return enableGithubIssueSearch;
  }

  public String getGithubToken() {
    return githubToken;
  }

  public String getGoogleApiKey() {
    return googleApiKey;
  }

  public String getGoogleCseCx() {
    return googleCseCx;
  }

  public String getGeminiApiKey() {
    return geminiApiKey;
  }

  public String getHuggingFaceApiKey() {
    return huggingFaceApiKey;
  }

  public String getGroqApiKey() {
    return groqApiKey;
  }

  public String getOpenRouterApiKey() {
    return openRouterApiKey;
  }

  public String getGeminiFreeModel() {
    return geminiFreeModel;
  }

  public String getHuggingFaceModel() {
    return huggingFaceModel;
  }

  public String getGroqFreeModel() {
    return groqFreeModel;
  }

  public String getOpenRouterFreeModel() {
    return openRouterFreeModel;
  }

  public String getChatgptWebSearchModel() {
    return chatgptWebSearchModel;
  }
}
