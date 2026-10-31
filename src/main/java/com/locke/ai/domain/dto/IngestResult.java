package com.locke.ai.domain.dto;

import java.util.List;

/**
 * 入库结果统计。
 *
 * <p>字段分两组：<b>向量侧</b>（文档、切块、写入）和<b>结构化侧</b>（精灵、技能、关联、属性表）。
 * 一次入库会同时写两边，报告里两边的数字都要能看到——
 * 只有向量数没有精灵数，就没法确认结构化表到底有没有灌进去。
 *
 * @param dryRun              是否为试运行（只解析和切分，不写任何库）
 * @param filesScanned        扫描到的语料文件数
 * @param documentsParsed     解析出的原始文档/记录数（一只精灵算一条）
 * @param chunksCreated       切分后的分块数
 * @param chunksWritten       实际写入向量库的分块数（dryRun 时为 0）
 * @param spiritsWritten      写入 spirit 表的精灵数（dryRun 时为 0）
 * @param skillsWritten       写入 skill 表的技能数
 * @param skillLinksWritten   写入 spirit_skill 的关联数
 * @param elementTypesWritten 写入 element_type 的属性数
 * @param skippedSpirits      因 implemented=false 被跳过的精灵数
 * @param vectorStoreTotal    入库后向量库总条数（dryRun 时不做统计，为 -1）
 * @param warnings            非致命问题，例如某个文件解析失败
 * @param elapsedMs           总耗时
 */
public record IngestResult(
        boolean dryRun,
        int filesScanned,
        int documentsParsed,
        int chunksCreated,
        int chunksWritten,
        int spiritsWritten,
        int skillsWritten,
        int skillLinksWritten,
        int elementTypesWritten,
        int skippedSpirits,
        int vectorStoreTotal,
        List<String> warnings,
        long elapsedMs) {

    public IngestResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static IngestResult empty(boolean dryRun, long elapsedMs) {
        return new IngestResult(dryRun, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, List.of(), elapsedMs);
    }

    /** 在已有统计上追加一条警告，避免各处手工 new。 */
    public IngestResult withWarning(String warning) {
        List<String> merged = new java.util.ArrayList<>(warnings);
        merged.add(warning);
        return new IngestResult(dryRun, filesScanned, documentsParsed, chunksCreated, chunksWritten,
                spiritsWritten, skillsWritten, skillLinksWritten, elementTypesWritten,
                skippedSpirits, vectorStoreTotal, merged, elapsedMs);
    }
}
