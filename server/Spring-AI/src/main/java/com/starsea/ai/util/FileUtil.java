package com.fansea.ai.util;

import java.util.Objects;

public class FileUtil {

    public static String getFileTypeByExtension(String fileName) {
        Objects.requireNonNull(fileName);
        // 获取文件名中最后一个点的索引
        int lastDotIndex = fileName.lastIndexOf('.');
        // 如果最后一个点的索引大于0且小于文件名的长度减1
        if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
            // 返回文件名中最后一个点后面的字符串，并将其转换为小写
            return fileName.substring(lastDotIndex + 1).toLowerCase();
        }
        return "another";
    }

}