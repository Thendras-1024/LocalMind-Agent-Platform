package org.javaup.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.dto.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    private static final String PUBLIC_IMAGE_PREFIX = "/imgs/";
    private static final Set<String> ALLOWED_SUFFIXES = Set.of("jpg", "jpeg", "png", "webp", "gif");

    @Value("${localmind.image-upload-dir:./data/uploads/imgs/}")
    private String imageUploadDir;

    @PostMapping("blog")
    public Result<String> uploadImage(@RequestParam("file") MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return Result.fail("上传文件不能为空");
        }
        String originalFilename = image.getOriginalFilename();
        String suffix = normalizeSuffix(originalFilename);
        if (!ALLOWED_SUFFIXES.contains(suffix)) {
            return Result.fail("仅支持 jpg、jpeg、png、webp、gif 格式图片");
        }

        try {
            String publicPath = createBlogPublicPath(suffix);
            Path target = resolveStoragePath(publicPath);
            Files.createDirectories(target.getParent());
            image.transferTo(target);
            log.debug("Blog image uploaded: {}", publicPath);
            return Result.ok(publicPath);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result<Void> deleteBlogImg(@RequestParam("name") String filename) {
        return doDeleteBlogImg(filename);
    }

    @DeleteMapping("/blog/delete")
    public Result<Void> deleteBlogImgByDelete(@RequestParam("name") String filename) {
        return doDeleteBlogImg(filename);
    }

    private Result<Void> doDeleteBlogImg(String filename) {
        if (StrUtil.isBlank(filename)) {
            return Result.fail("文件名不能为空");
        }
        Path file;
        try {
            file = resolveStoragePath(filename);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        if (Files.isDirectory(file)) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file.toFile());
        return Result.ok();
    }

    private String createBlogPublicPath(String suffix) {
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        return StrUtil.format("{}blogs/{}/{}/{}.{}", PUBLIC_IMAGE_PREFIX, d1, d2, name, suffix);
    }

    private Path resolveStoragePath(String publicPath) {
        String normalized = publicPath.replace('\\', '/');
        if (normalized.startsWith("/imgs/imgs/")) {
            normalized = normalized.substring("/imgs".length());
        }
        if (!normalized.startsWith(PUBLIC_IMAGE_PREFIX)) {
            throw new IllegalArgumentException("只能删除或访问 /imgs/ 下的文件");
        }
        String relative = normalized.substring(PUBLIC_IMAGE_PREFIX.length());
        Path root = Paths.get(imageUploadDir).toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        return target;
    }

    private String normalizeSuffix(String originalFilename) {
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        if (StrUtil.isBlank(suffix)) {
            return "";
        }
        return suffix.toLowerCase(Locale.ROOT);
    }
}
