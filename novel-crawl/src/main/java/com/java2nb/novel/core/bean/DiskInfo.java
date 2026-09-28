package com.java2nb.novel.core.bean;

import lombok.Data;

/**
 * @author xiongxiaoyang
 * @date 2025/10/24
 */
@Data
public class DiskInfo {

    private String path;
    private double totalGB;
    private double freeGB;
    private double usage;

    public DiskInfo(String path, long totalBytes, long freeBytes, double usage) {
        this.path = path;
        this.totalGB = bytesToGB(totalBytes);
        this.freeGB = bytesToGB(freeBytes);
        this.usage = usage;
    }

    private double bytesToGB(long bytes) {
        return bytes / (1024.0 * 1024.0 * 1024.0);
    }

}
