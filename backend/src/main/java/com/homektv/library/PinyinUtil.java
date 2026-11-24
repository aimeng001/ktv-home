package com.homektv.library;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

/**
 * 中文转拼音工具（P1.5）。
 * 生成全拼（如 周杰伦→zhoujielun）与首字母（→zjl），供搜索使用。
 * 非中文字符（英文/数字）原样保留小写，多音字取第一个读音。
 */
public final class PinyinUtil {

    private static final int CJK_START = 0x4E00;
    private static final int CJK_END = 0x9FFF;
    private static final String NO_PINYIN = "";
    private static final String[] CJK_PINYIN_CACHE = new String[CJK_END - CJK_START + 1];
    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
    }

    private PinyinUtil() {}

    /** 全拼：周杰伦 → zhoujielun；Jay 周 → jayzhou */
    public static String fullPinyin(String text) {
        if (text == null || text.isEmpty()) return "";
        int len = text.length();
        StringBuilder sb = new StringBuilder(len * 4);
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            String py = pinyinOfChar(c);
            if (py != null) {
                sb.append(py);
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
            // 其它符号忽略
        }
        return sb.toString();
    }

    /** 首字母：周杰伦 → zjl；G.E.M. → gem */
    public static String initials(String text) {
        if (text == null || text.isEmpty()) return "";
        int len = text.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            String py = pinyinOfChar(c);
            if (py != null && !py.isEmpty()) {
                sb.append(py.charAt(0));
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 返回单个汉字的拼音（无声调小写）；非汉字返回 null。对 CJK 基本区惰性缓存（~84KB）避免重复锁与正则开销。 */
    private static String pinyinOfChar(char c) {
        if (c < CJK_START || c > CJK_END) return null; // 非 CJK 基本区
        int idx = c - CJK_START;
        String cached = CJK_PINYIN_CACHE[idx];
        if (cached != null) {
            return cached == NO_PINYIN ? null : cached;
        }
        String resolved = NO_PINYIN;
        try {
            String[] arr = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
            if (arr != null && arr.length > 0 && arr[0] != null) {
                resolved = arr[0]; // 多音字取第一个读音
            }
        } catch (BadHanyuPinyinOutputFormatCombination ignored) {
            // 不会发生（格式固定）
        }
        CJK_PINYIN_CACHE[idx] = resolved;
        return resolved == NO_PINYIN ? null : resolved;
    }
}
