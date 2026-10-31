package com.locke.ai.util;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * JSON 工具类，专门用于读游戏数据文件（{@code public/data/**}）。
 *
 * <h3>这个 mapper 是独立实例，和 Web 层的不是同一个</h3>
 * 它的关键差异是开了 <b>SNAKE_CASE</b>：游戏数据的字段名全是
 * {@code base_phy_atk}、{@code is_leader_form}、{@code move_pool} 这种下划线风格，
 * 而 Web 接口（{@code ChatRequest}/{@code ChatResponse}）用的是 camelCase。
 * 两者共用一个 mapper 必然有一边对不上，所以这里单独建一个。
 * 接口层的序列化继续走容器注入的 mapper。
 *
 * <h3>Jackson 版本注意</h3>
 * Spring Boot 4.1 的 {@code spring-boot-starter-web} 拉的是 <b>Jackson 3</b>，
 * databind 的包名是 {@code tools.jackson.databind.*}（Jackson 2 是
 * {@code com.fasterxml.jackson.databind.*}）。<b>但注解没搬家</b>——Jackson 3
 * 按设计不带 annotations 模块，仍复用 {@code com.fasterxml.jackson.core:jackson-annotations}，
 * 所以 {@code @JsonIgnoreProperties} 这类注解依旧 import {@code com.fasterxml.jackson.annotation.*}。
 *
 * <p>不要再额外引入 Jackson 2 的 databind，那会让两个大版本同时出现在 classpath 上。
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            // 游戏数据表字段经常增删，未知字段直接忽略，
            // 否则策划加一个字段就能让整批入库挂掉
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // base_phy_atk <-> basePhyAtk
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private JsonUtils() {
    }

    /** 供需要更多能力的调用方使用（例如树模型）。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 JSON 数组。
     *
     * <p>走 {@code elementType.arrayType()} 再转 List，而不是 TypeFactory + JavaType：
     * 前者只依赖 {@code readValue(String, Class)} 这个最稳定的入口，
     * 少了类型工厂 API 变动的风险。
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> fromJsonList(String json, Class<T> elementType) {
        try {
            T[] array = (T[]) MAPPER.readValue(json, elementType.arrayType());
            return array == null ? List.of() : new ArrayList<>(Arrays.asList(array));
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 数组解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析成「对象数组」，用于结构不固定的表
     * （术语、性格、道具这类字段差别较大的小表）。
     *
     * <p>用 Map 而不是强类型 DTO：这些表的字段会随策划改动，
     * 只为取中文字段写一堆 record 不划算。
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> fromJsonMapList(String json) {
        try {
            List<Map<String, Object>> list = MAPPER.readValue(json, List.class);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 数组解析失败: " + e.getMessage(), e);
        }
    }

    /** 解析单个对象（{@code breeding.json} 这种以 key 为根的包装对象）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromJsonMap(String json) {
        try {
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 对象解析失败: " + e.getMessage(), e);
        }
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }
}
