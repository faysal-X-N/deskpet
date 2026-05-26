package com.deskpet.data.model

sealed class ImportException(message: String) : Exception(message) {
    class FileNotFound(path: String) : ImportException("文件不存在：$path")
    class InvalidFormat(reason: String) : ImportException("格式错误：$reason")
    class FileTooLarge(sizeMb: Long, maxMb: Long) :
        ImportException("文件过大：${sizeMb}MB，限制${maxMb}MB")
    class ExtractionFailed(cause: Throwable) : ImportException("解压失败：${cause.message}")
}
