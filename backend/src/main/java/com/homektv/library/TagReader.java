package com.homektv.library;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Objects;
import java.util.logging.Level;

/**
 * 内嵌标签解析（P1.1）：ID3(MP3) / Vorbis Comment(FLAC) 等音频格式。
 * 读取标题/歌手/语种/内嵌封面/内嵌歌词。视频容器(mkv/mp4)标签有限，
 * 解析失败时返回空 TagInfo，由文件名兜底（P1.2）。
 */
@Component
public class TagReader {

    private static final Logger log = LoggerFactory.getLogger(TagReader.class);
    static final int MAX_EMBEDDED_IMAGE_BYTES = AssetWriter.MAX_IMAGE_BYTES;
    static final int MAX_EMBEDDED_LYRIC_BYTES = AssetWriter.MAX_LYRIC_BYTES;

    private final AudioTagParser parser;
    private final TagBudgetProbe budgetProbe;

    static {
        // jaudiotagger 默认打印大量 INFO 日志，降噪
        java.util.logging.Logger.getLogger("org.jaudiotagger").setLevel(Level.WARNING);
    }

    public TagReader() {
        this(AudioFileIO::read, new DefaultTagBudgetProbe());
    }

    TagReader(AudioTagParser parser, TagBudgetProbe budgetProbe) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.budgetProbe = Objects.requireNonNull(budgetProbe, "budgetProbe");
    }

    public TagInfo read(File file) {
        TagInfo info = new TagInfo();
        try {
            TagBudget budget = budgetProbe.inspect(file);
            if (!budget.parserSafe()) {
                log.warn("标签元数据超过安全预算或格式无法预检，跳过解析：{}", file.getName());
                return info;
            }

            AudioFile af = parser.read(file);
            Tag tag = af.getTag();
            if (tag == null) return info;

            info.setTitle(firstOrNull(tag, FieldKey.TITLE));
            info.setArtist(firstOrNull(tag, FieldKey.ARTIST));
            info.setLanguage(firstOrNull(tag, FieldKey.LANGUAGE));
            String lyric = firstOrNull(tag, FieldKey.LYRICS);
            if (lyric != null && utf8AtMost(lyric, MAX_EMBEDDED_LYRIC_BYTES)) {
                info.setEmbeddedLyric(lyric);
            } else if (lyric != null) {
                log.warn("内嵌歌词过大，跳过缓存：{}", file.getName());
            }

            Artwork art = tag.getFirstArtwork();
            if (art != null) {
                byte[] image = art.getBinaryData();
                if (image != null && image.length <= MAX_EMBEDDED_IMAGE_BYTES) {
                    info.setCoverImage(image);
                    info.setCoverExt(mimeToExt(art.getMimeType()));
                } else if (image != null) {
                    log.warn("内嵌封面过大，跳过缓存：{}", file.getName());
                }
            }
        } catch (Exception e) {
            // 视频容器或损坏文件解析失败属正常，交由文件名兜底
            log.debug("标签解析失败（将走文件名兜底）：{} - {}", file.getName(), e.getMessage());
        }
        return info;
    }

    static boolean utf8AtMost(String value, int maxBytes) {
        if (value == null) return true;
        if (maxBytes < 0) return false;

        long bytes = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            int codePointBytes;
            if (codePoint <= 0x7f) {
                codePointBytes = 1;
            } else if (codePoint <= 0x7ff) {
                codePointBytes = 2;
            } else if (codePoint <= 0xffff) {
                codePointBytes = 3;
            } else {
                codePointBytes = 4;
            }
            bytes += codePointBytes;
            if (bytes > maxBytes) return false;
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static String firstOrNull(Tag tag, FieldKey key) {
        try {
            String v = tag.getFirst(key);
            return (v == null || v.isBlank()) ? null : v.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String mimeToExt(String mime) {
        if (mime == null) return "jpg";
        String m = mime.toLowerCase();
        if (m.contains("png")) return "png";
        if (m.contains("webp")) return "webp";
        return "jpg";
    }
}

@FunctionalInterface
interface AudioTagParser {

    AudioFile read(File file) throws Exception;
}
