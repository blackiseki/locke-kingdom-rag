package com.locke.ai.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：CORS + 访问日志拦截器。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 请求开始时间的属性名，拦截器和日志里共用。 */
    private static final String START_ATTR = "com.locke.ai.requestStart";

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // TODO 生产环境把 allowedOriginPatterns 收紧成具体域名；
        //      带 Cookie 的请求不能配 "*"，必须用 allowedOriginPatterns + allowCredentials
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccessLogInterceptor()).addPathPatterns("/api/**");
    }

    /**
     * 记录接口耗时。
     *
     * <p>RAG 链路的耗时主要花在「向量检索 + 大模型生成」两段，
     * 先在这里看总耗时，定位细分请用 Micrometer（当前骨架未接）。
     */
    static class AccessLogInterceptor implements HandlerInterceptor {

        private static final Logger log = LoggerFactory.getLogger(AccessLogInterceptor.class);

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            request.setAttribute(START_ATTR, System.nanoTime());
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                    Object handler, Exception ex) {
            Object start = request.getAttribute(START_ATTR);
            long costMs = (start instanceof Long startNanos)
                    ? (System.nanoTime() - startNanos) / 1_000_000L
                    : -1L;
            log.info("{} {} -> {} ({} ms){}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    costMs,
                    ex == null ? "" : " ex=" + ex.getClass().getSimpleName());
        }
    }
}
