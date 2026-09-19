package com.homektv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import com.homektv.library.LibraryMode;

/**
 * 应用配置（app.* 前缀）。
 *
 * Application configuration (app.* prefix).
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 源素材扫描目录（容器内 /source-music）。Source music scan directory (inside container: /source-music). */
    private String sourceLibraryPath = "/source-music";

    /** TV 实际播放曲库目录（容器内 /music）。TV playback music library directory (inside container: /music). */
    private String ktvLibraryPath = "/music";

    /** 曲库所有权模式。Managed imports into /music; external mode reads source in place. */
    private LibraryMode libraryMode = LibraryMode.MANAGED;

    /** 数据缓存目录（封面/歌词）。Data cache directory (covers/lyrics). */
    private String dataPath = "./data";

    /** ffprobe 可执行路径。ffprobe executable path. */
    private String ffprobePath = "ffprobe";

    /** Encrypted application-secret master key file. */
    private String configMasterKeyPath = "/data/secrets/config.key";

    /** 管理后台密码；为空时管理接口安全关闭。Admin password; blank disables admin APIs. */
    private String adminPassword = "";

    /** Optional shared credential for TV WebSocket clients. */
    private String playerCredential = "";

    /** 外部歌手头像库目录（容器内 /avatar-library）。External artist avatar library directory (inside container: /avatar-library). */
    private String avatarLibraryPath = "/avatar-library";

    /** AI 曲库分析配置。AI music library analysis configuration. */
    private Ai ai = new Ai();

    /** 局域网服务发现配置。LAN service discovery configuration. */
    private Discovery discovery = new Discovery();

    /** Application release metadata and bundled Android TV packages. */
    private Release release = new Release();

    /** Scan safety settings. */
    private Scan scan = new Scan();

    /** Prefer recognized four-part filename metadata over embedded tags. */
    private boolean structuredFilenameMetadataPreferred = false;

    public String getSourceLibraryPath() { return sourceLibraryPath; }
    public void setSourceLibraryPath(String sourceLibraryPath) { this.sourceLibraryPath = sourceLibraryPath; }
    public String getKtvLibraryPath() { return ktvLibraryPath; }
    public void setKtvLibraryPath(String ktvLibraryPath) { this.ktvLibraryPath = ktvLibraryPath; }
    public LibraryMode getLibraryMode() { return libraryMode; }
    public void setLibraryMode(LibraryMode libraryMode) { this.libraryMode = libraryMode; }
    public String getAvatarLibraryPath() { return avatarLibraryPath; }
    public void setAvatarLibraryPath(String avatarLibraryPath) { this.avatarLibraryPath = avatarLibraryPath; }
    public boolean isExternalReadOnly() { return LibraryMode.EXTERNAL_READ_ONLY.equals(libraryMode); }
    public String getDataPath() { return dataPath; }
    public void setDataPath(String dataPath) { this.dataPath = dataPath; }
    public String getFfprobePath() { return ffprobePath; }
    public void setFfprobePath(String ffprobePath) { this.ffprobePath = ffprobePath; }
    public String getConfigMasterKeyPath() { return configMasterKeyPath; }
    public void setConfigMasterKeyPath(String configMasterKeyPath) { this.configMasterKeyPath = configMasterKeyPath; }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
    public String getPlayerCredential() { return playerCredential; }
    public void setPlayerCredential(String playerCredential) { this.playerCredential = playerCredential; }
    public Ai getAi() { return ai; }
    public void setAi(Ai ai) { this.ai = ai; }
    public Discovery getDiscovery() { return discovery; }
    public void setDiscovery(Discovery discovery) { this.discovery = discovery; }
    public Release getRelease() { return release; }
    public void setRelease(Release release) { this.release = release; }
    public Scan getScan() { return scan; }
    public void setScan(Scan scan) { this.scan = scan; }
    public boolean isStructuredFilenameMetadataPreferred() { return structuredFilenameMetadataPreferred; }
    public void setStructuredFilenameMetadataPreferred(boolean preferred) {
        this.structuredFilenameMetadataPreferred = preferred;
    }

    public static class Scan {
        private MissingGuard missingGuard = new MissingGuard();

        public MissingGuard getMissingGuard() { return missingGuard; }
        public void setMissingGuard(MissingGuard missingGuard) { this.missingGuard = missingGuard; }
    }

    public static class MissingGuard {
        private long minimumPreviousFiles = 1_000;
        private double minimumSeenRatio = 0.50d;
        private boolean allowMassMissing = false;

        public long getMinimumPreviousFiles() { return minimumPreviousFiles; }
        public void setMinimumPreviousFiles(long minimumPreviousFiles) { this.minimumPreviousFiles = minimumPreviousFiles; }
        public double getMinimumSeenRatio() { return minimumSeenRatio; }
        public void setMinimumSeenRatio(double minimumSeenRatio) { this.minimumSeenRatio = minimumSeenRatio; }
        public boolean isAllowMassMissing() { return allowMassMissing; }
        public void setAllowMassMissing(boolean allowMassMissing) { this.allowMassMissing = allowMassMissing; }
    }

    public static class Release {
        private String version = "0.1.0-dev";
        private long versionCode = 1;
        private String armeabiV7aApk = "classpath:/static/tv-apk/home-ktv-tv-armeabi-v7a.apk";
        private String arm64V8aApk = "classpath:/static/tv-apk/home-ktv-tv-arm64-v8a.apk";
        private Announcement announcement = new Announcement();

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public long getVersionCode() { return versionCode; }
        public void setVersionCode(long versionCode) { this.versionCode = versionCode; }
        public String getArmeabiV7aApk() { return armeabiV7aApk; }
        public void setArmeabiV7aApk(String armeabiV7aApk) { this.armeabiV7aApk = armeabiV7aApk; }
        public String getArm64V8aApk() { return arm64V8aApk; }
        public void setArm64V8aApk(String arm64V8aApk) { this.arm64V8aApk = arm64V8aApk; }
        public Announcement getAnnouncement() { return announcement; }
        public void setAnnouncement(Announcement announcement) { this.announcement = announcement; }
    }

    public static class Announcement {
        private boolean enabled = true;
        private String id = "";
        private String title = "Android TV 客户端已更新";
        private String message = "新版本已随服务端发布，请根据电视设备架构下载并安装对应的 Android TV APK。升级后请重新扫描曲库；外部只读曲库请保持源文件不变，Managed 曲库请按管理页面提示操作。";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    /**
     * 局域网服务发现配置。
     *
     * LAN service discovery configuration.
     */
    public static class Discovery {
        /** 是否启用服务发现。Whether service discovery is enabled. */
        private boolean enabled = true;
        /** 实例名称。Instance name. */
        private String instanceName = "家庭KTV";
        /** UDP 广播端口。UDP broadcast port. */
        private int udpPort = 18888;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getInstanceName() { return instanceName; }
        public void setInstanceName(String instanceName) { this.instanceName = instanceName; }
        public int getUdpPort() { return udpPort; }
        public void setUdpPort(int udpPort) { this.udpPort = udpPort; }
    }

    /**
     * AI 曲库分析配置。
     *
     * AI music library analysis configuration.
     */
    public static class Ai {
        /** 是否启用 AI 分析。Whether AI analysis is enabled. */
        private boolean enabled = false;
        /** AI API 基础地址。AI API base URL. */
        private String baseUrl = "";
        /** API 密钥。API key. */
        private String apiKey = "";
        /** 使用的模型名称。Model name to use. */
        private String model = "";
        private String bulkModel = "";
        private String reasoningModel = "";
        private String jsonMode = "AUTO";
        private int bulkConcurrency = 2;
        private int reasoningConcurrency = 1;
        /** 自动采纳置信度阈值。Auto-apply confidence threshold. */
        private double autoApplyConfidence = 0.90;
        /** 连接超时秒数。Connect timeout in seconds. */
        private int connectTimeoutSeconds = 10;
        /** 读取超时秒数。Read timeout in seconds. */
        private int readTimeoutSeconds = 60;
        /** 是否明确允许访问本机/内网 AI 服务。Whether private-network AI endpoints are explicitly allowed. */
        private boolean allowPrivateNetwork = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBulkModel() { return bulkModel == null || bulkModel.isBlank() ? model : bulkModel; }
        public void setBulkModel(String bulkModel) { this.bulkModel = bulkModel; }
        public String getReasoningModel() { return reasoningModel; }
        public void setReasoningModel(String reasoningModel) { this.reasoningModel = reasoningModel; }
        public String getJsonMode() { return jsonMode; }
        public void setJsonMode(String jsonMode) { this.jsonMode = jsonMode; }
        public int getBulkConcurrency() { return bulkConcurrency; }
        public void setBulkConcurrency(int bulkConcurrency) { this.bulkConcurrency = bulkConcurrency; }
        public int getReasoningConcurrency() { return reasoningConcurrency; }
        public void setReasoningConcurrency(int reasoningConcurrency) { this.reasoningConcurrency = reasoningConcurrency; }
        public double getAutoApplyConfidence() { return autoApplyConfidence; }
        public void setAutoApplyConfidence(double autoApplyConfidence) { this.autoApplyConfidence = autoApplyConfidence; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
        public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }
        public boolean isAllowPrivateNetwork() { return allowPrivateNetwork; }
        public void setAllowPrivateNetwork(boolean allowPrivateNetwork) { this.allowPrivateNetwork = allowPrivateNetwork; }
    }
}
