package com.locke.ai.ingestion.reader;

import com.locke.ai.exception.IngestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 读取 {@code BinData/*.md}（数据字典）。
 *
 * <p>BinData 目录下同时有 18 个 {@code .md} 和 745 个 {@code .json}（共 291MB），
 * 这里<b>只读 .md</b>——按后缀过滤，不会误读那 291MB 的原始配置表。
 *
 * <p><b>这批语料的定位要清楚</b>：它不是游戏知识，而是「哪张配置表装了什么」的索引，
 * 每条就是「文件名 + 一句话用途 + 记录数 + 首记录字段名」。
 * 所以它能回答「战斗数值配置在哪张表」，但回答不了「火花有什么技能」。
 * 量小（18 个文件 193KB）所以值得入，但不要指望它撑起问答质量。
 *
 * <p>切分交给 {@code MarkdownDocumentReader} 按标题层级做，
 * 长段落再由 {@code TokenTextSplitter} 兜底。
 */
@Component
public class WikiMarkdownReader {

    private static final Logger log = LoggerFactory.getLogger(WikiMarkdownReader.class);

    private final DataRoot dataRoot;

    public WikiMarkdownReader(DataRoot dataRoot) {
        this.dataRoot = dataRoot;
    }

    /** @param source 源文件路径 @param documents 该文件切出的文档块 */
    public record Entry(Path source, List<Document> documents) {
    }

    public record ReadResult(List<Entry> entries, List<String> warnings) {
        public ReadResult {
            entries = List.copyOf(entries);
            warnings = List.copyOf(warnings);
        }
    }

    public ReadResult readAll() {
        Path dir = dataRoot.binDataDir();
        if (!Files.isDirectory(dir)) {
            throw new IngestException("数据字典目录不存在: " + dir
                    + "（应为 {data-root}/BinData，检查 locke.ai.ingest.data-root）");
        }

        List<Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int fileCount = 0;

        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    // 关键：只取 .md，跳过同目录下 745 个 .json 配置表
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted()
                    .toList();
            fileCount = files.size();

            for (Path file : files) {
                try {
                    entries.add(read(file));
                } catch (RuntimeException e) {
                    String warning = file.getFileName() + ": " + e.getMessage();
                    log.warn("跳过解析失败的 Markdown -> {}", warning);
                    warnings.add(warning);
                }
            }
        } catch (IOException e) {
            throw new IngestException("遍历数据字典目录失败: " + dir, e);
        }

        log.info("数据字典读取完成：{} 个文件，切出 {} 个文档块",
                fileCount, entries.stream().mapToInt(entry -> entry.documents().size()).sum());
        return new ReadResult(entries, warnings);
    }

    public Entry read(Path file) {
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                // 不要把 --- 当切分点：数据字典里它多半是排版分隔线
                .withHorizontalRuleCreateDocument(false)
                // 保留代码块和引用块，字段名清单常写在里面
                .withIncludeCodeBlock(true)
                .withIncludeBlockquote(true)
                .withAdditionalMetadata("source", sourceId(file))
                .withAdditionalMetadata("docType", "data-dictionary")
                .build();

        List<Document> documents = new MarkdownDocumentReader(new FileSystemResource(file), config).get();
        return new Entry(file, documents);
    }

    /** 稳定来源标识，形如 {@code BinData/3.战斗、技能与属性.md}。 */
    public String sourceId(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        Path dir = dataRoot.binDataDir();
        Path relative = normalized.startsWith(dir) ? dir.relativize(normalized) : normalized.getFileName();
        return dataRoot.sourceId("BinData/" + relative.toString().replace('\\', '/'));
    }
}
