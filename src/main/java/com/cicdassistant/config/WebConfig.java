package com.cicdassistant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * /vendor/** 下都是版本锁定的第三方静态资源（Tabler / Bootstrap / 图标字体），
     * 永远不会"内容变化但路径不变"，所以可以让浏览器强缓存 1 年。
     * 否则每次切页都会重新初始化图标字体 (~870KB)，肉眼看就是"图标慢慢出来"。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/vendor/**")
                .addResourceLocations("classpath:/static/vendor/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic());
    }
}
