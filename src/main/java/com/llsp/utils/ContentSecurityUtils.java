package com.llsp.utils;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 内容安全检测工具
 * 用于评论、笔记等用户生成内容的安全过滤
 */
public class ContentSecurityUtils {

    // 敏感词黑名单（可扩展）
    private static final Set<String> SENSITIVE_WORDS = Set.of(
        // 政治敏感
        "分裂", "颠覆", "暴动",
        // 色情
        "色情", "淫秽", "成人",
        // 赌博
        "赌博", "赌场", "博彩", "六合彩",
        // 诈骗/违法
        "诈骗", "洗钱", "枪支", "毒品", "假币",
        "办证", "刻章", "发票代开",
        // 广告/垃圾
        "贷款", "套现", "信用卡代办",
        // 恶意脚本
        "<script", "</script", "javascript:", "onerror=", "onload="
    );

    // 危险的HTML标签正则
    private static final Pattern SCRIPT_PATTERN = Pattern.compile(
        "<(script|iframe|object|embed|form|link|meta|applet)[^>]*>.*?</\\1>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern EVENT_HANDLER_PATTERN = Pattern.compile(
        "\\s+on\\w+\\s*=",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern JAVASCRIPT_URL_PATTERN = Pattern.compile(
        "javascript\\s*:",
        Pattern.CASE_INSENSITIVE
    );

    // 内容长度限制
    public static final int MAX_CONTENT_LENGTH = 5000;
    public static final int MAX_TITLE_LENGTH = 200;

    /**
     * 检测内容是否安全
     * @param content 待检测的文本内容
     * @return null 表示通过检测，否则返回错误信息
     */
    public static String validateContent(String content) {
        if (content == null || content.isBlank()) {
            return null; // 空内容由业务层校验
        }

        // 1. 长度检测
        if (content.length() > MAX_CONTENT_LENGTH) {
            return "内容过长，最多允许" + MAX_CONTENT_LENGTH + "个字符";
        }

        // 2. 检测恶意脚本
        if (SCRIPT_PATTERN.matcher(content).find()) {
            return "内容包含不安全的HTML标签";
        }

        if (EVENT_HANDLER_PATTERN.matcher(content).find()) {
            return "内容包含不安全的属性";
        }

        if (JAVASCRIPT_URL_PATTERN.matcher(content).find()) {
            return "内容包含不安全的链接";
        }

        // 3. 敏感词检测
        String lower = content.toLowerCase();
        for (String word : SENSITIVE_WORDS) {
            if (lower.contains(word.toLowerCase())) {
                return "内容包含违规词，请修改后重试";
            }
        }

        return null;
    }

    /**
     * 检测标题是否安全
     * @param title 标题
     * @return null 表示通过检测，否则返回错误信息
     */
    public static String validateTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }

        if (title.length() > MAX_TITLE_LENGTH) {
            return "标题过长，最多允许" + MAX_TITLE_LENGTH + "个字符";
        }

        // 标题同样做脚本和敏感词检测
        return validateContent(title);
    }

    /**
     * 清理HTML标签（保留纯文本）
     * @param content 原始内容
     * @return 清理后的纯文本
     */
    public static String stripHtml(String content) {
        if (content == null) {
            return null;
        }
        // 移除所有HTML标签
        return content.replaceAll("<[^>]*>", "")
                      .replaceAll("&lt;[^&]*&gt;", "")
                      .trim();
    }
}
