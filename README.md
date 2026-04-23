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

### 3. ZIP 解压 + 打包服务（带落表）—— `com.example.utils.compress.archive`

上一节的 `ZipUtils` 是纯内存 / 磁盘工具，不关心存储。
当业务需要"**上传一个 zip → 落表 → 拿到每个文件分别处理 → 再按原结构打回一个 zip**"时，
使用这一节的一套服务：

- `ZipExtractService`：解压入口，把 zip 里每个文件上传到 `FileStorage`，同时在
  `ZipExtractRecordRepository` 落一棵"目录树"。
- `ZipPackService`：打包入口，按 `taskId` 反查记录 → 拉取存储 → 按原结构（含嵌套 zip、
  空目录）还原为 zip。

> 按题目约定：解压**只**记录解压出来的节点；调用方自己维护"原文件 → 结果文件"映射，
> 打包前只需要把相应 record 的 `storageKey` 改成结果文件的 key，`ZipPackService`
> 就会把结果文件按原目录结构塞回 zip。

#### 落表结构 `zip_extract_record`

一张表同时表达**目录树**和**叶子文件在云存储的位置**：

| 字段          | 含义                                                                 |
| ------------- | -------------------------------------------------------------------- |
| `id`          | 主键                                                                 |
| `task_id`     | 任务 ID，一个 zip 一个 task_id，是打包时的入口                       |
| `parent_id`   | 父节点 id；根节点为 `null`                                           |
| `entry_name`  | 当前层名字（单层，不含路径分隔符）                                   |
| `full_path`   | 相对根 zip 的完整路径；**嵌套 zip 用 `!/` 分隔**（调试可读）         |
| `entry_type`  | `FILE` / `DIRECTORY` / `NESTED_ZIP`                                  |
| `depth`       | 层级深度（根层下的节点 = 0）                                         |
| `sort_order`  | 同 parent 下的原始顺序，保证多次打包顺序稳定                         |
| `storage_key` | 云存储对象 key；**仅 FILE 有值**，DIRECTORY / NESTED_ZIP 为 `null`   |
| `size`        | 文件字节数；DIRECTORY / NESTED_ZIP 为 0                              |
| `created_at`  | 创建时间                                                             |

索引建议：`(task_id)`、`(task_id, parent_id)`、`(task_id, full_path)` 唯一索引。

#### 各类边界场景的处理约定

- **嵌套 zip**：识别后展开为 `NESTED_ZIP` 容器节点 + 其下子树。嵌套 zip **自身不占云存储**，
  打包时由子树重新组装出一个 zip 再作为外层的一个 entry 写入。
- **空文件夹**：落一行 `DIRECTORY`，打包时显式写一个 `dir/` 目录条目还原。
- **不同目录重名**：每行 `full_path` 都不一样，`storage_key` 各自独立，天然无冲突。
- **zip-slip**：路径里的 `..` / `./` 会在解析阶段归一化，不会逃出根。
- **zip-bomb**：解压时单文件超出 `maxFileSize`（默认 1GB）会抛 `IOException`。

#### 典型使用

```java
FileStorage storage = new MockCloudFileStorage();           // 换成真实云存储即可
ZipExtractRecordRepository repo = new InMemoryZipExtractRecordRepository(); // 换成 DB

ZipExtractService extract = new ZipExtractService(storage, repo);
ZipPackService   pack    = new ZipPackService(storage, repo);

// 1) 解压：上传每个文件到云存储，并落一棵树
try (InputStream in = new FileInputStream("input.zip")) {
    extract.extract("task-001", in);
}

// 2) 业务侧拿到每个 record 的 storageKey，生成"处理后的结果文件"并上传成新 key，
//    再把对应 record 的 storageKey 改指到新 key（或者直接 UPDATE 数据库）

// 3) 打包：按原 zip 结构输出
try (OutputStream out = new FileOutputStream("result.zip")) {
    pack.pack("task-001", out);
}
```

## 构建与测试

```bash
mvn -q test
```
