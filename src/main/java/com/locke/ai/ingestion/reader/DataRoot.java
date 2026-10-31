package com.locke.ai.ingestion.reader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 语料根目录。
 *
 * <p>真实数据是 {@code rocom.aoe.top} 数据站导出的游戏数据包，目录结构固定：
 * <pre>
 * {data-root}/
 *   pets/{id}.json          1065 只精灵，自包含的完整记录（已实装 613 只）
 *   moves.json              475 条技能总表（id 空间与精灵内嵌技能不相交）
 *   items.json              4540 条道具
 *   types.json              19 条属性克制
 *   game_terms.json         53 条术语
 *   personalities.json      30 条性格
 *   magic_items.json        5 条血脉魔法
 *   BinData/*.md            18 个数据字典文件（676 张配置表的索引）
 *   BinData/*.json          745 张原始配置表，共 291MB —— 不向量化
 * </pre>
 *
 * <p>只用一个配置项 {@code locke.ai.ingest.data-root}，子路径在这里推导，
 * 避免 8 个路径配置项各自写错。
 */
@Component
public class DataRoot {

    private final Path root;

    public DataRoot(@Value("${locke.ai.ingest.data-root}") String dataRoot) {
        this.root = Path.of(dataRoot).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path petsDir() {
        return root.resolve("pets");
    }

    public Path binDataDir() {
        return root.resolve("BinData");
    }

    /** 根目录下的单个文件，例如 {@code moves.json}。 */
    public Path file(String fileName) {
        return root.resolve(fileName);
    }

    /**
     * 相对来源标识，写进向量元数据，例如 {@code pets/3001.json}。
     *
     * <p>用它拼检索结果的「出处」，所以要求稳定：只取相对路径，
     * 不含盘符，换机器不会变。
     */
    public String sourceId(String relative) {
        return relative.replace('\\', '/');
    }

    @Override
    public String toString() {
        return root.toString();
    }
}
