package com.tracedesk.service;

import com.tracedesk.web.exception.ApiException;
import com.tracedesk.web.exception.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

@Service
public class StorageService {

    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024; // 5 МБ

    private final MinioClient minioClient;
    private final String bucketName;
    private volatile boolean bucketInitialized = false;

    public StorageService(
            MinioClient minioClient,
            @Value("${minio.bucket}") String bucketName
    ) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
    }

    public StoredAttachment uploadAttachment(MultipartFile file) {
        validateFile(file);
        ensureBucketExists();

        String detectedMimeType = detectAndValidateMagicBytes(file);
        String originalFilename = sanitizeFilename(file.getOriginalFilename(), detectedMimeType);
        String objectKey = "tickets/" + UUID.randomUUID() + "_" + originalFilename;

        try (InputStream is = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(is, file.getSize(), -1)
                            .contentType(detectedMimeType)
                            .build()
            );
            return new StoredAttachment(objectKey, originalFilename, detectedMimeType, file.getSize());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.STORAGE_ERROR, "Не удалось сохранить вложение в MinIO");
        }
    }

    public byte[] downloadAttachment(String objectKey) {
        ensureBucketExists();
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectKey)
                        .build()
        )) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new ApiException(ErrorCode.ATTACHMENT_NOT_FOUND, "Не удалось прочитать вложение из хранилища");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty() || file.getSize() <= 0) {
            throw new ApiException(ErrorCode.INVALID_FILE, "Передан пустой файл вложения");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE, "Размер файла превышает 5 МБ");
        }
    }

    private String detectAndValidateMagicBytes(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            byte[] header = is.readNBytes(8);
            if (isPng(header)) {
                return "image/png";
            }
            if (isJpeg(header)) {
                return "image/jpeg";
            }
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INVALID_FILE, "Ошибка чтения файла вложения");
        }
        throw new ApiException(ErrorCode.INVALID_FILE, "Допускаются только изображения формата PNG или JPEG");
    }

    private boolean isPng(byte[] h) {
        return h.length >= 8
                && (h[0] & 0xFF) == 0x89
                && (h[1] & 0xFF) == 0x50
                && (h[2] & 0xFF) == 0x4E
                && (h[3] & 0xFF) == 0x47
                && (h[4] & 0xFF) == 0x0D
                && (h[5] & 0xFF) == 0x0A
                && (h[6] & 0xFF) == 0x1A
                && (h[7] & 0xFF) == 0x0A;
    }

    private boolean isJpeg(byte[] h) {
        return h.length >= 3
                && (h[0] & 0xFF) == 0xFF
                && (h[1] & 0xFF) == 0xD8
                && (h[2] & 0xFF) == 0xFF;
    }

    private String sanitizeFilename(String original, String mimeType) {
        if (original == null || original.isBlank()) {
            return "image/png".equals(mimeType) ? "attachment.png" : "attachment.jpg";
        }
        String clean = original.replaceAll("[^a-zA-Z0-9._-]", "_");
        return clean.isBlank() ? "attachment.png" : clean;
    }

    private synchronized void ensureBucketExists() {
        if (bucketInitialized) {
            return;
        }
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build()
            );
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucketName).build()
                );
            }
            bucketInitialized = true;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.STORAGE_ERROR, "Хранилище MinIO недоступно");
        }
    }

    public record StoredAttachment(
            String key,
            String originalName,
            String contentType,
            long size
    ) {
    }
}