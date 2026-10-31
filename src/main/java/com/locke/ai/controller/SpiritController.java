package com.locke.ai.controller;

import com.locke.ai.domain.entity.Spirit;
import com.locke.ai.domain.enums.ElementType;
import com.locke.ai.repository.SpiritRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 结构化查询接口：直接查库，不走向量检索。
 *
 * <p>「火花的技能有哪些」这类精确问题走这里比走 RAG 更快更准；
 * RAG 负责的是「怎么打某关」这类语义问题。两者在
 * {@code RetrieverService} 里做路由（向量优先，没命中才回退到结构化查询）。
 */
@RestController
@RequestMapping("/api/spirits")
public class SpiritController {

    private final SpiritRepository spiritRepository;

    public SpiritController(SpiritRepository spiritRepository) {
        this.spiritRepository = spiritRepository;
    }

    /** 精确按中文名查一只精灵（含技能）。查不到返回 404。 */
    @GetMapping("/{name}")
    public ResponseEntity<Spirit> getByName(@PathVariable String name) {
        return spiritRepository.findByDisplayName(name)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 按中文名模糊搜索（原设计外的补充接口，前端搜索框需要）。 */
    @GetMapping("/search")
    public List<Spirit> search(@RequestParam("q") String keyword,
                               @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return spiritRepository.searchByDisplayName(keyword, clampLimit(limit, 10));
    }

    /** 按属性列表，例如 {@code /api/spirits/by-element/火} 或 {@code /by-element/FIRE}。 */
    @GetMapping("/by-element/{element}")
    public List<Spirit> byElement(@PathVariable String element,
                                  @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return spiritRepository.findByElement(ElementType.fromJson(element), clampLimit(limit, 20));
    }

    /**
     * 防止前端传 {@code limit=100000} 把库拖垮。
     *
     * <p>上限 100 是经验值：这个接口返回的是完整精灵对象（含技能列表），
     * 一次返回几百条响应体会非常大。
     */
    private static int clampLimit(int limit, int fallback) {
        if (limit <= 0) {
            return fallback;
        }
        return Math.min(limit, 100);
    }
}
