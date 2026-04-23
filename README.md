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

### 3. ZIP 解压 + 打包（可落表 + 可对接云存储）—— `com.example.utils.compress.archive`

上一节的 `ZipUtils` 是纯粹的 zip 工具。本节在它之上提供一套**结构化**方案：

> 典型业务：用户上传一个 zip → 系统解压 → 每个文件落表 + 云存储 → 业务侧对每个
> 文件做处理并生成"结果文件" → 按原 zip 结构把结果文件重新打成一个 zip 返回。

这一层刻意按"职责分离"切成了三块，彼此独立：

- **`ZipArchiver`** —— 纯 zip 工具，**不依赖 DB、不依赖云存储**。
  - `extract(taskId, in) → List<ArchiveEntry>`：解压为扁平 list，FILE 节点的字节挂在
    `entry.content` 上，供调用方上传云存储 / 做进一步处理。
  - `pack(List<ArchiveEntry>, out)`：按 list 里的树形关系打回 zip。
    要求每个 FILE 节点的 `entry.content` 都已经填好。
- **`ArchiveStorage`** —— 云存储空接口（`uploadAll` / `downloadAll`）。
  业务侧自行对接 OSS / S3 / COS 等，本工程 **不提供**实现。
- **`ArchiveEntryRepository`** —— 落表空接口（`batchInsert` / `findByTaskId`）。
  业务侧用 MyBatis / JPA / JDBC 实现即可，本工程 **不提供**实现。

#### 表结构 `zip_extract_record`

```sql
CREATE TABLE zip_extract_record (
    id           BIGINT        PRIMARY KEY AUTO_INCREMENT,
    task_id      VARCHAR(64)   NOT NULL                COMMENT '任务ID，一个zip一个taskId',
    entry_name   VARCHAR(255)  NOT NULL                COMMENT '当前层名字',
    full_path    VARCHAR(1024) NOT NULL                COMMENT '相对根zip的完整路径；嵌套zip用"!/"分隔',
    parent_path  VARCHAR(1024) NULL                    COMMENT '父节点full_path；根层为NULL',
    entry_type   VARCHAR(16)   NOT NULL                COMMENT 'FILE / DIRECTORY / NESTED_ZIP',
    depth        INT           NOT NULL DEFAULT 0      COMMENT '层级深度，根层=0',
    storage_key  VARCHAR(512)  NULL                    COMMENT '云存储对象key，仅FILE有值',
    size         BIGINT        NOT NULL DEFAULT 0      COMMENT '文件字节数',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_path (task_id, full_path),
    KEY idx_task (task_id),
    KEY idx_task_parent (task_id, parent_path)
) COMMENT 'zip解压记录，一个task对应一棵树';
```

几点说明：

- 父子关系用 **`parent_path`**（而不是 `parent_id`）表达，这样解压结果可以一次性
  `batchInsert`，不需要等自增 id 回填。
- `(task_id, full_path)` 唯一，**天然解决"不同目录重名"**：路径不同即不冲突。
- 只有 `FILE` 节点有 `storage_key`；`DIRECTORY` / `NESTED_ZIP` 为 `NULL`，不占云存储。
- 嵌套 zip 用 `!/` 分隔（和 Java URL / war-inside-jar 习惯一致），例如
  `pack/nested.zip!/inner/a.txt`。
- **不带 `sort_order`**：只保证目录结构完整，同层顺序不强求。

#### 边界场景处理

| 场景              | 处理方式                                                               |
| ----------------- | ---------------------------------------------------------------------- |
| 嵌套 zip          | 识别为 `NESTED_ZIP` 节点 + 其下子树；自身不占云存储；打包时子树重打    |
| 空文件夹          | 产出 `DIRECTORY` 节点，打包时显式写 `dir/` 目录 entry 还原             |
| 不同目录重名      | 每条 `full_path` 不同 → 落表不冲突，云存储 key 互相独立                |
| zip-slip          | 路径里的 `..`、`./` 在解析阶段归一化，不会逃出根                       |
| zip-bomb          | 单文件最大字节数限制（默认 1GB），超过抛 `IOException`                 |

#### 典型使用

```java
// ========== 解压 + 落表 + 上传云存储 ==========
List<ArchiveEntry> entries;
try (InputStream in = new FileInputStream("input.zip")) {
    entries = ZipArchiver.extract("task-001", in);
}
archiveStorage.uploadAll(entries);     // FILE 节点：content 上传 → 回填 storageKey → 清空 content
archiveEntryRepository.batchInsert(entries);  // 整份 list 批量入库

// ========== 业务侧对每个 FILE 做处理并维护"原文件 → 结果文件"映射 ==========
// （UPDATE zip_extract_record SET storage_key = <结果文件key> WHERE id = ?）

// ========== 打包 ==========
List<ArchiveEntry> toPack = archiveEntryRepository.findByTaskId("task-001");
archiveStorage.downloadAll(toPack);    // 按 storageKey 下载并回填 content
try (OutputStream out = new FileOutputStream("result.zip")) {
    ZipArchiver.pack(toPack, out);
}
```

三步里 `ZipArchiver` 和 `ArchiveStorage` / `ArchiveEntryRepository` **没有任何直接耦合**，
串联过程完全由上层业务把控，方便替换存储、替换 ORM、插入自己的前后处理逻辑。

## 构建与测试

```bash
mvn -q test
```
