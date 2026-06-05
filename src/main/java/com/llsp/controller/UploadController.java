package com.llsp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.llsp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    @Value("${llsp.upload.dir}")
    private String uploadDir;

    // 允许的图片类型
    private static final java.util.Set<String> ALLOWED_EXTENSIONS = java.util.Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 文件大小校验
            if (image.getSize() > MAX_FILE_SIZE) {
                return Result.fail("文件大小不能超过10MB");
            }
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 文件类型校验
            String suffix = StrUtil.subAfter(originalFilename, ".", true);
            if (StrUtil.isBlank(suffix) || !ALLOWED_EXTENSIONS.contains(suffix.toLowerCase())) {
                return Result.fail("不支持的文件类型，仅允许: " + String.join(", ", ALLOWED_EXTENSIONS));
            }
            // 生成新文件名（相对路径，如 /blogs/a/3/xxx.jpg）
            String fileName = createNewFileName(originalFilename);
            byte[] bytes = image.getBytes();

            saveToLocal(bytes, fileName);
            // 返回完整路径，前端直接使用（Nginx 代理 /imgs/）
            String url = "/imgs" + fileName;
            log.debug("本地文件上传成功，{}", url);
            return Result.ok(url);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        // 防止路径遍历攻击
        if (filename.contains("..") || filename.contains("//") || filename.contains("\\\\")) {
            log.warn("非法文件路径: {}", filename);
            return Result.fail("非法文件路径");
        }
        // 本地删除
        File file = new File(uploadDir, filename);
        if (!file.isDirectory()) {
            FileUtil.del(file);
        }
        return Result.ok();
    }

    /**
     * 保存到本地磁盘
     */
    private void saveToLocal(byte[] bytes, String fileName) throws IOException {
        File targetFile = new File(uploadDir, fileName);
        File parentDir = targetFile.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }
        FileUtil.writeBytes(bytes, targetFile);
    }

    /**
     * 生成散列存储路径: /blogs/{d1}/{d2}/{uuid}.{suffix}
     */
    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
