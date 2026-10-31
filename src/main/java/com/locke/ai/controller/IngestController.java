package com.locke.ai.controller;

import com.locke.ai.domain.dto.IngestResult;
import com.locke.ai.service.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入库接口。
 *
 * <p><b>安全提醒</b>：这个接口会触发全量解析和写库（并调用 embedding 模型），
 * 属于运维操作，生产环境必须加鉴权 + 内网限制，
 * 否则任何人都能反复触发把库和机器一起拖垮。当前骨架没有引 Security，
 * 是为了先跑通主流程。
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * 请求体。放在控制器内部是有意的：这是接口层的形状，
     * 不属于 {@code domain/dto} 里可复用的领域数据结构。
     *
     * @param source all / pets / moves / items / reference / wiki，缺省 all
     * @param dryRun true 时只解析和切分，不写库
     */
    public record IngestRequest(String source, Boolean dryRun) {
    }

    /**
     * 触发入库。
     *
     * <p>请求体可以为空，等价于 {@code {"source":"all","dryRun":false}}。
     * <b>建议先用 {@code dryRun=true} 试跑</b>：返回里的
     * {@code documentsParsed} / {@code chunksCreated} 就是本次会写入多少向量，
     * 可以据此判断语料规模是否符合预期，再真正入库。
     */
    @PostMapping
    public IngestResult ingest(@RequestBody(required = false) IngestRequest request) {
        String source = request == null ? null : request.source();
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());

        log.info("收到入库请求 source={} dryRun={}", source == null ? "all" : source, dryRun);
        return ingestService.ingest(source, dryRun);
    }
}
