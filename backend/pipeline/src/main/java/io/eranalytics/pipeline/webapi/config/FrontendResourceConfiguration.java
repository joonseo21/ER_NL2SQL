package io.eranalytics.pipeline.webapi.config;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/** Reads build output only; browser routes fall back to index without intercepting API/assets. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class FrontendResourceConfiguration implements WebMvcConfigurer {
    private final String location;

    public FrontendResourceConfiguration(
            @Value("${er.web-api.frontend-location:classpath:/static/}") String location) {
        if (!(location.startsWith("file:") || location.startsWith("classpath:"))
                || !location.endsWith("/")) {
            throw new IllegalArgumentException(
                    "Frontend location must be a file/classpath directory ending in a slash");
        }
        this.location = location;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .resourceChain(false)
                .addResolver(new FrontendResolver());
    }

    static class FrontendResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String path, Resource location) throws IOException {
            if (path.equals("api") || path.startsWith("api/") || path.equals("error")) return null;
            if (path.isEmpty()) return super.getResource("index.html", location);
            Resource found = super.getResource(path, location);
            if (found != null) return found;
            String lastPart = path.substring(path.lastIndexOf('/') + 1);
            if (lastPart.contains(".") || path.equals("assets") || path.startsWith("assets/")) {
                return null;
            }
            return super.getResource("index.html", location);
        }
    }
}
