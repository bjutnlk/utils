# utils

各种互相独立的工具类集合。每个工具类之间互不依赖，按需取用。

## 环境

- JDK 8+
- Maven 3.6+

## 当前已包含的工具

### 1. ZIP 压缩 / 解压缩工具 —— `com.example.utils.compress.ZipUtils`

纯工具方法，不涉及任何存储 / 落表逻辑。数据流式读写，支持 zip-slip 与 zip-bomb 基础防护。

常用能力：

- `zip(File source, File zipFile)` 压缩文件或目录到 zip 文件
- `zip(File source, OutputStream out)` 压缩到任意输出流（流由调用方关闭）
- `zip(Map<String, byte[]> entries, OutputStream out)` 直接把内存条目打包
- `unzip(File zipFile, File targetDir)` 解压 zip 到目录
- `unzip(InputStream in, File targetDir)` 从流解压到目录（流由调用方关闭）
- `unzipToMap(InputStream in)` 解压为 `Map<entryName, bytes>`，适合"解压后统一遍历再落表"的场景

### 2. 文件存储接口 —— `com.example.utils.storage.FileStorage`

一个流式的文件存储抽象，底层可以对接任一云存储（OSS / S3 / COS / OBS / MinIO 等）。
目前提供一个基于内存 Map 的 mock 实现 `MockCloudFileStorage`，方便本地开发 / 单测。

核心 API：

```java
void upload(String key, InputStream in, ObjectMetadata metadata);
InputStream download(String key);
void download(String key, OutputStream out);
```

#### 关于"流是否需要关闭"

遵循 **"谁打开谁关闭"** 原则：

- **上传**：`InputStream` 由调用方打开，也由 **调用方关闭**。实现内部只负责读到 EOF，
  不会去 close 入参流。调用方推荐 try-with-resources：

  ```java
  try (InputStream in = new FileInputStream(file)) {
      storage.upload(key, in, metadata);
  }
  ```

- **下载（返回 InputStream）**：返回的流由 **调用方关闭**，否则会泄漏底层网络连接：

  ```java
  try (InputStream in = storage.download(key)) {
      // ... 读取 ...
  }
  ```

- **下载（写入 OutputStream）**：调用方传入的 `OutputStream` 由 **调用方关闭**；
  实现类内部自己打开的网络连接 / 对象流由实现类自己关闭。

#### 关于"上传是否需要指定类型"

**需要**。上传时通过 `ObjectMetadata` 显式指定 `contentType`，原因：

1. 字符、文档、zip、图片等在云存储上本质只是 MIME 类型不同，指定后可让浏览器 / 下游
   工具正确识别（例如图片可直接在浏览器渲染、zip 会触发下载、JSON 不会被当成纯文本）。
2. 接口侧不对文件做类型嗅探 —— 让调用方按业务语义指定，避免嗅探错误或多读一遍流。
3. 大部分云存储 SDK（S3/OSS/COS…）本身就要求在 PutObject 时把 contentType 放进
   request metadata 里。

`ObjectMetadata` 同时支持：
- `contentLength`：建议提供，未知时可传 `-1`（部分云存储会走分片上传）
- `userMetadata`：业务自定义 kv，写入对象的 x-\*-meta- 头

内置常量覆盖常见场景：`CONTENT_TYPE_TEXT_PLAIN` / `CONTENT_TYPE_JSON` /
`CONTENT_TYPE_ZIP` / `CONTENT_TYPE_PDF` / `CONTENT_TYPE_PNG` / `CONTENT_TYPE_JPEG`
/ `CONTENT_TYPE_DOCX` / `CONTENT_TYPE_XLSX` / `CONTENT_TYPE_OCTET_STREAM`。

#### 典型使用：解压后统一落表

解压和打包只是纯工具方法，不涉及数据存储。上层拿到解压结果后再遍历做存储 / 落表：

```java
Map<String, byte[]> unzipped = ZipUtils.unzipToMap(zipInputStream);
for (Map.Entry<String, byte[]> e : unzipped.entrySet()) {
    try (InputStream in = new ByteArrayInputStream(e.getValue())) {
        storage.upload("biz/" + e.getKey(), in,
                ObjectMetadata.of(guessContentType(e.getKey()), e.getValue().length));
    }
    // 再写 DB ...
}
```

## 构建与测试

```bash
mvn -q test
```
